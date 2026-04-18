package com.napps.filamentmanager.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents the region for filament vendor sync.
 * This determines the specific URLs used when navigating the Bambu Lab website.
 */
enum class SyncRegion {
    EU, USA, ASIA, GLOBAL
}

/**
 * Entity storing user's Bambu Lab account authentication details.
 * The access token is encrypted via [CryptoManager] before storage.
 */
@Entity(tableName = "bambu_auth")
data class BambuLab(
    @PrimaryKey val id: Int = 1,
    /** The user's account UID. */
    val uid: String,
    /** Encrypted MQTT access token. */
    val encryptedToken: String,
    /** IV used for the access token encryption. */
    val iv: String,
    /** Selected sync region. */
    val region: SyncRegion,
    /** True if the user is using the China mainland cloud server. */
    val isInChina: Boolean,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Entity representing the most recent telemetry report from a printer.
 * To save storage, this entity typically stores the full cumulative JSON
 * of the printer's state.
 */
@Entity(tableName = "bambu_printer_status")
data class BambuReportEntity(
    /** SHA-256 hash of the printer's serial number. */
    @PrimaryKey val hashedSerialNumber: String,
    /** Cumulative JSON representation of the printer's state. */
    val rawJson: String,
    /** Timestamp of the last local update (from live MQTT or worker). */
    val lastUpdated: Long = System.currentTimeMillis(),
    /** Timestamp of the last successful sync performed by [BambuUpdateWorker]. */
    val lastWorkerSync: Long? = null,
    /** Description of the last error encountered during sync, or null if successful. */
    val lastSyncError: String? = null
)

/**
 * Entity representing metadata for a registered printer.
 * The serial number is stored encrypted to protect privacy.
 */
@Entity(tableName = "bambu_printers")
data class BambuPrinter(
    /** SHA-256 hash of the printer's serial number. */
    @PrimaryKey val hashedSerial: String,
    /** Encrypted raw serial number. */
    val encryptedSerial: String,
    /** IV used for the serial number encryption. */
    val iv: String,
    /** User-assigned display name for the printer. */
    val name: String = "Printer",
    /** Detected model of the printer. */
    val model: String = "Unknown",
    /** The UID of the Bambu account this printer belongs to. */
    val accountUid: String
)

/**
 * Entity tracking the history of filaments in an AMS slot.
 * Helps detect when a filament has run out or been replaced by comparing current and previous assignments.
 *
 * @property hashedSerial SHA-256 hash of the printer's serial number.
 * @property amsIndex The index of the AMS unit (e.g., 0, 1).
 * @property slotIndex The index of the slot within the AMS unit (0-3).
 * @property currentFilamentId The ID of the [FilamentInventory] currently in this slot.
 * @property previousFilamentId The ID of the filament that was previously in this slot.
 * @property lastUpdated Timestamp of the last status change.
 */
@Entity(tableName = "ams_slot_history", primaryKeys = ["hashedSerial", "amsIndex", "slotIndex"])
data class AmsSlotHistory(
    val hashedSerial: String,
    val amsIndex: Int,
    val slotIndex: Int,
    val currentFilamentId: Int? = null,
    val previousFilamentId: Int? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
