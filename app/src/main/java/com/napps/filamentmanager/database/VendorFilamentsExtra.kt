package com.napps.filamentmanager.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation


/**
 * A data class specifically for displaying a summary of filament data in a list.
 */

/**
 * Entity for storing small UI text snippets or status messages in the database.
 * Used for persistent UI status bars (e.g., "Monitoring Active", "Running full sync").
 */
@Entity(tableName = "availability_menu_text")
data class AvailabilityMenuText(
    @PrimaryKey
    val id: Int = 0,
    /** Unique name/key for the menu text row. */
    val name: String,
    /** The actual text content to display. */
    val text: String,
    /** Timestamp of the last time this text was updated. */
    val lastUpdated: Long? = null,
)

/**
 * Entity representing a "Tracker" which groups multiple filament variants together.
 * If all filaments in a tracker are in stock, the user can be notified.
 */
@Entity(tableName = "availability_trackers")
data class AvailabilityTracker(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    /** Name of the tracker (e.g., "Black PLA Refills"). */
    val name: String,
    /** Whether notifications are enabled for this specific tracker. */
    val notificationEnabled: Boolean,
    /** Whether the user is allowed to delete this tracker (e.g., built-in trackers). */
    val isDeletable: Boolean = true,
    /** Whether the user is allowed to edit this tracker. */
    val isEditable: Boolean = true
)

/**
 * Composite data class that represents a tracker and its associated filament list.
 * Uses a Room [Relation] to automatically fetch linked filaments via a join table.
 */
data class TrackerWithFilaments(
    @Embedded val tracker: AvailabilityTracker,
    @Relation(
        parentColumn = "id",            // Tracker ID
        entityColumn = "id",            // Filament ID
        associateBy = Junction(
            value = TrackerFilamentCrossRef::class,
            parentColumn = "trackerId",
            entityColumn = "filamentId"
        )
    )
    val filaments: List<VendorFilament>
)

/**
 * Join table for the many-to-many relationship between [AvailabilityTracker] and [VendorFilament].
 *
 * @property trackerId The ID of the parent [AvailabilityTracker].
 * @property filamentId The ID of the [VendorFilament] variant being tracked.
 */
@Entity(
    tableName = "tracker_filament_cross_ref",
    primaryKeys = ["trackerId", "filamentId"],
    foreignKeys = [
        ForeignKey(
            entity = AvailabilityTracker::class,
            parentColumns = ["id"],
            childColumns = ["trackerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("filamentId")]
)
data class TrackerFilamentCrossRef(
    val trackerId: Int,
    val filamentId: Int
)

/**
 * Simple data class representing color name and its corresponding RGB value.
 */
data class ColorInfo(
    val colorName: String?,
    val colorRgb: Int?
)
