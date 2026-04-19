/**
 * Container for sync-related utility functions and definitions.
 * Provides parsing logic for Bambu Lab's online store and image processing
 * to extract filament colors.
 */
package com.napps.filamentmanager.webscraper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import com.napps.filamentmanager.database.VendorFilament
import androidx.core.graphics.get
import androidx.core.graphics.createBitmap
import com.napps.filamentmanager.database.SyncRegion
import com.napps.filamentmanager.util.SecuritySession

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

data class ProductLink(val url: String, val expectedCount: Int)

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
        android.util.Log.w("WebScraper", "No elements found with class '$testClassName'. Trying fallback...")
        // Fallback: try to find any links containing "/products/" if the wrapper class failed
        val allLinks = document.select("a[href*=/products/]")
        for (link in allLinks) {
            val url = link.absUrl("href")
            if (!url.contains("google.com") && !url.contains("facebook.com")) {
                // In fallback mode, we assume 1 color per link if we can't find the count
                filamentList.add(ProductLink(url, 1))
            }
        }
    } else {
        android.util.Log.d("WebScraper", "Found ${productElements.size} elements with class '$testClassName'")
        for (productElement in productElements) {
            val linkElement = productElement.select("a[href]").first()
            if (linkElement != null) {
                val url = linkElement.absUrl("href")
                
                // Try to find "X colors available" text
                val colorText = productElement.text()
                val match = Regex("(\\d+)\\s+colors?").find(colorText)
                val count = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                filamentList.add(ProductLink(url, count))
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

    try {
        val jsonString = if (isJsonOnly) {
            html
        } else {
            // 1. Extract content between <script id="product-jsonld"...> and </script>
            val scriptTagId = "id=\"product-jsonld\""
            if (!html.contains(scriptTagId)) return null

            html.substringAfter(scriptTagId)
                .substringAfter(">")
                .substringBefore("</script>")
        }

        val root = JSONObject(jsonString)
        val brandName = root.getJSONObject("brand").getString("name")
        val variants = root.getJSONArray("hasVariant")

        for (i in 0 until variants.length()) {
            val variant = variants.getJSONObject(i)
            val fullName = variant.getString("name") // e.g. "PLA Basic - Jade White (10100) / Refill / 1kg"
            val offers = variant.getJSONObject("offers")

            // 2. Split Name: "Type - Color / Package / Weight"
            // Split by '/' first to get [Type-Color, Package, Weight]
            val parts = fullName.split("/")
            val typeAndColor = parts.getOrNull(0)?.trim() ?: ""
            val packageType = parts.getOrNull(1)?.trim() ?: "Unknown"
            val weight = parts.getOrNull(2)?.trim() ?: ""

            // Split Type and Color by '-'
            val type = typeAndColor.substringBefore("-").trim()
            val colorName = typeAndColor.substringAfter("-").trim()

            // 3. Parse Availability
            val availabilityUrl = offers.getString("availability")
            val isAvailable = availabilityUrl.contains("InStock", ignoreCase = true)

            filamentList.add(
                VendorFilament(
                    brand = brandName,
                    type = type,
                    colorName = colorName,
                    // Note: Center pixel color requires image downloading,
                    // usually better handled by a placeholder or separate utility.
                    colorRgb = 0xFFCCCCCC.toInt(),
                    packageType = packageType,
                    weight = weight,
                    variantName = fullName,
                    sku = variant.getString("sku"),
                    typeLink = offers.getString("url").substringBefore("?id="),
                    timestamp = System.currentTimeMillis(),
                    price = offers.getDouble("price"),
                    isAvailable = isAvailable,
                    availableOnDate = null
                )
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return filamentList
}

/**
 * Maps color names to their thumbnail image URLs from the product page.
 * This is used to later extract a representative RGB color from the thumbnail.
 */
fun parseColorThumbnails(html: String): Map<String, String> {
    val colorMap = mutableMapOf<String, String>()
    // Find the list items: <li value="Color Name (12345)" ... <img src="url"
    val liRegex = """<li value="([^"]+)"[^>]*>.*?<img src="([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL)

    liRegex.findAll(html).forEach { match ->
        val name = match.groupValues[1]
        val imageUrl = match.groupValues[2]
        colorMap[name] = imageUrl
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

/**
 * Downloads a small thumbnail image and extracts the color of its center pixel.
 * Uses the Coil image loading library with hardware acceleration disabled to 
 * allow direct pixel access.
 */
suspend fun getPixelFromUrl(context: Context, url: String): Int = withContext(Dispatchers.IO) {
    try {
        val loader = coil.ImageLoader(context)
        val request = coil.request.ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false) // Still critical for pixel access
            .build()

        val result = loader.execute(request)
        if (result is coil.request.SuccessResult) {
            val drawable = result.drawable

            // Create a bitmap that matches the drawable's size
            val bitmap = createBitmap(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1)
            )

            // Draw the image onto our readable bitmap
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)

            // Get the color from the middle
            val color = bitmap[bitmap.width / 2, bitmap.height / 2]

            // Clean up the bitmap to save memory
            bitmap.recycle()

            return@withContext color
        }
        0xFFCCCCCC.toInt() // Fallback
    } catch (e: Exception) {
        android.util.Log.e("Sync", "Error getting pixel for $url", e)
        0xFFCCCCCC.toInt()
    }
}
