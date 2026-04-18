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
import com.napps.filamentmanager.util.NotificationGroupManager
import com.napps.filamentmanager.util.SecuritySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.napps.filamentmanager.database.VendorFilamentsDao
import com.napps.filamentmanager.database.TrackerFilamentCrossRef
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

/**
 * WorkManager worker responsible for performing a comprehensive "Full Sync" of all
 * vendor filament offerings.
 */
class FullSyncWorker(
    val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val workerScope = CoroutineScope(Dispatchers.Main)
    private var retryCount = 0

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
            .setContentTitle("Bambu Filament Manager")
            .setContentText("Running full sync...")
            .setSmallIcon(R.drawable.filamentmanagerlogobw)
            .setOngoing(true)
            .build()

        return ForegroundInfo(1002, notification)
    }

    /**
     * Executes the sync task.
     * 1. Updates database status to "Running full sync".
     * 2. Navigates to vendor start pages to find all product links.
     * 3. Iteratively syncs each product page for variant details (color, SKU, price, etc.).
     * 4. Updates the database with the results and marks the full sync as completed.
     */
    override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
        val database = AppDatabase.getDatabase(appContext)
        val dao = database.vendorFilamentsDao()
        val userPrefs = UserPreferencesRepository(appContext)
        
        while (retryCount < 2) {
            try {
                return@withContext withTimeout(120000) { // 2 minute timeout
                    runSyncLogic(dao, userPrefs)
                }
            } catch (e: Exception) {
                retryCount++
                if (retryCount < 2) {
                    NotificationGroupManager.updateStatus(appContext, "FullSyncWorker", "Sync timeout, retrying ($retryCount/2)...", "sync_channel_Status")
                    delay(2000) // Small delay before retry
                } else {
                    handleFailure(dao, e)
                    return@withContext Result.failure()
                }
            }
        }
        Result.failure()
    }

    private suspend fun runSyncLogic(dao: VendorFilamentsDao, userPrefs: UserPreferencesRepository): Result {
        // Update status to Running
        withContext(Dispatchers.IO) {
            val menuText = dao.getMenuTextStatic("TopMenuRow1")
            if (menuText != null) {
                dao.update(menuText.copy(text = "Full sync in progress...", lastUpdated = null))
            } else {
                dao.insert(AvailabilityMenuText(id = 1, name = "TopMenuRow1", text = "Full sync in progress..."))
            }
        }

        NotificationGroupManager.updateStatus(appContext, "FullSyncWorker", "Starting full data sync...", "sync_channel_Status")

        val syncEngine = HeadlessWebViewSync(appContext)
        return try {
            val region = SecuritySession.getRegion()
            val startLink = StartPagesOfVendors.BAMBU_LAB.getLink(region)
            
            // Step 1: Get product links from start page
            val startPageHtml = syncEngine.loadPageAndGetHtml(startLink) 
                ?: throw Exception("Could not load start page")
            
            val productLinks = parseBambulabStartPage(startPageHtml, startLink, StartPagesOfVendors.BAMBU_LAB.testHtmlClass)
            if (productLinks == null || productLinks.isEmpty()) {
                throw Exception("Could not parse start page links.")
            }

            // Step 2: Sync each product page
            val syncList = syncEngine.sync(productLinks, StartPagesOfVendors.BAMBU_LAB) { current, total ->
                val progressText = "Full sync: $current/$total"
                NotificationGroupManager.updateStatus(appContext, "FullSyncWorker", progressText, "sync_channel_Status")
                
                workerScope.launch(Dispatchers.IO) {
                    val menuText = dao.getMenuTextStatic("TopMenuRow1")
                    if (menuText != null) {
                        dao.update(menuText.copy(text = progressText, lastUpdated = null))
                    }
                }
            }
            
            withContext(Dispatchers.IO) {
                syncList.forEach { dao.insertOrUpdate(it) }
                userPrefs.setFirstSyncFinished(true)
                
                val trackers = dao.getAllTrackersWithNotificationsStatic()
                val statusText = if (trackers.isNotEmpty()) "Enabled" else "Disabled"
                val menuText = dao.getMenuTextStatic("TopMenuRow1")
                if (menuText != null) {
                    dao.update(menuText.copy(text = statusText, lastUpdated = System.currentTimeMillis()))
                }
            }

            postCompletionNotification(
                appContext, 
                "Full Sync Complete", 
                "Synced ${productLinks.size} product types and updated ${syncList.size} filaments."
            )
            NotificationGroupManager.updateStatus(appContext, "FullSyncWorker", "Full sync completed (${syncList.size} items).", "sync_channel_Status")
            
            Result.success()
        } catch (e: Exception) {
            throw e
        } finally {
            syncEngine.cleanup()
        }
    }

    private suspend fun handleFailure(dao: VendorFilamentsDao, e: Exception) {
        withContext(Dispatchers.IO) {
            val trackers = dao.getAllTrackersWithNotificationsStatic()
            val statusText = if (trackers.isNotEmpty()) "Enabled" else "Disabled"
            val menuText = dao.getMenuTextStatic("TopMenuRow1")
            if (menuText != null) {
                dao.update(menuText.copy(text = statusText))
            }
        }
        postFailureNotification(appContext, "Full Sync Failed", "The sync timed out or encountered an error. Tap to try again.")
        NotificationGroupManager.updateStatus(appContext, "FullSyncWorker", "Error: ${e.message}", "sync_channel_Status")
    }

    private fun postFailureNotification(context: Context, title: String, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sync_notifications"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_AVAILABILITY", true)
            putExtra("HIGHLIGHT_FULL_SYNC", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 3001, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.filamentmanagerlogobw)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(3001, notification)
    }

    private fun postCompletionNotification(context: Context, title: String, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "sync_notifications"

        val channel = NotificationChannel(
            channelId, "Sync Status", NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 3000, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.filamentmanagerlogobw)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(3000, notification)
    }

    companion object {
        /**
         * Enqueues a one-time full sync work request.
         * Replaces any existing work to ensure only one full sync runs at a time.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<FullSyncWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("FULL_SYNC")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "FullSyncWork",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        /**
         * Checks if a full sync is currently queued or running.
         */
        suspend fun isWorkRunning(context: Context): Boolean {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork("FullSyncWork").get()
            return workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }
    }
}
