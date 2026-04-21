package com.napps.filamentmanager.webscraper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

import com.napps.filamentmanager.database.VendorFilament
import com.napps.filamentmanager.database.VendorFilamentsDao
import com.napps.filamentmanager.database.TrackerFilamentCrossRef
import com.napps.filamentmanager.database.SyncReport
import com.google.gson.Gson
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
            .setContentTitle("Filament Manager")
            .setContentText("Full Database Sync Running...")
            .setSmallIcon(R.drawable.filamentmanagerlogobw)
            .setOngoing(true)
            .setSilent(true)
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
    override suspend fun doWork(): Result {
        android.util.Log.d("BambuSync", "FullSyncWorker: doWork() started on thread ${Thread.currentThread().name}")
        val database = AppDatabase.getDatabase(appContext)
        val dao = database.vendorFilamentsDao()
        val userPrefs = UserPreferencesRepository(appContext)
        
        try {
            // Increased to 30 minutes for full sync to prevent timeout restarts
            withTimeout(1800000) { 
                runSyncLogic(dao, userPrefs)
            }
            return Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            android.util.Log.d("BambuSync", "Full Sync Worker cancelled (expected during Restart)")
            throw e 
        } catch (e: Exception) {
            android.util.Log.e("BambuSync", "Full Sync Worker encountered error: ${e.message}", e)
            handleFailure(dao, e)
            return Result.success()
        }
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
                val report = SyncReport(
                    syncType = "Full Sync",
                    summary = "Start page parse failure",
                    details = "Could not parse product links from: $startLink",
                    affectedVariants = 0,
                    errorCount = 1,
                    isError = true
                )
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(appContext).syncReportDao().insert(report)
                }
                throw Exception("Could not parse start page links.")
            }

            // Step 2: Sync each product page
            val syncResult = syncEngine.sync(productLinks, StartPagesOfVendors.BAMBU_LAB, "Full Sync") { pagesDone: Int, totalPages: Int, success: Int, failed: Int, newFilaments: List<VendorFilament> ->
                val progressText = "Full sync: $pagesDone/$totalPages | Success: $success | Failed: $failed"
                NotificationGroupManager.updateStatus(appContext, "FullSyncWorker", progressText, "sync_channel_Status")
                
                workerScope.launch(Dispatchers.IO) {
                    // Incremental database update
                    for (filament in newFilaments) {
                        dao.insertOrUpdate(filament)
                    }

                    val menuText = dao.getMenuTextStatic("TopMenuRow1")
                    if (menuText != null) {
                        dao.update(menuText.copy(text = progressText, lastUpdated = null))
                    }
                }
            }

            // Quality Check: If failure rate is too high (e.g. > 30%), treat as a worker failure to trigger retry
            val totalPages = productLinks.size
            val failureRate = if (totalPages > 0) syncResult.errorCount.toDouble() / totalPages else 1.0
            
            if (failureRate > 0.3 && totalPages > 5) {
                throw Exception("Sync quality too low (${syncResult.errorCount}/$totalPages pages failed). Check connection or Cloudflare.")
            }
            
            val syncList = syncResult.filaments
            
            // Final Sanity Check: Ensure we actually have data to save
            if (syncList.isEmpty() && syncResult.errorCount < totalPages) {
                 throw Exception("Sync finished but found 0 variants. Data parsing might be broken.")
            }
            
            withContext(Dispatchers.IO) {
                // Ensure we don't crash on batch insert if list is huge
                syncList.forEach { dao.insertOrUpdate(it) }
                
                val report = SyncReport(
                    syncType = "Full Sync",
                    summary = syncResult.summary,
                    details = syncResult.details,
                    affectedVariants = syncResult.affectedVariants,
                    errorCount = syncResult.errorCount,
                    isError = syncResult.isError,
                    syncedContent = Gson().toJson(syncList)
                )
                AppDatabase.getDatabase(appContext).syncReportDao().insert(report)

                userPrefs.setFirstSyncFinished(true)
                
                val trackers = dao.getAllTrackersWithNotificationsStatic()
                val statusText = if (trackers.isNotEmpty()) "Enabled" else "Disabled"
                val menuText = dao.getMenuTextStatic("TopMenuRow1")
                if (menuText != null) {
                    dao.update(menuText.copy(text = statusText, lastUpdated = System.currentTimeMillis()))
                }
            }

            val finalCount = syncList.size
            val totalVariantsExpected = productLinks.sumOf { it.expectedCount }
            
            val completionTitle = if (finalCount < totalVariantsExpected * 0.9) "Partial Sync Complete" else "Full Sync Complete"
            val completionMessage = "Pages: $totalPages/$totalPages | Filaments: $finalCount"

            postCompletionNotification(appContext, completionTitle, completionMessage)
            NotificationGroupManager.updateStatus(appContext, "FullSyncWorker", "Full sync completed ($finalCount items).", "sync_channel_Status")
            
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
        
        val userFriendlyMessage = when (e) {
            is HeadlessWebViewSync.CloudflareChallengeException -> "Verification needed. Open the store in a browser once to clear the Cloudflare check."
            is HeadlessWebViewSync.LanguageMismatchException -> e.message ?: "The store page is not in English."
            is kotlinx.coroutines.TimeoutCancellationException -> "Sync timed out. Your connection might be too slow."
            else -> "The sync encountered an error. Tap to try again."
        }
        
        withContext(Dispatchers.IO) {
            val report = SyncReport(
                syncType = "Full Sync",
                summary = "Critical sync failure",
                details = "Error: ${e.message}",
                affectedVariants = 0,
                errorCount = 1,
                isError = true
            )
            AppDatabase.getDatabase(appContext).syncReportDao().insert(report)
        }

        postFailureNotification(appContext, "Full Sync Failed", userFriendlyMessage)
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
         * @param force If true, replaces any existing work (REPLACE). If false, ignores if already running (KEEP).
         */
        fun enqueue(context: Context, force: Boolean = false) {
            android.util.Log.d("BambuSync", "FullSyncWorker: enqueue(force=$force) called")
            
            val workManager = WorkManager.getInstance(context)
            
            // Explicitly check if it's already running to prevent unnecessary work and logging
            val workInfos = workManager.getWorkInfosForUniqueWork("FullSyncWork").get()
            val isAlreadyActive = workInfos.any { !it.state.isFinished }
            
            if (isAlreadyActive && !force) {
                android.util.Log.d("BambuSync", "FullSyncWorker: Sync already active/queued, ignoring enqueue request.")
                return
            }

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
            val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            // Set initial status so user knows it's triggered
            CoroutineScope(Dispatchers.IO).launch {
                val dao = AppDatabase.getDatabase(context).vendorFilamentsDao()
                val statusText = if (isConnected) "Full sync queued..." else "Waiting for network..."
                val menuText = dao.getMenuTextStatic("TopMenuRow1")
                if (menuText != null) {
                    dao.update(menuText.copy(text = statusText, lastUpdated = null))
                } else {
                    dao.insert(AvailabilityMenuText(id = 1, name = "TopMenuRow1", text = statusText))
                }
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<FullSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .addTag("FULL_SYNC")
                .build()

            val policy = if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            workManager.enqueueUniqueWork(
                "FullSyncWork",
                policy,
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
