package com.napps.filamentmanager.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity used to map material variants to their human-readable color names.
 * This is especially useful for non-Bambu filaments where the tag UID doesn't
 * provide a direct color mapping.
 */
@Entity(tableName = "color_cross_ref")
data class ColorCrossRef(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val brand: String? = "Bambu Lab",
    val type: String? = "Unknown",
    /** The unique ID of the material variant (e.g., "G02-Y0"). */
    val materialVariantID: String?,
    /** The color name associated with this variant (e.g., "Neon Yellow"). */
    val colorName: String,
    /** Optional RGB integer representation of the color. */
    val colorInt: Int?,
    /** Original RGB integer value read from the filament's tag. */
    val tagColorRgb: Int? = null
)

/**
 * Entity to track filaments that were added (via AMS or NFC) but whose 
 * color/material mapping is missing from ColorCrossRef.
 */
@Entity(tableName = "unmapped_filaments")
data class UnmappedFilament(
    @PrimaryKey
    val filamentId: Int,
    /** The variant ID that is missing from the database. */
    val materialVariantID: String?,
    /** The raw color int if available. */
    val colorInt: Int? = null,
    val tagColorRgb: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)
