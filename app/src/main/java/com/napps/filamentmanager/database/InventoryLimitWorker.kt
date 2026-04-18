package com.napps.filamentmanager.database

import android.content.Context
import android.util.Log
import androidx.work.*
import com.napps.filamentmanager.FilamentManagerApplication
import com.napps.filamentmanager.R
import java.util.concurrent.TimeUnit
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat

/**
 * Worker that replaces the legacy InventoryLimitService.
 * It checks current filament stock against defined InventoryLimits and 
 * toggles AvailabilityTrackers accordingly.
 */
class InventoryLimitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "bambuInventoryLimitWorker"
        private const val WORK_NAME = "InventoryLimitCheckWork"

        /**
         * Schedules a one-time execution of the limit check.
         * Uses ExistingWorkPolicy.REPLACE to "debounce" multiple rapid changes.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<InventoryLimitWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .addTag("inventory_limit_check")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE, 
                request
            )
        }
    }

    /**
     * Creates and displays a low-priority foreground notification while the worker is running.
     * Required for expedited work requests.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "inventory_limit_check_channel"
        
        val channel = NotificationChannel(
            channelId, "Inventory Limit Check", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Status of inventory limit checking"
        }
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Bambu Filament Manager")
            .setContentText("Checking inventory limits...")
            .setSmallIcon(R.drawable.filamentmanagerlogobw)
            .setOngoing(true)
            .build()

        return ForegroundInfo(1003, notification)
    }

    /**
     * The main execution logic for inventory limit checking.
     * Compares current [FilamentInventory] stock levels against [InventoryLimit] rules.
     * If a limit is breached, an [AvailabilityTracker] is activated or created.
     */
    override suspend fun doWork(): Result {
        val app = applicationContext as FilamentManagerApplication
        val limitRepo = app.inventoryLimitRepository
        val inventoryRepo = app.filamentInventoryRepository
        val limitDao = app.database.inventoryLimitDao()
        val vendorDao = app.database.vendorFilamentsDao()

        Log.i(TAG, "Inventory Limit check started.")

        try {
            val limits = limitRepo.getAllLimitsWithFilamentsStatic()
            val inventory = inventoryRepo.getAllFilamentsStatic()

            if (limits.isEmpty()) {
                Log.i(TAG, "No limits defined. Work complete.")
                return Result.success()
            }

            limits.forEach { limitWithFilaments ->
                val limit = limitWithFilaments.limit
                val limitName = limit.name
                
                if (!limit.isActive) return@forEach

                // Calculate current stock for this specific limit
                var matchCount = 0
                inventory.forEach { inv ->
                    val isMonitored = limitWithFilaments.filaments.any { vf ->
                        val brandMatch = (vf.brand ?: "").trim().equals(inv.brand.trim(), ignoreCase = true)
                        val typeMatch = (vf.type ?: "").trim().equals((inv.type ?: "").trim(), ignoreCase = true)
                        val colorMatch = (vf.colorName ?: "").trim().equals((inv.colorName ?: "").trim(), ignoreCase = true)
                        brandMatch && typeMatch && colorMatch
                    }
                    
                    if (isMonitored) {
                        val weightValue = inv.weight?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull() ?: 1000f
                        val remainingGrams = weightValue * (inv.usedPercent ?: 1.0f)
                        if (remainingGrams >= limit.minWeightThreshold) {
                            matchCount++
                        }
                    }
                }

                val isLowStock = matchCount < limit.minFilamentsNeeded
                Log.i(TAG, "Limit '$limitName': $matchCount found (needed ${limit.minFilamentsNeeded}). Low stock: $isLowStock")

                if (isLowStock) {
                    if (limit.trackerId == null) {
                        createNewTracker(limitWithFilaments, vendorDao, limitDao)
                    } else {
                        val allTrackers = vendorDao.getAllAvailabilityTrackersStatic()
                        val existingTracker = allTrackers.find { it.tracker.id == limit.trackerId }
                        if (existingTracker != null) {
                            if (!existingTracker.tracker.notificationEnabled) {
                                Log.d(TAG, "Enabling tracker for '$limitName'")
                                vendorDao.update(existingTracker.tracker.copy(
                                    notificationEnabled = true,
                                    isEditable = false,
                                    isDeletable = false
                                ))
                            }
                        } else {
                            createNewTracker(limitWithFilaments, vendorDao, limitDao)
                        }
                    }
                } else {
                    limit.trackerId?.let { tId ->
                        val allTrackers = vendorDao.getAllAvailabilityTrackersStatic()
                        val existingTracker = allTrackers.find { it.tracker.id == tId }
                        if (existingTracker != null && existingTracker.tracker.notificationEnabled) {
                            Log.d(TAG, "Disabling tracker for '$limitName'")
                            vendorDao.update(existingTracker.tracker.copy(
                                notificationEnabled = false,
                                isEditable = false,
                                isDeletable = false
                            ))
                        }
                    }
                }
            }
            
            Log.i(TAG, "Inventory Limit check finished successfully.")
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Error in InventoryLimitWorker: ${e.message}")
            return Result.retry()
        }
    }

    private suspend fun createNewTracker(
        limitWithFilaments: LimitWithFilaments,
        vendorDao: VendorFilamentsDao,
        limitDao: InventoryLimitDao
    ) {
        val trackerName = "Limit Service: ${limitWithFilaments.limit.name}"
        Log.d(TAG, "Creating new tracker: '$trackerName'")
        
        val tracker = AvailabilityTracker(
            name = trackerName,
            notificationEnabled = true,
            isDeletable = false,
            isEditable = false
        )
        val filamentIds = limitWithFilaments.filaments.map { it.id }

        try {
            val trackerId = vendorDao.insertTracker(tracker).toInt()
            filamentIds.forEach { filId ->
                vendorDao.insertCrossRef(TrackerFilamentCrossRef(trackerId, filId))
            }
            limitDao.updateTrackerId(limitWithFilaments.limit.id, trackerId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create tracker: ${e.message}")
        }
    }
}
