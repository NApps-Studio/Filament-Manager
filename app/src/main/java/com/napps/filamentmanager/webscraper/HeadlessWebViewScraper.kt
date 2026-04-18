package com.napps.filamentmanager.webscraper

import android.content.Context
import android.os.Handler
import android.os.Looper
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
     * @param vendor The vendor profile (e.g., Bambu Lab).
     * @param onProgress Callback to report sync progress.
     * @return A list of [VendorFilament] objects containing the synced data.
     * @throws CloudflareChallengeException if the site blocks the sync with a challenge.
     */
    suspend fun sync(
        links: List<String>, 
        vendor: StartPagesOfVendors,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<VendorFilament> = withContext(Dispatchers.Main) {
        val finalFilaments = mutableListOf<VendorFilament>()
        val colorCache = mutableMapOf<String, Int>()
        val region = SecuritySession.getRegion()
        val baseLink = vendor.getLink(region)

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
            for ((index, link) in links.withIndex()) {
                onProgress(index + 1, links.size)
                val html = loadPageAndGetHtml(wv, link) ?: continue
                if (isCloudflareChallenge(html)) {
                    throw CloudflareChallengeException("Cloudflare challenge detected")
                }

                var attempt = 0
                var syncedFilaments: List<VendorFilament>? = emptyList()

                while (attempt < 7 && syncedFilaments.isNullOrEmpty()) {
                    delay(750.milliseconds)
                    val currentRawHtml = wv.evaluateJavascriptSync("(function() { return document.documentElement.outerHTML; })();")
                    val currentHtml = unescapeHtml(currentRawHtml ?: "")
                    syncedFilaments = parseBambulabProductPage(currentHtml)
                    attempt++
                    
                    if (!syncedFilaments.isNullOrEmpty()) {
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
                        finalFilaments.addAll(updatedFilaments)
                        break
                    }
                }
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
        // Check for lang attribute in <html> tag
        if (html.contains("lang=\"en\"", ignoreCase = true) || html.contains("lang='en'", ignoreCase = true)) {
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
}
