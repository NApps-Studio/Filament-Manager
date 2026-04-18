package com.napps.filamentmanager.webscraper

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.napps.filamentmanager.database.SyncRegion
import com.napps.filamentmanager.database.VendorFilament
import kotlinx.coroutines.*

private const val TAG = "bambuCart"

private fun getBaseStoreUrl(region: SyncRegion): String {
    return when (region) {
        SyncRegion.EU -> "https://eu.store.bambulab.com"
        SyncRegion.USA -> "https://us.store.bambulab.com"
        SyncRegion.ASIA -> "https://store.bambulab.com"
        SyncRegion.GLOBAL -> "https://store.bambulab.com"
    }
}

/**
 * Orchestrates the "Add to Cart" automation for the Bambu Lab store.
 * Uses a hidden or visible WebView to navigate through direct variant URLs and execute 
 * JavaScript interactions to simulate clicks on the "Add to Cart" button.
 * 
 * Logic highlights:
 * 1. Targeted Cookie Clearing: Only clears cart and session cookies to avoid breaking 
 *    the global Bambu Lab login while ensuring a fresh shopping cart.
 * 2. Variant Routing: Appends `?id=[SKU]` to base product links to bypass manual 
 *    selection of color/material.
 * 3. Fallback Selectors: Tries multiple CSS selectors for the "Add to Cart" button 
 *    to handle store UI variations across regions.
 * 4. External Handoff: Once automation completes, provides an option to open the 
 *    system browser with matching session tokens.
 *
 * @param cartFilaments List of vendor filament records to be added.
 * @param region The store region (EU, USA, etc.) determining the base URL.
 * @param onDismiss Callback to close the dialog.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebCartDialog(
    cartFilaments: List<VendorFilament>,
    region: SyncRegion,
    onDismiss: () -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf("Initializing...") }
    var isAutomationFinished by remember { mutableStateOf(false) }
    var itemsAddedCount by remember { mutableIntStateOf(0) }
    
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var pageLoadSignal by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    val context = LocalContext.current

    val baseStoreUrl = remember(region) { getBaseStoreUrl(region) }

    LaunchedEffect(webViewInstance) {
        val webView = webViewInstance ?: return@LaunchedEffect
        Log.d(TAG, "Automation Started. Region: $region, Total items to add: ${cartFilaments.size}")

        // 1. Identify and clear ONLY cart-related cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        
        val targetDomains = listOf(baseStoreUrl, "https://store.bambulab.com")
        for (url in targetDomains.distinct()) {
            val allCookies = cookieManager.getCookie(url)
            if (allCookies != null) {
                val cookieNames = allCookies.split(";").map { it.split("=").first().trim() }
                val cartCookies = cookieNames.filter { name ->
                    name.contains("cart", ignoreCase = true) || 
                    name.startsWith("bbl_store", ignoreCase = true) ||
                    name.contains("checkout", ignoreCase = true)
                }

                cartCookies.forEach { name ->
                    val baseSpec = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
                    val possiblePaths = listOf("/", "/cart")
                    val domainMatch = Uri.parse(url).host
                    val possibleDomains = listOf(null, domainMatch, ".bambulab.com")

                    possiblePaths.forEach { path ->
                        possibleDomains.forEach { domain ->
                            var cookieStr = "$baseSpec; Path=$path"
                            if (domain != null) cookieStr += "; Domain=$domain"
                            cookieManager.setCookie(url, cookieStr)
                        }
                    }
                }
            }
            cookieManager.flush()
        }

        delay(500)

        for (i in cartFilaments.indices) {
            currentIndex = i
            val filament = cartFilaments[i]
            val baseLink = (filament.typeLink ?: "").trim()
            val sku = (filament.sku ?: "").trim()
            val cleanBase = baseLink.removeSuffix("/")
            
            val targetUrl = if (sku.isNotEmpty()) {
                if (cleanBase.contains("?")) "$cleanBase&id=$sku" else "$cleanBase?id=$sku"
            } else {
                cleanBase
            }

            statusMessage = "Navigating to item ${i + 1}..."
            val deferred = CompletableDeferred<Unit>()
            pageLoadSignal = deferred
            
            withContext(Dispatchers.Main) {
                webView.loadUrl(targetUrl)
            }
            
            try {
                withTimeout(25000) { deferred.await() }
            } catch (e: Exception) {
                Log.e(TAG, "Page load timeout for: $targetUrl")
            }
            pageLoadSignal = null

            delay(1000)
            statusMessage = "Checking availability..."
            
            // Fixed JS: Explicitly check for button existence and visibility before any interaction
            val clickScript = """
                (function() {
                    const selectors = [
                        'button[name="add"]',
                        'button[data-action="add-to-cart"]',
                        '.add-to-cart',
                        '[data-add-to-cart]',
                        'button[type="submit"].ad-to-cart-button',
                        '.product-form__submit',
                        '#AddToCart'
                    ];
                    let btn = null;
                    for (const s of selectors) {
                        const element = document.querySelector(s);
                        if (element && (element.offsetWidth > 0 || element.offsetHeight > 0)) {
                            btn = element;
                            break;
                        }
                    }
                    
                    if (!btn) return "SKIP_NOT_FOUND";
                    
                    if (btn.disabled || btn.classList.contains('disabled') || btn.innerText.toLowerCase().includes('out of stock')) {
                        return "SKIP_OUT_OF_STOCK";
                    }
                    
                    try {
                        btn.click();
                        return "SUCCESS";
                    } catch (e) {
                        return "ERROR";
                    }
                })();
            """.trimIndent()
            
            val result = suspendCancellableCoroutine<String> { cont ->
                webView.post {
                    webView.evaluateJavascript(clickScript) { jsResult ->
                        cont.resume(jsResult ?: "null") { }
                    }
                }
            }
            
            if (result.contains("SUCCESS")) {
                statusMessage = "Item added!"
                itemsAddedCount++
            } else {
                statusMessage = "Skipping item..."
            }
            delay(1000)
        }

        statusMessage = "Opening cart..."
        val cartUrl = "$baseStoreUrl/cart"
        withContext(Dispatchers.Main) {
            webView.loadUrl(cartUrl)
        }
        
        isAutomationFinished = true
        statusMessage = "Done! Added $itemsAddedCount items."
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = settings.userAgentString.replace("wv", "")
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    pageLoadSignal?.complete(Unit)
                                }
                            }
                            webViewInstance = this
                        }
                    }
                )

                Column(modifier = Modifier.padding(16.dp)) {
                    val progressText = if (isAutomationFinished) "Complete" 
                                      else "Item ${ (currentIndex + 1).coerceAtMost(cartFilaments.size) } of ${cartFilaments.size}"
                    
                    Text(text = progressText, style = MaterialTheme.typography.titleMedium)
                    Text(text = statusMessage, style = MaterialTheme.typography.bodySmall)
                    
                    if (!isAutomationFinished) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (cartFilaments.isNotEmpty()) (currentIndex.toFloat() / cartFilaments.size.toFloat()) else 1f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isAutomationFinished) {
                        Button(
                            onClick = {
                                val cartUrl = "$baseStoreUrl/cart"
                                val cookieManager = CookieManager.getInstance()
                                val cookies = cookieManager.getCookie(cartUrl)
                                val cookieMap = cookies?.split(";")?.associate {
                                    val parts = it.trim().split("=")
                                    parts[0] to parts.getOrElse(1) { "" }
                                } ?: emptyMap()

                                val cartToken = cookieMap["cart"]
                                val bblStoreGid = cookieMap["bbl_store_gid"]

                                val targetUri = Uri.parse(cartUrl).buildUpon()
                                if (!cartToken.isNullOrBlank()) targetUri.appendQueryParameter("cart", cartToken)
                                if (!bblStoreGid.isNullOrBlank()) targetUri.appendQueryParameter("bbl_store_gid", bblStoreGid)

                                context.startActivity(Intent(Intent.ACTION_VIEW, targetUri.build()))
                            },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Text("Open in Browser")
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(if (isAutomationFinished) "Close" else "Cancel")
                    }
                }
            }
        }
    }
}
