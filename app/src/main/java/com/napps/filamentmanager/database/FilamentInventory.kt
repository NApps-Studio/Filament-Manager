package com.napps.filamentmanager.database

import androidx.room.Entity
import androidx.room.PrimaryKey


/**
 * Represents the usage and physical status of a filament spool in the inventory.
 */
object AvailabilityStatus {
    /** Status for spools with no known history. */
    const val UNKNOWN = 0
    /** New, unopened filament spool. */
    const val NEW = 1
    /** Spool has been opened but is not currently in a printer. */
    const val OPEN = 2
    /** Spool is actively being used in a non-AMS setup. */
    const val IN_USE = 3
    /** Spool has been fully consumed. */
    const val OUT_OF_STOCK = 4
    /** Spool is currently loaded into an Automatic Material System (AMS) unit. */
    const val IN_AMS = 5
}

/**
 * Entity representing a single filament spool in the local database.
 * Tracks material properties, physical identification (UID), and current stock status.
 */
@Entity(tableName = "filaments_inventory")
data class FilamentInventory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    /** The manufacturer of the filament (e.g., "Bambu Lab"). */
    val brand: String = "Bambu Lab",
    /** The material type (e.g., "PLA", "PETG"). */
    val type: String?,
    /** Internal identifier for the specific material variant. */
    val materialVariantID: String?,
    /** General material identifier. */
    val materialID: String?,
    /** Filament diameter in mm (usually 1.75). */
    val diameter: Float?,
    /** Human-readable name of the color. */
    val colorName: String?,
    /** RGB integer value representing the filament color. */
    val colorRgb: Int?,
    /** Original RGB integer value read from the filament's tag. Never changes. */
    val tagColorRgb: Int? = null,
    /** Unique UID read from the filament's RFID tag (if applicable). */
    val trayUID: String?,
    /** Timestamp when this spool was added to the inventory. */
    val timestamp: Long = 0,
    /** Exact timestamp when the spool was added. */
    val addedTimestamp: Long = System.currentTimeMillis(),
    /** Timestamp when the spool was marked as Out of Stock. */
    val oosTimestamp: Long? = null,
    /** Current estimated weight or description of remaining filament. */
    val weight: String?,
    /** Percentage of filament remaining (0.0 to 1.0). */
    val usedPercent: Float? = 1f,
    /** Estimated remaining length of filament in meters. */
    val filamentLength: Int?,
    /** Current availability state (see [AvailabilityStatus]). */
    val availabilityStatus: Int = AvailabilityStatus.UNKNOWN,
    /** General record status (see [StatusCodes]). */
    val status: Int = StatusCodes.UNKNOWN,
    /** Error tracking for this specific record (see [ErrorCodes]). */
    val error: Int = ErrorCodes.NONE
)

/**
 * Reasons why a filament was marked as "potential out of stock".
 */
object PotentialOosReason {
    /** The filament's weight is below the user-defined threshold. */
    const val LOW_THRESHOLD = 1
    /** The printer reported an HMS runout error for the slot containing this filament. */
    const val AMS_RUNOUT = 2
}

/**
 * Entity tracking filaments that are nearing depletion or have run out.
 */
@Entity(tableName = "potential_out_of_stock")
data class PotentialOutOfStock(
    @PrimaryKey val filamentId: Int,
    /** Reason for marking this filament (see [PotentialOosReason]). */
    val reason: Int = PotentialOosReason.LOW_THRESHOLD,
    /** Timestamp when this filament was marked. */
    val timestamp: Long = System.currentTimeMillis()
)
