package com.napps.filamentmanager.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.napps.filamentmanager.MainActivity
import com.napps.filamentmanager.R

/**
 * Manages notification groups and summaries for the application.
 * This prevents notification clutter by grouping multiple service status updates
 * or stock alerts into single summary notifications when multiple items are active.
 */
object NotificationGroupManager {
    /** Group key for background service status notifications. */
    const val GROUP_KEY = "com.napps.filamentmanager.SERVICES_GROUP"
    /** Group key for filament stock alert notifications. */
    const val STOCK_ALERTS_GROUP_KEY = "com.napps.filamentmanager.STOCK_ALERTS_GROUP"
    
    private const val SUMMARY_ID = 1000
    private const val STOCK_SUMMARY_ID = 1005
    
    private const val SUMMARY_CHANNEL_ID = "service_group_summary"
    private const val STOCK_CHANNEL_ID = "stock_alerts"

    /** Tracks the current status string for each active background service. */
    private val serviceStatuses = mutableMapOf<String, String>()

    /**
     * Updates the status of a specific background service and refreshes the group summary.
     *
     * @param context The application context.
     * @param serviceId Unique identifier for the service (e.g., worker name).
     * @param status Current status message to display.
     * @param channelId The notification channel to use for this update.
     */
    @Synchronized
    fun updateStatus(context: Context, serviceId: String, status: String, channelId: String) {
        serviceStatuses[serviceId] = status
        updateSummary(context)
    }

    /**
     * Removes a service from the active status tracker and hides the summary if no services remain.
     *
     * @param context The application context.
     * @param serviceId The ID of the service to remove.
     * @param channelId The notification channel ID associated with the service.
     */
    @Synchronized
    fun removeService(context: Context, serviceId: String, channelId: String) {
        serviceStatuses.remove(serviceId)
        if (serviceStatuses.isEmpty()) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(SUMMARY_ID)
        } else {
            updateSummary(context)
        }
    }

    /**
     * Rebuilds and displays the summary notification for all active background services.
     */
    private fun updateSummary(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(
            SUMMARY_CHANNEL_ID,
            "Background Services Status",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val activeStatuses = serviceStatuses.values.toList()
        
        if (activeStatuses.size <= 1) {
            notificationManager.cancel(SUMMARY_ID)
            return
        }

        val serviceNames = serviceStatuses.keys.map { id ->
            when (id) {
                "BambuUpdateWorker" -> "Printer Sync"
                "InventoryLimitWorker" -> "Inventory Limits"
                "SyncWorker" -> "Availability Check"
                "FullSyncWorker" -> "Full Sync"
                else -> id
            }
        }.joinToString(", ")

        val content = "${activeStatuses.size} services active: $serviceNames"

        val summaryNotification = NotificationCompat.Builder(context, SUMMARY_CHANNEL_ID)
            .setContentTitle("Filament Manager Services")
            .setContentText(content)
            .setSmallIcon(R.drawable.filamentmanagerlogobw)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        notificationManager.notify(SUMMARY_ID, summaryNotification)
    }

    /**
     * Updates the summary notification for stock alerts when multiple trackers are available.
     *
     * @param context The application context.
     * @param count The number of active stock alerts.
     */
    fun updateStockAlertSummary(context: Context, count: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (count <= 1) {
            notificationManager.cancel(STOCK_SUMMARY_ID)
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("TARGET_SCREEN", "AVAILABILITY")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 205, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val summaryNotification = NotificationCompat.Builder(context, STOCK_CHANNEL_ID)
            .setContentTitle("Stock Alerts")
            .setContentText("$count trackers are in stock")
            .setSmallIcon(R.drawable.filamentmanagerlogo)
            .setGroup(STOCK_ALERTS_GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
            
        notificationManager.notify(STOCK_SUMMARY_ID, summaryNotification)
    }
}
