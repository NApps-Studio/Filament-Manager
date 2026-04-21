package com.napps.filamentmanager.webscraper

import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.napps.filamentmanager.database.VendorFilament
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * HeadlessWebViewSync uses a hidden WebView to scrape product data from vendor websites.
 */
class HeadlessWebViewSync(private val context: Context) {

    class CloudflareChallengeException(message: String) : Exception(message)
    class LanguageMismatchException(message: String) : Exception(message)

    data class SyncResult(
        val filaments: List<VendorFilament>,
        val summary: String,
        val details: String,
        val affectedVariants: Int,
        val errorCount: Int,
        val isError: Boolean
    )

    private var webView: WebView? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private suspend fun getWebView(): WebView = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
            layout(0, 0, 1280, 2000)
        }
        webView = wv
        wv
    }

    suspend fun loadPageAndGetHtml(url: String): String? = withContext(Dispatchers.Main) {
        val wv = getWebView()
        wv.loadUrlAndWait(url)
    }

    suspend fun sync(
        productLinks: List<ProductLink>,
        vendor: StartPagesOfVendors,
        syncType: String,
        availabilityOnly: Boolean = false,
        onProgress: (pagesDone: Int, totalPages: Int, success: Int, failed: Int, newFilaments: List<VendorFilament>) -> Unit
    ): SyncResult {
        val allFilaments = mutableListOf<VendorFilament>()
        var successCount = 0
        var failureCount = 0
        val totalPages = productLinks.size

        productLinks.forEachIndexed { index, productLink ->
            // Update progress BEFORE starting the scrape for this page
            onProgress(index, totalPages, successCount, failureCount, emptyList())
            
            val result = scrapeProductPage(productLink, availabilityOnly = availabilityOnly)
            if (result.filaments.isNotEmpty()) {
                allFilaments.addAll(result.filaments)
                successCount += result.filaments.size
            } else {
                failureCount++
            }
            // Update progress again AFTER finishing the scrape with the actual results
            onProgress(index + 1, totalPages, successCount, failureCount, result.filaments)
        }

        return SyncResult(allFilaments, "$syncType complete", "Synced ${allFilaments.size} items", allFilaments.size, failureCount, failureCount > 0)
    }

    suspend fun scrapeProductPage(productLink: ProductLink, availabilityOnly: Boolean = false): SyncResult = withContext(Dispatchers.Main) {
        val wv = getWebView()
        val initialUrl = productLink.url
        val forcedEnglishUrl = initialUrl + (if (initialUrl.contains("?")) "&ls=en" else "?ls=en")
        val finalFilamentsMap = mutableMapOf<String, VendorFilament>()
        val allColorThumbnails = mutableMapOf<String, String>()

        try {
            Log.d("BambuSync", "Initial load (${if(availabilityOnly) "Avail-Only" else "Full"}): $forcedEnglishUrl")
            val initialHtml = withTimeoutOrNull(45000) { wv.loadUrlAndWait(forcedEnglishUrl) }
                ?: return@withContext SyncResult(emptyList(), "Timeout", "Page load timed out", 0, 1, true)

            var currentHtml = initialHtml
            // Fallback: If still not English after forcing URL param, try one more time.
            if (!isEnglish(currentHtml)) {
                Log.w("BambuSync", "Page not in English after forcing param. Retrying...")
                wv.loadUrl(forcedEnglishUrl)
                delay(2000)
                currentHtml = unescapeHtml(wv.evaluateJavascriptSync("document.documentElement.outerHTML") ?: "")
            }

            // 1. Get all variants from JSON-LD to identify unique "types"
            val jsonLd = unescapeHtml(wv.evaluateJavascriptSync("document.getElementById('product-jsonld')?.innerHTML") ?: "").trim().removeSurrounding("\"")
            if (jsonLd.isEmpty() || jsonLd == "null") {
                return@withContext SyncResult(emptyList(), "Error", "No JSON-LD found", 0, 1, true)
            }

            val fullParsedBatch = parseBambulabProductPage(jsonLd, isJsonOnly = true) ?: emptyList()
            if (fullParsedBatch.isEmpty()) return@withContext SyncResult(emptyList(), "Error", "Parsed batch empty", 0, 1, true)

            if (availabilityOnly) {
                // For background sync, we just want the availability from the JSON-LD
                fullParsedBatch.forEach { finalFilamentsMap[it.sku ?: "UNK_${it.variantName}"] = it }
                val results = finalFilamentsMap.values.toList()
                return@withContext SyncResult(results, "Success", "Updated availability for ${results.size} variants", results.size, 0, false)
            }

            // 2. Group variants by their "Material Type"
            val typeGroups = fullParsedBatch.groupBy { filament ->
                (filament.type ?: "").lowercase()
                    .replace(Regex("filament.*spool|filament|spool|refill"), "")
                    .replace(Regex("[()\\-/_]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            
            Log.d("BambuSync", "Detected ${typeGroups.size} groups: ${typeGroups.keys.joinToString(", ")}")

            // 3. Determine how to gather swatches
            if (typeGroups.size > 1) {
                // Multi-material page (e.g. TPU 85A/90A)
                // Load specific pages for EACH material type to get correct swatches for each.
                for ((groupName, filaments) in typeGroups) {
                    val representative = filaments.firstOrNull() ?: continue
                    val url = representative.typeLink ?: continue
                    val domain = initialUrl.substringBefore("/products/")
                    val fullUrl = when {
                        url.startsWith("http") -> url
                        url.startsWith("/") -> "$domain$url"
                        else -> {
                            val base = initialUrl.substringBefore("?")
                            if (url.startsWith("?")) "$base$url" else "$base?id=$url"
                        }
                    }
                    val groupUrlWithEn = fullUrl + (if (fullUrl.contains("?")) "&ls=en" else "?ls=en")
                    Log.d("BambuSync", "Loading material group page ($groupName): $groupUrlWithEn")
                    val groupHtml = withTimeoutOrNull(30000) { wv.loadUrlAndWait(groupUrlWithEn) }
                    if (groupHtml != null) {
                        allColorThumbnails.putAll(parseColorThumbnails(groupHtml))
                    }
                }
            } else {
                // Single material page (e.g. PLA Basic)
                // The initial page already has everything we need.
                allColorThumbnails.putAll(parseColorThumbnails(currentHtml))
            }

            // 5. Final processing: Match all filaments to the gathered swatches
            val currentUA = wv.settings.userAgentString
            val processed = withContext(Dispatchers.Default) {
                fullParsedBatch.map { filament ->
                    val targetColor = (filament.colorName ?: "").trim()
                    var thumbUrl = allColorThumbnails.entries.find { it.key.trim().equals(targetColor, ignoreCase = true) }?.value
                    
                    if (thumbUrl == null) {
                        // Robust Token Matching
                        val targetTokens = targetColor.lowercase().split(Regex("[\\s\\-/_(),.]+")).filter { it.length > 1 }.toSet()
                        thumbUrl = allColorThumbnails.entries.map { entry ->
                            val swatchTokens = entry.key.lowercase().split(Regex("[\\s\\-/_(),.]+")).filter { it.length > 1 }.toSet()
                            entry.value to targetTokens.intersect(swatchTokens).size
                        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
                    }

                    if (thumbUrl != null) {
                        val colorInt = getPixelFromUrl(context, thumbUrl, initialUrl, currentUA)
                        filament.copy(colorRgb = colorInt ?: 0xFF888888.toInt())
                    } else {
                        val fallbackColor = when(targetColor.lowercase()) {
                            "white" -> 0xFFFFFFFF.toInt()
                            "black" -> 0xFF1A1A1A.toInt()
                            "red" -> 0xFFE63946.toInt()
                            "blue" -> 0xFF457B9D.toInt()
                            else -> null
                        }
                        filament.copy(colorRgb = fallbackColor ?: 0xFF888888.toInt())
                    }
                }
            }

            processed.forEach { finalFilamentsMap[it.sku ?: "UNK_${it.variantName}"] = it }
            val results = finalFilamentsMap.values.toList()
            return@withContext SyncResult(results, "Success", "Synced ${results.size} variants", results.size, 0, false)

        } catch (e: Exception) {
            Log.e("BambuSync", "Scrape failed", e)
            return@withContext SyncResult(emptyList(), "Error", e.message ?: "Unknown", 0, 1, true)
        }
    }

    fun cleanup() {
        mainHandler.post { webView?.destroy(); webView = null }
    }

    private suspend fun WebView.loadUrlAndWait(url: String): String? = suspendCancellableCoroutine { cont ->
        var resumed = false
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mainHandler.postDelayed({
                    if (!resumed) {
                        resumed = true
                        view?.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { html ->
                            cont.resume(unescapeHtml(html ?: ""))
                        }
                    }
                }, 800)
            }
            override fun onReceivedError(view: WebView?, code: Int, desc: String?, fUrl: String?) {
                if (!resumed) { resumed = true; cont.resume(null) }
            }
        }
        loadUrl(url)
        mainHandler.postDelayed({ if (!resumed) { resumed = true; cont.resume(null) } }, 45000)
    }

    private suspend fun WebView.evaluateJavascriptSync(script: String): String? = suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { result -> cont.resume(result) }
    }

    private fun isEnglish(html: String) = html.contains("lang=\"en\"", true) || 
                                          html.contains("Add to cart", true) || 
                                          html.contains("Shipping policy", true) || 
                                          html.contains("Description", true)

    private fun unescapeHtml(html: String): String {
        return html.replace("\\u003C", "<").replace("\\u003E", ">").replace("\\u0026", "&")
            .replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&amp;", "&")
            .trim('"').trim()
    }

    private suspend fun getPixelFromUrl(context: Context, url: String, referer: String, ua: String): Int? = withContext(Dispatchers.IO) {
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", ua)
            connection.setRequestProperty("Referer", referer)
            connection.connect()
            val bitmap = android.graphics.BitmapFactory.decodeStream(connection.inputStream)
            val pixel = bitmap?.getPixel(bitmap.width / 2, bitmap.height / 2)
            bitmap?.recycle()
            pixel
        } catch (e: Exception) { null }
    }
}
