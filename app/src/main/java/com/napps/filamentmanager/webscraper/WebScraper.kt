/**
 * Container for sync-related utility functions and definitions.
 * Provides parsing logic for Bambu Lab's online store and image processing
 * to extract filament colors.
 */
package com.napps.filamentmanager.webscraper

import android.content.Context
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.SvgDecoder
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.napps.filamentmanager.database.SyncRegion
import com.napps.filamentmanager.database.VendorFilament
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Defines the main store URLs for different regions.
 * Used by the sync engine to navigate to the correct localized store.
 */
enum class StartPagesOfVendors(val vendor: String, val testHtmlClass: String) {
    BAMBU_LAB("Bambu lab", "ProductItem__Wrapper");

    fun getLink(region: SyncRegion): String {
        return when (region) {
            SyncRegion.EU -> "https://eu.store.bambulab.com/collections/bambu-lab-3d-printer-filament?By+Material=All"
            SyncRegion.USA -> "https://us.store.bambulab.com/collections/bambu-lab-3d-printer-filament?By+Material=All"
            SyncRegion.ASIA -> "https://store.bambulab.com/collections/bambu-lab-3d-printer-filament?By+Material=All"
            SyncRegion.GLOBAL -> "https://store.bambulab.com/collections/bambu-lab-3d-printer-filament?By+Material=All"
        }
    }
}

data class ProductLink(val url: String, val expectedCount: Int = 0, val typeName: String = "", val category: String? = null)

/**
 * Parses the Bambu Lab store collection page to find all individual product links.
 * 
 * @param html The HTML content of the collection page.
 * @param baseUrl The base URL for resolving relative links.
 * @param testClassName A CSS class name used to identify product grid items.
 * @return A list of [ProductLink] objects to product detail pages.
 */
fun parseBambulabStartPage(html: String, baseUrl: String, testClassName: String): List<ProductLink>? {
    val document: Document = Jsoup.parse(html, baseUrl)
    val filamentList = mutableListOf<ProductLink>()

    // Use the class name as a starting point, but be flexible
    val productElements = document.getElementsByClass(testClassName)

    if (productElements.isEmpty()){
        android.util.Log.w("BambuWebScraper", "No elements found with class '$testClassName'. Trying fallback...")
        // Fallback: try to find any links containing "/products/" if the wrapper class failed
        val allLinks = document.select("a[href*=/products/]")
        for (link in allLinks) {
            val url = link.absUrl("href")
            if (!url.contains("google.com") && !url.contains("facebook.com")) {
                // Extract name from URL: .../products/pla-basic -> PLA Basic
                val nameFromUrl = url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }
                
                // Skip bundles and kits
                val lowerName = nameFromUrl.lowercase()
                if (lowerName.contains("bundle") || lowerName.contains("pack") || lowerName.contains("kit") || lowerName.contains("lithophane")) {
                    android.util.Log.d("BambuWebScraper", "Skipping bundle/kit in fallback: $nameFromUrl")
                    continue
                }

                // In fallback mode, we assume 1 color per link if we can't find the count
                filamentList.add(ProductLink(url, 1, nameFromUrl))
            }
        }
    } else {
        android.util.Log.d("BambuWebScraper", "Found ${productElements.size} elements with class '$testClassName'")
        for (productElement in productElements) {
            val linkElement = productElement.select("a[href]").first()
            if (linkElement != null) {
                val url = linkElement.absUrl("href")
                
                // Try to find "X colors available" text
                val colorText = productElement.text()
                val match = Regex("(\\d+)\\s+colors?").find(colorText)
                val count = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                // Get the product title
                val titleElement = productElement.select(".ProductItem__Title, .product-item__title, h2, h3").first()
                val title = titleElement?.text() ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }
                
                // Skip bundles and kits that don't represent individual filament types
                val lowerTitle = title.lowercase()
                if (lowerTitle.contains("bundle") || lowerTitle.contains("pack") || lowerTitle.contains("kit") || lowerTitle.contains("lithophane")) {
                    android.util.Log.d("BambuWebScraper", "Skipping bundle/kit: $title")
                    continue
                }

                filamentList.add(ProductLink(url, count, title))
            }
        }
    }

    return if (filamentList.isEmpty()) null else filamentList.distinctBy { it.url }
}

/**
 * Parses a single product detail page to extract all filament variants.
 * 
 * Extracts data from the `product-jsonld` script tag which contains structured
 * JSON data for all variants (different colors, package types, etc.).
 * 
 * @param html The HTML content of the product page.
 * @return A list of [VendorFilament] entities found on the page.
 */
fun parseBambulabProductPage(html: String, isJsonOnly: Boolean = false): List<VendorFilament>? {
    val filamentList = mutableListOf<VendorFilament>()

    val jsonString = try {
        val extracted = if (isJsonOnly) {
            html
        } else {
            val scriptTagId = "id=\"product-jsonld\""
            if (!html.contains(scriptTagId)) return null

            html.substringAfter(scriptTagId)
                .substringAfter(">")
                .substringBefore("</script>")
        }
        
        // Find the first '{' and last '}' to strip any garbage or outer quotes from JS evaluateJavascript
        val start = extracted.indexOf('{')
        val end = extracted.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        
        val content = extracted.substring(start, end + 1)
        
        // Clean up common JS/HTML escapes that break JSON parsing
        content
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    } catch (e: Exception) {
        return null
    }

    try {
        val root = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            android.util.Log.e("BambuWebScraper", "JSON Parsing Error. Raw string: ${jsonString.take(100)}...")
            throw e
        }
        val brandName = root.optJSONObject("brand")?.optString("name") ?: "Bambu Lab"
        val variants = root.optJSONArray("hasVariant") ?: return null

        for (i in 0 until variants.length()) {
            try {
                val variant = variants.getJSONObject(i)
                val fullName = variant.getString("name")
                
                // Offers can be a single object or an array of objects
                val offersObj = variant.optJSONObject("offers")
                val offersArr = variant.optJSONArray("offers")
                
                val primaryOffer = offersObj ?: offersArr?.optJSONObject(0)

                // Split by "/" first to separate (Main Info), (Package), (Weight)
                val primaryParts = fullName.split("/").map { it.trim() }
                
                var type = ""
                var colorName = ""
                var packageType = "Unknown"
                var weight = ""

                // If color name still looks like a material (e.g., contains TPU, PLA, etc.)
                // and there was only one " - ", it might be that the name is "Material - Variant"
                // and the color is actually missing or in another part.
                val materials = listOf("TPU", "PLA", "PETG", "ABS", "ASA", "PC", "PA", "PET")

                // New Robust Name Parsing
                if (fullName.contains(" - ")) {
                    val beforeDash = fullName.substringBefore(" - ").trim()
                    val afterDashParts = fullName.substringAfter(" - ").split("/").map { it.trim() }
                    
                    if (beforeDash.contains("/")) {
                        // Case A: "TPU 85A / TPU 90A - TPU 85A / 1 kg / Light Cyan (51500)"
                        // Or "PLA Basic / PLA Matte - PLA Basic / 1 kg / White (11100)"
                        type = afterDashParts[0]
                        android.util.Log.d("BambuWebScraper", "Parsing multi-type name. Type: $type, Parts: $afterDashParts")
                        
                        // Smart identification of color vs weight
                        val p1 = afterDashParts.getOrNull(1) ?: ""
                        val p2 = afterDashParts.getOrNull(2) ?: ""
                        val p3 = afterDashParts.getOrNull(3) ?: ""
                        
                        // Weight regex to identify which part is the weight
                        val weightRegex = Regex("\\d+\\s*(kg|g|lb)", RegexOption.IGNORE_CASE)
                        
                        if (weightRegex.containsMatchIn(p1)) {
                            weight = p1
                            colorName = if (p2.isNotEmpty()) p2 else p3
                        } else if (weightRegex.containsMatchIn(p2)) {
                            weight = p2
                            colorName = p1
                        } else if (weightRegex.containsMatchIn(p3)) {
                            weight = p3
                            colorName = p1
                        } else {
                            // Default fallback
                            colorName = p1
                            weight = p2
                        }
                        
                        // If colorName still looks like a material or is empty, try to find the part that looks like a color
                        if (colorName.isEmpty() || materials.any { it.equals(colorName, ignoreCase = true) }) {
                             for (part in afterDashParts.drop(1)) {
                                 if (!weightRegex.containsMatchIn(part) && !materials.any { it.equals(part, ignoreCase = true) }) {
                                     colorName = part
                                     break
                                 }
                             }
                        }
                        
                        packageType = "Spool"
                        android.util.Log.d("BambuWebScraper", "Result -> Type: $type, Color: $colorName, Weight: $weight")
                    } else {
                        // Case B: "PLA Translucent - Teal (13612) / Filament with spool / 1 kg"
                        type = beforeDash
                        colorName = afterDashParts[0]
                        packageType = afterDashParts.getOrNull(1) ?: "Unknown"
                        weight = afterDashParts.getOrNull(2) ?: ""
                    }
                }
                // Case 2: "PLA Basic / Black / Refill / 1 kg"
                else if (primaryParts.size >= 3) {
                    type = primaryParts[0]
                    
                    // Smart identification for index 1 and 2
                    val p1 = primaryParts[1]
                    val p2 = primaryParts[2]
                    val p3 = primaryParts.getOrNull(3) ?: ""
                    
                    val p1IsPackage = p1.contains("refill", true) || p1.contains("spool", true)
                    val p2IsPackage = p2.contains("refill", true) || p2.contains("spool", true)
                    
                    if (p1IsPackage) {
                        packageType = p1
                        colorName = p2
                        weight = p3
                    } else if (p2IsPackage) {
                        packageType = p2
                        colorName = p1
                        weight = p3
                    } else {
                        colorName = p1
                        packageType = p2
                        weight = p3
                    }
                }
                // Case 3: "PLA Basic / Black"
                else if (primaryParts.size == 2) {
                    type = primaryParts[0]
                    colorName = primaryParts[1]
                }
                else {
                    type = primaryParts[0]
                    colorName = ""
                }

                // Normalize packageType
                val combinedPackageInfo = "$packageType ${if (primaryParts.size > 1) primaryParts[1] else ""}"
                packageType = when {
                    combinedPackageInfo.contains("refill", true) -> "Refill"
                    combinedPackageInfo.contains("spool", true) -> "Spool"
                    else -> packageType
                }

                if (type.isEmpty()) type = brandName

                val availabilityUrl = primaryOffer?.optString("availability", "") ?: ""
                val isAvailable = availabilityUrl.contains("InStock", ignoreCase = true)

                filamentList.add(
                    VendorFilament(
                        brand = brandName,
                        type = type,
                        colorName = colorName,
                        colorRgb = 0xFF888888.toInt(),
                        packageType = packageType,
                        weight = weight,
                        variantName = fullName,
                        sku = variant.optString("sku", "UNKNOWN"),
                        typeLink = primaryOffer?.optString("url", "") ?: "",
                        timestamp = System.currentTimeMillis(),
                        price = primaryOffer?.optDouble("price", 0.0) ?: 0.0,
                        isAvailable = isAvailable,
                        availableOnDate = null
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("BambuWebScraper", "Error parsing variant $i: ${e.message}")
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("BambuWebScraper", "Critical error parsing product JSON", e)
    }

    return if (filamentList.isEmpty()) null else filamentList
}

/**
 * Maps color names to their thumbnail image URLs from the product page.
 * This is used to later extract a representative RGB color from the thumbnail.
 */
fun parseColorThumbnails(html: String): Map<String, String> {
    val colorMap = mutableMapOf<String, String>()
    
    // Improved regex: Match opening tag, capture name attribute, and handle potential lack of content/closing tag (like inputs)
    val tagRegex = Regex("""<(li|div|button|span|a|label|input)[^>]*(?:value|data-value|title|aria-label|data-option-value|data-id|data-name|data-variant-title|data-variant-name)=["']([^"']+)["'][^>]*>(.*?)((?:</\1>)|$)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    
    val imgRegex = Regex("""<img[^>]*?(?:data-src|src|data-original|data-lazy-src)=["']([^"'\s]+)["']""", RegexOption.IGNORE_CASE)
    val styleImgRegex = Regex("""background-image:\s*url\(["']?([^"'\s)]+)["']?\)""", RegexOption.IGNORE_CASE)
    val dataImgRegex = Regex("""(?:data-image|data-img-src|data-bg|data-variant-image)=["']([^"'\s]+)["']""", RegexOption.IGNORE_CASE)

    tagRegex.findAll(html).forEach { match ->
        val name = unescapeHtml(match.groupValues[2].trim())
        val content = match.groupValues[3]
        val fullTagMatch = match.value
        
        if (name.isBlank() || name.contains("model_id") || name.startsWith("{") || name.length > 60) return@forEach

        // Try to find image URL in various places: data-attribute, content img, style bg
        var imageUrl = dataImgRegex.find(fullTagMatch)?.groupValues?.get(1)
            ?: imgRegex.find(content)?.groupValues?.get(1)
            ?: styleImgRegex.find(content)?.groupValues?.get(1)
            ?: dataImgRegex.find(content)?.groupValues?.get(1)
            ?: styleImgRegex.find(fullTagMatch)?.groupValues?.get(1)
            ?: imgRegex.find(fullTagMatch)?.groupValues?.get(1)
            
        if (imageUrl != null) {
            imageUrl = unescapeHtml(imageUrl)
            if (imageUrl.startsWith("//")) imageUrl = "https:$imageUrl"
            else if (imageUrl.startsWith("/")) imageUrl = "https://store.bambulab.com$imageUrl"
            
            // Adjust thumbnail sizes to 100x100 for better color sampling.
            if (imageUrl.contains("_small") || imageUrl.contains("_thumb") || imageUrl.contains(Regex("""_\d+x\d*"""))) {
                imageUrl = imageUrl.replace(Regex("""_(?:small|thumb|\d+x\d*)"""), "_100x100")
            }

            // Only add if we don't have it or if the new one looks more valid (not a placeholder)
            if (!colorMap.containsKey(name) || (!imageUrl.contains("placeholder") && colorMap[name]?.contains("placeholder") == true)) {
                colorMap[name] = imageUrl
            }
        }
    }
    
    // Fallback: look for ANY img with an alt text that matches a known color pattern
    val altImgRegex = Regex("""<img[^>]*?alt=["']([^"']+)["'][^>]*?(?:data-src|src|data-original|data-lazy-src)=["']([^"'\s]+)["']""", RegexOption.IGNORE_CASE)
    altImgRegex.findAll(html).forEach { match ->
        val alt = unescapeHtml(match.groupValues[1].trim())
        var url = match.groupValues[2]
        
        if (alt.length > 2 && alt.length < 50 && !alt.contains("product", true)) {
            if (url.startsWith("//")) url = "https:$url"
            else if (url.startsWith("/")) url = "https://store.bambulab.com$url"
            
            if (!colorMap.containsKey(alt) || !url.contains("placeholder")) {
                colorMap[alt] = url
            }
        }
    }
    
    if (colorMap.isNotEmpty()) {
        android.util.Log.d("BambuWebScraper", "Found ${colorMap.size} color swatches")
    }
    
    return colorMap
}

/**
 * Unescapes HTML entities and characters from a raw string, typically
 * from a `WebView.evaluateJavascript` result.
 */
fun unescapeHtml(rawHtml: String): String {
    return rawHtml
        .replace("\\u003C", "<")
        .replace("\\u003E", ">")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .trim('"')
}

private var sharedImageLoader: ImageLoader? = null

/**
 * Downloads a small thumbnail image and extracts the color of its center pixel.
 */
suspend fun getPixelFromUrl(context: Context, url: String, referer: String? = null, userAgent: String? = null): Int? = withContext(Dispatchers.IO) {
    val tryExtensions = if (url.contains(".media")) {
        listOf(url, url.replace(".media", ".png"))
    } else if (url.contains(".png")) {
        listOf(url, url.replace(".png", ".media"))
    } else {
        listOf(url)
    }

    for (currentUrl in tryExtensions) {
        try {
            val extension = currentUrl.substringAfterLast(".", "unknown").substringBefore("?").lowercase()

            if (sharedImageLoader == null) {
                val okHttpClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor { chain ->
                        val originalRequest = chain.request()
                        val url = originalRequest.url.toString()
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        val latestCookies = cookieManager.getCookie(url)
                        
                        val requestBuilder = originalRequest.newBuilder()
                        
                        // Critical headers to mimic a real browser image request
                        requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                        requestBuilder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                        requestBuilder.header("Accept-Language", "en-US,en;q=0.9")
                        requestBuilder.header("Sec-Fetch-Dest", "image")
                        requestBuilder.header("Sec-Fetch-Mode", "no-cors")
                        requestBuilder.header("Sec-Fetch-Site", "cross-site")
                        requestBuilder.header("Cache-Control", "no-cache")
                        requestBuilder.header("Pragma", "no-cache")
                        
                        if (latestCookies != null) {
                            requestBuilder.header("Cookie", latestCookies)
                        }

                        chain.proceed(requestBuilder.build())
                    }
                    .build()

                sharedImageLoader = ImageLoader.Builder(context)
                    .okHttpClient(okHttpClient)
                    .components {
                        add(SvgDecoder.Factory())
                        add(GifDecoder.Factory())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            add(coil.decode.ImageDecoderDecoder.Factory())
                        }
                    }
                    .crossfade(true)
                    .build()
            }

            val finalReferer = referer ?: "https://store.bambulab.com/"
            val finalUA = userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

            val request = ImageRequest.Builder(context)
                .data(currentUrl)
                .setHeader("User-Agent", finalUA)
                .setHeader("Referer", finalReferer)
                .setHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .allowHardware(false)
                .size(100, 100)
                .build()

            val result = sharedImageLoader!!.execute(request)
            
            if (result is SuccessResult) {
                // Check if we actually got an image or an HTML wrapper
                val drawable = result.drawable
                
                // If the "image" is extremely small or if we suspect it's HTML, we should double check.
                // Coil sometimes "successfully" decodes a tiny placeholder if it gets HTML.
                // But a better way is to use OkHttp directly if we want to check Content-Type.
                // For now, let's assume if it succeeded and has dimensions, it's the image.
                
                android.util.Log.d("BambuSync", "[$extension] SUCCESS. Link: $currentUrl")
                
                val w = drawable.intrinsicWidth.let { if (it <= 0) 100 else it }
                val h = drawable.intrinsicHeight.let { if (it <= 0) 100 else it }
                
                val bitmap = createBitmap(w, h)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)

                val centerX = bitmap.width / 2
                val centerY = bitmap.height / 2
                
                val samplePoints = listOf(
                    Pair(centerX, centerY),
                    Pair(centerX, centerY - 10),
                    Pair(centerX, centerY + 10),
                    Pair(centerX - 10, centerY),
                    Pair(centerX + 10, centerY)
                )
                
                val candidates = mutableListOf<Int>()
                for (point in samplePoints) {
                    val x = point.first.coerceIn(0, bitmap.width - 1)
                    val y = point.second.coerceIn(0, bitmap.height - 1)
                    val color = bitmap[x, y]
                    if (((color shr 24) and 0xFF) > 128) candidates.add(color)
                }
                
                val finalColor = if (candidates.isNotEmpty()) {
                    candidates.maxByOrNull { c ->
                        val hsv = FloatArray(3)
                        android.graphics.Color.colorToHSV(c, hsv)
                        hsv[1] * 5.0f + hsv[2] 
                    } ?: candidates[0]
                } else 0xFF888888.toInt()

                bitmap.recycle()
                return@withContext finalColor
            } else if (result is ErrorResult) {
                val msg = result.throwable.message ?: "Unknown Error"
                android.util.Log.e("BambuSync", "[$extension] FAILED: $msg. Link: $currentUrl")
                // Continue to the next extension in the loop
            }
        } catch (e: Exception) {
            val extension = currentUrl.substringAfterLast(".", "unknown").substringBefore("?")
            android.util.Log.e("BambuSync", "[$extension] ERROR: ${e.message}. Link: $currentUrl")
        }
    }
    null
}

// Helper to handle vector drawables which might not have intrinsic dimensions
private fun Int.mutableIntrinsicHeight(): Int = if (this <= 0) 100 else this
