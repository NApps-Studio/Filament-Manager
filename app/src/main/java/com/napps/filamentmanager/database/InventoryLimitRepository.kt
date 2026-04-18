package com.napps.filamentmanager.database

import android.content.Context
import androidx.lifecycle.LiveData

/**
 * Repository for managing inventory limits and their synchronization with availability trackers.
 * Ensures that stock level monitoring automatically updates notification states.
 */
class InventoryLimitRepository(
    private val inventoryLimitDao: InventoryLimitDao,
    private val vendorFilamentsDao: VendorFilamentsDao,
    private val context: Context
) {

    /**
     * Flow of all defined inventory limits and their associated vendor filaments.
     */
    val allLimitsWithFilaments: LiveData<List<LimitWithFilaments>> = inventoryLimitDao.getAllLimitsWithFilaments()

    /**
     * Schedules a background check to verify current stock levels against defined limits.
     */
    private fun scheduleLimitCheck() {
        InventoryLimitWorker.schedule(context)
    }

    /**
     * Updates an inventory limit and its associated filaments.
     * Also synchronizes the underlying "Limit Service" tracker to reflect the new state.
     */
    suspend fun updateLimitWithFilaments(limit: InventoryLimit, filamentIds: List<Int>) {
        val limitId = inventoryLimitDao.updateLimitWithFilaments(limit, filamentIds)
        
        // Sync with underlying AvailabilityTracker if it exists
        val updatedLimit = inventoryLimitDao.getAllLimitsWithFilamentsStatic().find { it.limit.id == limitId }
        
        if (updatedLimit != null) {
            val tId = updatedLimit.limit.trackerId
            if (tId != null) {
                val allTrackers = vendorFilamentsDao.getAllAvailabilityTrackersStatic()
                val existingTracker = allTrackers.find { it.tracker.id == tId }
                if (existingTracker != null) {
                    // Maintain synchronization: update name and lock editing
                    vendorFilamentsDao.update(existingTracker.tracker.copy(
                        name = "Limit Service: ${updatedLimit.limit.name}",
                        isEditable = false,
                        isDeletable = false
                    ))
                    
                    // Synchronize the filaments observed by the tracker
                    existingTracker.filaments.forEach { oldFil ->
                        vendorFilamentsDao.deleteCrossRef(TrackerFilamentCrossRef(tId, oldFil.id))
                    }
                    filamentIds.forEach { newFilId ->
                        vendorFilamentsDao.insertCrossRef(TrackerFilamentCrossRef(tId, newFilId))
                    }
                } else {
                    // Recovery: trackerId exists but no tracker record found, create one
                    createNewTrackerForLimit(updatedLimit)
                }
            } else {
                // Initial setup: create the service tracker for this limit
                createNewTrackerForLimit(updatedLimit)
            }
        }
        scheduleLimitCheck() // Trigger re-check after limit changes
    }

    /**
     * Creates a specialized "Limit Service" tracker that is managed by the limit system.
     * These trackers are not editable by the user and are used for background notifications.
     */
    private suspend fun createNewTrackerForLimit(limitWithFilaments: LimitWithFilaments) {
        val trackerName = "Limit Service: ${limitWithFilaments.limit.name}"
        val tracker = AvailabilityTracker(
            name = trackerName,
            notificationEnabled = false, // Start OFF, background worker will turn it ON if stock is low
            isDeletable = false,
            isEditable = false
        )
        val trackerId = vendorFilamentsDao.insertTracker(tracker).toInt()
        limitWithFilaments.filaments.forEach { filament ->
            vendorFilamentsDao.insertCrossRef(TrackerFilamentCrossRef(trackerId, filament.id))
        }
        inventoryLimitDao.updateTrackerId(limitWithFilaments.limit.id, trackerId)
    }

    /**
     * Deletes an inventory limit and its associated internal tracker.
     */
    suspend fun deleteLimit(limit: InventoryLimit) {
        inventoryLimitDao.deleteLimit(limit)
        limit.trackerId?.let { tId ->
            val trackers = vendorFilamentsDao.getAllAvailabilityTrackersStatic()
            trackers.find { it.tracker.id == tId }?.let {
                vendorFilamentsDao.delete(it.tracker)
            }
        }
    }

    /**
     * Retrieves all inventory limits statically.
     */
    suspend fun getAllLimitsWithFilamentsStatic(): List<LimitWithFilaments> {
        return inventoryLimitDao.getAllLimitsWithFilamentsStatic()
    }

    /**
     * Toggles the active state of a limit.
     * If deactivated, the underlying tracker is also disabled.
     */
    suspend fun updateLimitStatus(limitId: Int, isActive: Boolean) {
        inventoryLimitDao.updateLimitStatus(limitId, isActive)
        
        val limits = inventoryLimitDao.getAllLimitsWithFilamentsStatic()
        val limit = limits.find { it.limit.id == limitId }
        limit?.limit?.trackerId?.let { tId ->
            val allTrackers = vendorFilamentsDao.getAllAvailabilityTrackersStatic()
            val trackerWithFilaments = allTrackers.find { it.tracker.id == tId }
            trackerWithFilaments?.let {
                vendorFilamentsDao.update(it.tracker.copy(
                    // Turn OFF tracker if limit is disabled.
                    // If enabled, let the worker handle notifications based on stock levels.
                    notificationEnabled = if (!isActive) false else it.tracker.notificationEnabled,
                    isEditable = false,
                    isDeletable = false
                ))
            }
        }
        scheduleLimitCheck() // Trigger re-check after status changes
    }

    /**
     * Performs a property-based import of inventory limits.
     * Matches incoming filaments by SKU or physical characteristics.
     *
     * @param incomingLimits List of limits and their associated filament property maps.
     * @param replaceDuplicates If true, existing limits with the same name will be overwritten.
     * @param onComplete Callback invoked with the total number of successfully processed limits.
     */
    suspend fun importRobustLimits(
        incomingLimits: List<Pair<Map<String, String>, List<Map<String, String>>>>,
        replaceDuplicates: Boolean,
        onComplete: (Int) -> Unit
    ) {
        val existingLimits = inventoryLimitDao.getAllLimitsWithFilamentsStatic()
        val allVendorFilaments = vendorFilamentsDao.getAllVendorFilamentsStatic()
        var processedCount = 0
        
        incomingLimits.forEach { (limitMap, filamentsInfo) ->
            val name = limitMap["name"] ?: return@forEach
            val existingMatch = existingLimits.find { it.limit.name == name }
            
            // Resolve local filament IDs by matching properties across the local database
            val resolvedFilamentIds = filamentsInfo.mapNotNull { info ->
                val sku = info["sku"]
                val brand = info["brand"]
                val type = info["type"]
                val colorName = info["colorName"]
                val packageType = info["packageType"]
                
                var match = if (!sku.isNullOrBlank()) {
                    allVendorFilaments.find { it.sku == sku }
                } else null
                
                if (match == null) {
                    match = allVendorFilaments.find { 
                        it.brand == brand && it.type == type && 
                        it.colorName == colorName && it.packageType == packageType
                    }
                }
                match?.id
            }

            if (existingMatch != null) {
                // Check if the content is identical to avoid redundant database writes
                val sameContent = existingMatch.limit.minFilamentsNeeded == (limitMap["minFilamentsNeeded"]?.toIntOrNull() ?: 1) &&
                                  existingMatch.limit.minWeightThreshold == (limitMap["minWeightThreshold"]?.toFloatOrNull() ?: 100f) &&
                                  existingMatch.limit.isActive == (limitMap["isActive"]?.toBoolean() ?: true) &&
                                  existingMatch.filaments.map { it.id }.toSet() == resolvedFilamentIds.toSet()
                
                if (sameContent) return@forEach

                if (replaceDuplicates) {
                    val limit = existingMatch.limit.copy(
                        minFilamentsNeeded = limitMap["minFilamentsNeeded"]?.toIntOrNull() ?: existingMatch.limit.minFilamentsNeeded,
                        minWeightThreshold = limitMap["minWeightThreshold"]?.toFloatOrNull() ?: existingMatch.limit.minWeightThreshold,
                        isActive = limitMap["isActive"]?.toBoolean() ?: existingMatch.limit.isActive
                    )
                    updateLimitWithFilaments(limit, resolvedFilamentIds)
                    processedCount++
                }
            } else {
                val limit = InventoryLimit(
                    name = name,
                    minFilamentsNeeded = limitMap["minFilamentsNeeded"]?.toIntOrNull() ?: 1,
                    minWeightThreshold = limitMap["minWeightThreshold"]?.toFloatOrNull() ?: 100f,
                    isActive = limitMap["isActive"]?.toBoolean() ?: true
                )
                updateLimitWithFilaments(limit, resolvedFilamentIds)
                processedCount++
            }
        }
        syncLimitsWithTrackers()
        scheduleLimitCheck() // Trigger re-check after import
        onComplete(processedCount)
    }

    /**
     * Performs a comprehensive synchronization between Limits and Trackers.
     * Ensures every Limit has exactly one valid "Limit Service" tracker and cleans up
     * orphaned or duplicated trackers from previous versions or failed operations.
     */
    suspend fun syncLimitsWithTrackers() {
        val limits = inventoryLimitDao.getAllLimitsWithFilamentsStatic()
        val allTrackers = vendorFilamentsDao.getAllAvailabilityTrackersStatic()
        val usedTrackerIds = mutableSetOf<Int>()

        limits.forEach { limitWithFilaments ->
            val limit = limitWithFilaments.limit
            val expectedName = "Limit Service: ${limit.name}"
            
            // 1. Resolve tracker by ID, falling back to name matching for recovery
            var tracker = allTrackers.find { it.tracker.id == limit.trackerId }
            if (tracker == null) {
                tracker = allTrackers.find { it.tracker.name == expectedName }
                if (tracker != null) {
                    inventoryLimitDao.updateTrackerId(limit.id, tracker.tracker.id)
                }
            }

            if (tracker != null) {
                // 2. Synchronize basic properties and locked states
                if (tracker.tracker.name != expectedName || 
                    (tracker.tracker.notificationEnabled && !limit.isActive) ||
                    tracker.tracker.isEditable || 
                    tracker.tracker.isDeletable) {
                    vendorFilamentsDao.update(tracker.tracker.copy(
                        name = expectedName,
                        notificationEnabled = if (!limit.isActive) false else tracker.tracker.notificationEnabled,
                        isEditable = false,
                        isDeletable = false
                    ))
                }

                // 3. Synchronize filament lists
                val currentFilamentIds = tracker.filaments.map { it.id }.toSet()
                val targetFilamentIds = limitWithFilaments.filaments.map { it.id }.toSet()
                
                if (currentFilamentIds != targetFilamentIds) {
                    currentFilamentIds.filter { it !in targetFilamentIds }.forEach { id ->
                        vendorFilamentsDao.deleteCrossRef(TrackerFilamentCrossRef(tracker.tracker.id, id))
                    }
                    targetFilamentIds.filter { it !in currentFilamentIds }.forEach { id ->
                        vendorFilamentsDao.insertCrossRef(TrackerFilamentCrossRef(tracker.tracker.id, id))
                    }
                }
                usedTrackerIds.add(tracker.tracker.id)
            } else {
                // 4. Create missing tracker
                val newTracker = AvailabilityTracker(
                    name = expectedName,
                    notificationEnabled = false,
                    isDeletable = false,
                    isEditable = false
                )
                val newId = vendorFilamentsDao.insertTracker(newTracker).toInt()
                limitWithFilaments.filaments.forEach { filament ->
                    vendorFilamentsDao.insertCrossRef(TrackerFilamentCrossRef(newId, filament.id))
                }
                inventoryLimitDao.updateTrackerId(limit.id, newId)
                usedTrackerIds.add(newId)
            }
        }

        // 5. Delete orphaned or duplicated trackers that are no longer linked to a limit
        val finalTrackers = vendorFilamentsDao.getAllAvailabilityTrackersStatic()
        finalTrackers.forEach { trackerWithFilaments ->
            val t = trackerWithFilaments.tracker
            if (t.name.startsWith("Limit Service: ") && t.id !in usedTrackerIds) {
                vendorFilamentsDao.delete(t)
            }
        }
    }
}
