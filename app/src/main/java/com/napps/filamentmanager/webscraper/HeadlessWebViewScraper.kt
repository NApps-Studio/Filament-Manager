package com.napps.filamentmanager.webscraper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.napps.filamentmanager.database.VendorFilament
import com.napps.filamentmanager.util.SecuritySession
import com.napps.filamentmanager.webscraper.unescapeHtml
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.milliseconds

/**
 * A headless synchronization engine that uses an Android [WebView] to navigate and parse
 * modern, dynamic filament vendor websites.
 *
 * This component handles JavaScript-heavy pages, Cloudflare challenges, and
 * localization issues by simulating a real browser environment.
 */
class HeadlessWebViewSync(private val context: Context) {

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())

    fun cleanup() {
        handler.post {
            webView?.destroy()
            webView = null
        }
    }

    /**
     * Synchronizes a list of product links for filament variant details.
     *
     * @param links List of URLs to individual product pages.
     * @param vendor The vendor profile (e.g., BambuWeb Lab).
     * @param onProgress Callback to report sync progress.
     * @return A list of [VendorFilament] objects containing the synced data.
     * @throws CloudflareChallengeException if the site blocks the sync with a challenge.
     */
    suspend fun sync(
        links: List<ProductLink>, 
        vendor: StartPagesOfVendors,
        onProgress: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> }
    ): List<VendorFilament> = withContext(Dispatchers.Main) {
        val finalFilaments = mutableListOf<VendorFilament>()
        val colorCache = mutableMapOf<String, Int>()
        val totalPages = links.size
        var filamentsSuccess = 0
        var filamentsFailed = 0

        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            val currentUA = settings.userAgentString
            settings.userAgentString = "$currentUA Language/en-US"
        }
        webView = wv

        try {
            // Step 2: Sync each product page
            for ((index, productLink) in links.withIndex()) {
                val pagesDone = index + 1
                val link = productLink.url
                Log.d("BAMBU", "Processing page $pagesDone/$totalPages: $link")
                
                val htmlRaw = loadPageAndGetHtml(wv, link)
                val html = unescapeHtml(htmlRaw ?: "")

                if (html.isEmpty()) {
                    Log.e("BAMBU", "HTML is empty for $link")
                    filamentsFailed += productLink.expectedCount
                    onProgress(pagesDone, totalPages, filamentsSuccess, filamentsFailed)
                    continue
                }

                val english = isEnglish(html)
                Log.d("BAMBU", "Page language is English: $english")

                if (isCloudflareChallenge(html)) {
                    Log.e("BAMBU", "Cloudflare detected on $link")
                    throw CloudflareChallengeException("Cloudflare challenge detected")
                }

                // Per-page language check
                if (!english) {
                    Log.w("BAMBU", "Skipping page (not English): $link")
                    filamentsFailed += productLink.expectedCount
                    onProgress(pagesDone, totalPages, filamentsSuccess, filamentsFailed)
                    continue
                }

                var attempt = 0
                var syncedFilaments: List<VendorFilament>? = emptyList()

                while (attempt < 7 && syncedFilaments.isNullOrEmpty()) {
                    delay(750.milliseconds)
                    
                    // Optimization: Instead of fetching the whole HTML, try to get the JSON-LD directly first
                    val jsonLdRaw = wv.evaluateJavascriptSync("document.getElementById('product-jsonld')?.innerHTML")
                    val jsonLd = unescapeHtml(jsonLdRaw ?: "").trim().removeSurrounding("\"")
                    
                    if (jsonLd.isNotEmpty() && jsonLd != "null") {
                        Log.d("BAMBU", "Found JSON-LD directly via JS (${jsonLd.length} chars)")
                        syncedFilaments = parseBambulabProductPage(jsonLd, isJsonOnly = true)
                    }

                    // Fallback to full HTML if direct JS failed or we need it for thumbnails
                    if (syncedFilaments.isNullOrEmpty()) {
                        val currentRawHtml = wv.evaluateJavascriptSync("(function() { return document.documentElement.outerHTML; })();")
                        val currentHtml = unescapeHtml(currentRawHtml ?: "")
                        syncedFilaments = parseBambulabProductPage(currentHtml)
                    }

                    attempt++
                    
                    if (!syncedFilaments.isNullOrEmpty()) {
                        // We still need the full HTML for thumbnails as they aren't in the JSON-LD
                        val currentRawHtml = wv.evaluateJavascriptSync("(function() { return document.documentElement.outerHTML; })();")
                        val currentHtml = unescapeHtml(currentRawHtml ?: "")
                        val colorMap = parseColorThumbnails(currentHtml)
                        
                        val updatedFilaments = syncedFilaments.map { filament ->
                            val thumbUrl = colorMap[filament.colorName]
                            if (thumbUrl != null) {
                                val cachedColor = colorCache[thumbUrl]
                                val colorInt = cachedColor ?: getPixelFromUrl(context, thumbUrl)
                                if (cachedColor == null) colorCache[thumbUrl] = colorInt
                                filament.copy(colorRgb = colorInt)
                            } else {
                                filament
                            }
                        }
                        Log.d("BAMBU", "Successfully parsed ${updatedFilaments.size} variants from $link")
                        finalFilaments.addAll(updatedFilaments)
                        filamentsSuccess += updatedFilaments.size
                        break
                    }
                    
                    if (attempt == 7) {
                        Log.e("BAMBU", "Failed to parse $link. JSON-LD found: ${jsonLd.take(100)}...")
                    }
                }
                
                if (syncedFilaments.isNullOrEmpty()) {
                    filamentsFailed += productLink.expectedCount
                }
                onProgress(pagesDone, totalPages, filamentsSuccess, filamentsFailed)
            }
        } finally {
            wv.destroy()
            webView = null
        }
        finalFilaments
    }

    /**
     * Loads a single page and returns its full HTML source.
     * For start pages, it attempts multiple retries to ensure dynamic content grid
     * has finished rendering.
     */
    suspend fun loadPageAndGetHtml(url: String): String? = withContext(Dispatchers.Main) {
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " Language/en-US"
        }
        
        var finalHtml: String? = null
        val region = SecuritySession.getRegion()
        val startLink = StartPagesOfVendors.BAMBU_LAB.getLink(region)
        val isStartPage = url == startLink

        // Initial load
        val firstHtmlRaw = loadPageAndGetHtml(wv, url)
        var cleanHtml = unescapeHtml(firstHtmlRaw ?: "")
        
        if (!isEnglish(cleanHtml)) {
            throw LanguageMismatchException("The store page is not in English. Please try again.")
        }

        // If it's the start page, we wait and retry a few times to let the grid render
        if (isStartPage) {
            var attempts = 0
            while (attempts < 5) {
                // Check if the product list is actually there
                val links = parseBambulabStartPage(cleanHtml, url, StartPagesOfVendors.BAMBU_LAB.testHtmlClass)
                if (!links.isNullOrEmpty()) {
                    finalHtml = cleanHtml
                    break
                }
                
                delay(1000.milliseconds)
                val nextHtmlRaw = wv.evaluateJavascriptSync("(function() { return document.documentElement.outerHTML; })();")
                cleanHtml = unescapeHtml(nextHtmlRaw ?: "")
                attempts++
            }
        } else {
            // For other pages, just return the first result
            finalHtml = cleanHtml
        }

        wv.destroy()
        finalHtml
    }

    /**
     * Helper method to perform a single page load with custom headers.
     * Attempts to force English language by detecting localized keywords in the HTML.
     */
    /**
     * Internal helper to load a URL and wait for its HTML content.
     * 
     * Handles:
     * - English language forcing by detecting localized keywords and reloading with `?lang=en-US`.
     * - Custom `Accept-Language` headers.
     * - Resuming the coroutine once the page is fully rendered and Javascript is evaluated.
     */
    private suspend fun loadPageAndGetHtml(wv: WebView, url: String): String? = suspendCancellableCoroutine { continuation ->
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { html ->
                    val cleanHtml = unescapeHtml(html ?: "")
                    if (!isEnglish(cleanHtml) && !url.isNullOrEmpty() && !url.contains("lang=en-US")) {
                        // Attempt to force English by appending lang param
                        val langUrl = if (url.contains("?")) "$url&lang=en-US" else "$url?lang=en-US"
                        view.loadUrl(langUrl)
                    } else {
                        if (continuation.isActive) continuation.resume(html)
                    }
                }
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
        val headers = mapOf("Accept-Language" to "en-US,en;q=0.9")
        wv.loadUrl(url, headers)
    }

    private fun isEnglish(html: String): Boolean {
        // We only look at the first 1000 characters to find the <html> tag
        val header = html.take(1000).lowercase()
        
        // Flexible check: Does the string contain <html and does it have lang="en" or lang='en'
        // This works even if there are classes or other attributes before/after 'lang'
        val hasHtmlTag = header.contains("<html")
        val hasEnglishLang = header.contains("lang=\"en\"") || header.contains("lang='en'") || header.contains("lang=en")
        
        if (hasHtmlTag && hasEnglishLang) {
            return true
        }

        // Fallback: Check for common English keywords that would be localized on other pages
        val indicators = listOf("Cart", "Account", "Accessories", "Filament")
        return indicators.any { html.contains(it, ignoreCase = false) }
    }

    private suspend fun WebView.evaluateJavascriptSync(script: String): String? = suspendCoroutine { continuation ->
        evaluateJavascript(script) { result ->
            continuation.resume(result)
        }
    }

    /**
     * Checks if the provided HTML content indicates a Cloudflare challenge page.
     * Automated sync cannot bypass these challenges without user intervention.
     */
    private fun isCloudflareChallenge(html: String): Boolean {
        return html.contains("cf-challenge") || html.contains("ray ID") || html.contains("Checking your browser")
    }


    class CloudflareChallengeException(message: String) : Exception(message)
    class LanguageMismatchException(message: String) : Exception(message)
}
