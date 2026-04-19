package com.napps.filamentmanager.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents the summary of a single synchronization operation.
 * Aggregates all successes and failures into one report entry.
 */
@Entity(tableName = "sync_reports")
data class SyncReport(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val syncType: String, // "Full Sync" or "Background Update"
    val summary: String, // e.g., "Completed with 2 errors" or "Successfully synced 45 variants"
    val details: String? = null, // Long-form details of errors or specific failures
    val affectedVariants: Int, // Total variants successfully updated
    val errorCount: Int, // Number of individual failures encountered
    val isRead: Boolean = false,
    val isError: Boolean = false,
    val syncedContent: String? = null // Serialized summary of successfully synced filaments
)
