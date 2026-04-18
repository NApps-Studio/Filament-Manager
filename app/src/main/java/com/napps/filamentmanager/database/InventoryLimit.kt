package com.napps.filamentmanager.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Entity representing an inventory "Limit" rule.
 * These rules define the minimum acceptable stock for a group of filaments.
 * If the physical inventory falls below these thresholds, a notification
 * or shopping action can be triggered.
 */
@Entity(tableName = "inventory_limits")
data class InventoryLimit(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    /** Name of the inventory rule (e.g., "Low Stock Alert: Black PLA"). */
    val name: String,
    /** Minimum number of physical spools required to be in stock. */
    val minFilamentsNeeded: Int = 1,
    /** Weight threshold below which a spool is considered "nearly empty". */
    val minWeightThreshold: Float = 100f,
    /** Whether this rule is currently enabled. */
    val isActive: Boolean = true,
    /** Optional link to an [AvailabilityTracker] for automatic restock monitoring. */
    val trackerId: Int? = null
)

/**
 * Join table for mapping [InventoryLimit] rules to specific [VendorFilament] types.
 * This allows a single rule to apply to multiple similar filament variants (e.g., all 1kg PLA spools).
 *
 * @property limitId The ID of the parent [InventoryLimit] rule.
 * @property filamentId The ID of the [VendorFilament] variant this rule tracks.
 */
@Entity(
    tableName = "limit_filament_cross_ref",
    primaryKeys = ["limitId", "filamentId"],
    foreignKeys = [
        ForeignKey(
            entity = InventoryLimit::class,
            parentColumns = ["id"],
            childColumns = ["limitId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VendorFilament::class,
            parentColumns = ["id"],
            childColumns = ["filamentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("filamentId")]
)
data class LimitFilamentCrossRef(
    val limitId: Int,
    val filamentId: Int
)

/**
 * Composite data class representing an inventory limit and all its tracked filament variants.
 *
 * @property limit The base [InventoryLimit] entity.
 * @property filaments The list of [VendorFilament] variants associated with this limit.
 */
data class LimitWithFilaments(
    @Embedded val limit: InventoryLimit,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = LimitFilamentCrossRef::class,
            parentColumn = "limitId",
            entityColumn = "filamentId"
        )
    )
    val filaments: List<VendorFilament>
)
