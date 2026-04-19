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
import com.napps.filamentmanager.database.SyncReport

data class SyncResult(
    val filaments: List<VendorFilament>,
    val summary: String,
    val details: String,
    val affectedVariants: Int,
    val errorCount: Int,
    val isError: Boolean,
    val syncType: String = "Full Sync"
)

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
        syncType: String = "Full Sync",
        onProgress: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> }
    ): SyncResult = withContext(Dispatchers.Main) {
        val finalFilaments = mutableListOf<VendorFilament>()
        val errors = mutableListOf<String>()
        val totalPages = links.size
        var filamentsSuccess = 0
        var pageFailures = 0

        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            val currentUA = settings.userAgentString
            settings.userAgentString = "$currentUA Language/en-US"
        }
        webView = wv

        try {
            for ((index, productLink) in links.withIndex()) {
                val pagesDone = index + 1
                val link = productLink.url
                
                var success = false
                var attempts = 0
                val maxAttempts = 3
                
                while (attempts < maxAttempts && !success) {
                    attempts++
                    val htmlRaw = loadPageAndGetHtml(wv, link)
                    val html = unescapeHtml(htmlRaw ?: "")

                    if (html.isEmpty()) {
                        if (attempts < maxAttempts) {
                            delay(2000)
                            continue
                        } else {
                            pageFailures++
                            errors.add("Failed to load page: $link")
                            break
                        }
                    }

                    if (isCloudflareChallenge(html)) {
                        if (attempts < maxAttempts) {
                            Log.d("Sync", "Cloudflare detected, waiting and retrying... ($attempts/$maxAttempts)")
                            delay(5000) // Wait for potential JS challenge to resolve
                            continue
                        } else {
                            throw CloudflareChallengeException("Cloudflare challenge detected and could not be bypassed after $maxAttempts attempts.")
                        }
                    }

                    if (!isEnglish(html)) {
                        if (attempts < maxAttempts) {
                            Log.d("Sync", "Language mismatch detected, retrying... ($attempts/$maxAttempts)")
                            delay(2000)
                            continue
                        } else {
                            pageFailures++
                            errors.add("Language mismatch (not English) on: $link")
                            break
                        }
                    }

                    var syncedFilaments: List<VendorFilament>? = emptyList()
                    // Try parsing multiple times if needed, as content might be dynamic
                    for (parseAttempt in 1..5) {
                        delay(750.milliseconds)
                        val jsonLdRaw = wv.evaluateJavascriptSync("document.getElementById('product-jsonld')?.innerHTML")
                        val jsonLd = unescapeHtml(jsonLdRaw ?: "").trim().removeSurrounding("\"")
                        
                        if (jsonLd.isNotEmpty() && jsonLd != "null") {
                            syncedFilaments = parseBambulabProductPage(jsonLd, isJsonOnly = true)
                        }

                        if (syncedFilaments.isNullOrEmpty()) {
                            val currentRawHtml = wv.evaluateJavascriptSync("(function() { return document.documentElement.outerHTML; })();")
                            val currentHtml = unescapeHtml(currentRawHtml ?: "")
                            syncedFilaments = parseBambulabProductPage(currentHtml)
                        }

                        if (!syncedFilaments.isNullOrEmpty()) {
                            finalFilaments.addAll(syncedFilaments)
                            filamentsSuccess += syncedFilaments.size
                            success = true
                            break
                        }
                    }
                    
                    if (syncedFilaments.isNullOrEmpty() && attempts == maxAttempts) {
                        pageFailures++
                        errors.add("Failed to parse variants from: $link")
                    }
                }
                onProgress(pagesDone, totalPages, filamentsSuccess, pageFailures)
            }
        } catch (e: CloudflareChallengeException) {
            errors.add(e.message ?: "Sync blocked by Cloudflare")
        } finally {
            wv.destroy()
            webView = null
        }

        val isError = errors.isNotEmpty()
        val summary = if (isError) {
            "Sync completed with ${errors.size} issues"
        } else {
            "Successfully synced $filamentsSuccess variants"
        }
        
        SyncResult(
            filaments = finalFilaments,
            summary = summary,
            details = errors.joinToString("\n"),
            affectedVariants = filamentsSuccess,
            errorCount = errors.size,
            isError = isError,
            syncType = syncType
        )
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
        var currentHtmlRaw = loadPageAndGetHtml(wv, url)
        var cleanHtml = unescapeHtml(currentHtmlRaw ?: "")
        
        // Retry loop for English/Cloudflare on initial load
        var loadAttempts = 0
        while (loadAttempts < 3 && (cleanHtml.isEmpty() || !isEnglish(cleanHtml) || isCloudflareChallenge(cleanHtml))) {
            loadAttempts++
            if (isCloudflareChallenge(cleanHtml)) {
                delay(5000)
            } else {
                delay(2000)
            }
            currentHtmlRaw = if (!isEnglish(cleanHtml) && !url.contains("lang=en-US")) {
                val langUrl = if (url.contains("?")) "$url&lang=en-US" else "$url?lang=en-US"
                loadPageAndGetHtml(wv, langUrl)
            } else {
                wv.evaluateJavascriptSync("(function() { return document.documentElement.outerHTML; })();")
            }
            cleanHtml = unescapeHtml(currentHtmlRaw ?: "")
        }

        // If it's the start page, we wait and retry a few times to let the grid render
        if (isStartPage) {
            var gridAttempts = 0
            while (gridAttempts < 5) {
                // Check if the product list is actually there
                val links = parseBambulabStartPage(cleanHtml, url, StartPagesOfVendors.BAMBU_LAB.testHtmlClass)
                if (!links.isNullOrEmpty()) {
                    finalHtml = cleanHtml
                    break
                }
                
                delay(1000.milliseconds)
                val nextHtmlRaw = wv.evaluateJavascriptSync("(function() { return document.documentElement.outerHTML; })();")
                cleanHtml = unescapeHtml(nextHtmlRaw ?: "")
                gridAttempts++
            }
        } else {
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
        // We look at more characters to find the <html> tag and lang attribute
        val header = html.take(2000).lowercase()
        
        val hasHtmlTag = header.contains("<html")
        // Flexible check: Match lang="en", lang="en-US", lang='en', etc.
        val hasEnglishLang = header.contains("lang=\"en") || header.contains("lang='en") || header.contains("lang=en")
        
        if (hasHtmlTag && hasEnglishLang) {
            return true
        }

        // Fallback: Check for common English keywords that would be localized on other pages
        val indicators = listOf("Cart", "Account", "Accessories", "Filament", "Support", "Store")
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
