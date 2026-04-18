package com.napps.filamentmanager.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * General status codes for vendor filament tracking.
 */
object StatusCodes {
    const val UNKNOWN = 0
    /** Data was successfully synced from the vendor website. */
    const val SYNCED = 1
}

/**
 * Error codes encountered during syncing or processing.
 */
object ErrorCodes {
    const val NONE = 0
    /** The product page could not be reached. */
    const val NETWORK_ERROR = 1
    /** The page HTML structure has changed and parsing failed. */
    const val PARSE_ERROR = 2
}

/**
 * Entity representing a specific filament product variant offered by a vendor.
 * These records are populated by the [FullSyncWorker] and monitored by [SyncWorker].
 */
@Entity(tableName = "vendor_filaments")
data class VendorFilament(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    /** The vendor brand (e.g., "Bambu Lab"). */
    val brand: String?,
    /** The product category/type (e.g., "PLA Basic"). */
    val type: String?,
    /** The specific variant name (e.g., "PLA Basic - Black"). */
    val variantName: String?,
    /** The name of the color as displayed on the website. */
    val colorName: String?,
    /** RGB integer value for the color. */
    val colorRgb: Int?,
    /** Unique Stock Keeping Unit (SKU) used for identification across syncs. */
    val sku: String?,
    /** The direct URL to the product's type category or detail page. */
    val typeLink: String?,
    /** Timestamp of the last successful sync. */
    val timestamp: Long,
    /** Packaging type (e.g., "Spool", "Refill"). */
    val packageType: String?,
    /** Spool weight (e.g., "1kg"). */
    val weight: String?,
    /** Listed price in the vendor's currency. */
    val price: Double?,
    /** True if the item is currently listed as 'In Stock' on the website. */
    val isAvailable: Boolean,
    /** Optional estimated restock date if provided by the vendor. */
    val availableOnDate: Long?,
    val status: Int = StatusCodes.UNKNOWN,
    val error: Int = ErrorCodes.NONE
)
