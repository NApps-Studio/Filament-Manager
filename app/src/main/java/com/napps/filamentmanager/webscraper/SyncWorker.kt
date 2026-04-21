package com.napps.filamentmanager.webscraper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.napps.filamentmanager.MainActivity
import com.napps.filamentmanager.R
import com.napps.filamentmanager.database.AppDatabase
import com.napps.filamentmanager.database.AvailabilityMenuText
import com.napps.filamentmanager.database.UserPreferencesRepository
import com.napps.filamentmanager.database.VendorFilament
import com.napps.filamentmanager.database.SyncReport
import com.google.gson.Gson
import com.napps.filamentmanager.util.NotificationGroupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that periodically checks the availability of filaments
 * that are currently being tracked by the user.
 *
 * Unlike [FullSyncWorker], this worker only syncs specific product pages
 * associated with active trackers, making it much more efficient for routine
 * availability updates.
 */
class SyncWorker(
    val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sync_channel_Status"
        
        val channel = NotificationChannel(
            channelId, "Sync Status", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Status of background sync tasks"
        }
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setContentTitle("Filament Manager")
            .setContentText("Filament Availability Check Running...")
            .setSmallIcon(R.drawable.filamentmanagerlogobw)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return ForegroundInfo(1001, notification)
    }

    /**
     * Executes the periodic availability check.
     * 1. Fetches all active trackers and their associated product links.
     * 2. Syncs each unique link for real-time stock status.
     * 3. Updates the database and triggers notifications if a tracker becomes fully available.
     */
    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val database = AppDatabase.getDatabase(appContext)
        val dao = database.vendorFilamentsDao()
        val reportDao = database.syncReportDao()
        val userPrefs = UserPreferencesRepository(appContext)
        
        val startTime = System.currentTimeMillis()
        var reportId: Long = -1
        
        // Create an initial report entry so it shows up immediately
        withContext(Dispatchers.IO) {
            val initialReport = SyncReport(
                timestamp = startTime,
                syncType = "Availability Update",
                summary = "Sync starting...",
                affectedVariants = 0,
                errorCount = 0,
                isError = false
            )
            reportId = reportDao.insert(initialReport)
        }

        val links = withContext(Dispatchers.IO) {
            dao.getAllTrackersWithNotificationsStatic()
                .flatMap { it.filaments }
                .mapNotNull { it.typeLink }
                .map { it.substringBefore("?") } // Remove variant IDs to load the main product page
                .distinct()
        }

        if (links.isEmpty()) {
            withContext(Dispatchers.IO) {
                reportDao.update(SyncReport(
                    id = reportId.toInt(),
                    timestamp = startTime,
                    syncType = "Availability Update",
                    summary = "Finished: No active trackers found to check.",
                    affectedVariants = 0,
                    errorCount = 0,
                    isError = false
                ))
            }
            return@withContext Result.success()
        }

        NotificationGroupManager.updateStatus(appContext, "SyncWorker", "Checking ${links.size} pages...", "sync_channel_Status")

        val syncEngine = HeadlessWebViewSync(appContext)
        try {
            val productLinks = links.map { link ->
                val typeName = link.substringBefore("?").substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }
                ProductLink(link, 1, typeName)
            }
            val syncResult = syncEngine.sync(productLinks, StartPagesOfVendors.BAMBU_LAB, "Availability Update", availabilityOnly = true) { pagesDone: Int, totalPages: Int, success: Int, failed: Int, newFilaments: List<VendorFilament> ->
                val progressText = "Checking: $pagesDone/$totalPages | Success: $success | Failed: $failed"
                NotificationGroupManager.updateStatus(appContext, "SyncWorker", progressText, "sync_channel_Status")
                
                // Update the existing report with progress
                CoroutineScope(Dispatchers.IO).launch {
                    val currentReport = SyncReport(
                        id = reportId.toInt(),
                        timestamp = startTime,
                        syncType = "Availability Update",
                        summary = progressText,
                        affectedVariants = success,
                        errorCount = failed,
                        isError = failed > 0,
                        syncedContent = "Syncing in progress..."
                    )
                    reportDao.update(currentReport)

                    // Incremental database update for background sync
                    for (syncItem in newFilaments) {
                        val existing = dao.getFilamentBySku(syncItem.sku ?: "-1")
                        if (existing != null) {
                            dao.update(existing.copy(
                                isAvailable = syncItem.isAvailable,
                                timestamp = syncItem.timestamp,
                                error = syncItem.error,
                                status = syncItem.status
                            ))
                        }
                    }
                }
            }

            val syncList = syncResult.filaments

            withContext(Dispatchers.IO) {
                val finalReport = SyncReport(
                    id = reportId.toInt(),
                    timestamp = startTime,
                    syncType = "Availability Update",
                    summary = syncResult.summary,
                    details = syncResult.details,
                    affectedVariants = syncResult.affectedVariants,
                    errorCount = syncResult.errorCount,
                    isError = syncResult.isError,
                    syncedContent = Gson().toJson(syncList)
                )
                reportDao.update(finalReport)

                // Final status check and menu update
                val trackers = dao.getAllTrackersWithNotificationsStatic()
                val statusText = if (trackers.isNotEmpty()) "Enabled" else "Disabled"
                val currentTime = System.currentTimeMillis()
                val menuText = dao.getMenuTextStatic("TopMenuRow1")
                if (menuText != null) {
                    if (!menuText.text.contains("Full sync")) {
                        dao.update(menuText.copy(text = statusText, lastUpdated = currentTime))
                    }
                } else {
                    dao.insert(AvailabilityMenuText(id = 1, name = "TopMenuRow1", text = statusText, lastUpdated = currentTime))
                }
                
                val readyTrackers = dao.getFullyAvailableTrackersStatic()
                readyTrackers.forEach { trackerWithFilaments ->
                    val filamentNames = trackerWithFilaments.filaments
                        .take(3)
                        .joinToString(", ") { it.colorName ?: "Unknown" }

                    val message = if (trackerWithFilaments.filaments.size > 3) {
                        "$filamentNames and more are in stock!"
                    } else {
                        "$filamentNames are in stock!"
                    }

                    postTrackerNotification(
                        appContext,
                        id = trackerWithFilaments.tracker.id,
                        title = "Tracker Ready: ${trackerWithFilaments.tracker.name}",
                        content = message
                    )
                }
                NotificationGroupManager.updateStockAlertSummary(appContext, readyTrackers.size)
            }

            NotificationGroupManager.updateStatus(appContext, "SyncWorker", "Last check completed.", "sync_channel_Status")
            Result.success()
        } catch (e: HeadlessWebViewSync.CloudflareChallengeException) {
            NotificationGroupManager.updateStatus(appContext, "SyncWorker", "Manual action required (Cloudflare)", "sync_channel_Status")
            withContext(Dispatchers.IO) {
                val report = SyncReport(
                    id = reportId.toInt(),
                    timestamp = startTime,
                    syncType = "Availability Update",
                    summary = "Sync blocked by Cloudflare",
                    details = "Manual verification required in store",
                    affectedVariants = 0,
                    errorCount = 1,
                    isError = true
                )
                reportDao.update(report)
            }
            Result.retry()
        } catch (e: Exception) {
            NotificationGroupManager.updateStatus(appContext, "SyncWorker", "Error: ${e.message}", "sync_channel_Status")
            withContext(Dispatchers.IO) {
                val report = SyncReport(
                    id = reportId.toInt(),
                    timestamp = startTime,
                    syncType = "Availability Update",
                    summary = "Critical sync failure",
                    details = "Error: ${e.message}",
                    affectedVariants = 0,
                    errorCount = 1,
                    isError = true
                )
                reportDao.update(report)
            }
            Result.failure()
        }
    }

    private fun postTrackerNotification(context: Context, id: Int, title: String, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "stock_alerts"

        val channel = NotificationChannel(
            channelId, "Stock Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when trackers are fully in stock"
        }
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("TARGET_SCREEN", "AVAILABILITY")
            putExtra("EXPAND_READY", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 2000 + id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.filamentmanagerlogo)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup(NotificationGroupManager.STOCK_ALERTS_GROUP_KEY)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(id, notification)
    }

    companion object {
        /**
         * Enqueues the sync worker.
         *
         * @param immediate If true, runs a one-time check immediately.
         *                  If false, schedules periodic checks based on user preferences.
         * @param intervalMinutes The interval in minutes for periodic checks.
         */
        fun enqueue(context: Context, immediate: Boolean = false, intervalMinutes: Int = 30) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            if (immediate) {
                val oneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "SyncWork_Immediate",
                    ExistingWorkPolicy.REPLACE,
                    oneTimeWorkRequest
                )
            } else {
                val periodicWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                    intervalMinutes.toLong(), TimeUnit.MINUTES,
                    5, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "SyncWork_Periodic",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicWorkRequest
                )
            }
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("SyncWork_Periodic")
        }
    }
}
