package com.napps.filamentmanager.mqtt

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.napps.filamentmanager.FilamentManagerApplication
import com.napps.filamentmanager.R
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.napps.filamentmanager.util.CryptoManager
import kotlinx.coroutines.*

/**
 * WorkManager worker that periodically synchronizes the printer's current
 * state (including AMS inventory) with the local database.
 *
 * This worker connects to the Bambu Lab cloud MQTT broker, decrypts the
 * necessary authentication tokens, and waits for a full telemetry report.
 */
class BambuUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BambuWorker"
    }

    /**
     * Executes the printer synchronization task.
     * 1. Retrieves and decrypts the user's Bambu account credentials.
     * 2. Iterates through all registered printers.
     * 3. For each printer, establishes an MQTT connection and requests a full state update.
     * 4. Parses the received JSON and updates the [FilamentInventory] and [AmsUnitReport] in the database.
     * 5. Enforces a 60-second timeout per printer to prevent the worker from hanging.
     */
    override suspend fun doWork(): Result = coroutineScope {
        val app = applicationContext as FilamentManagerApplication
        val repository = app.bambuRepository
        val workerStartTime = System.currentTimeMillis()
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        
        Log.d(TAG, "Worker triggered at ${sdf.format(java.util.Date(workerStartTime))}")
        
        val auth = repository.getBambuAuth() ?: run {
            Log.w(TAG, "Sync skipped: No Bambu account found.")
            return@coroutineScope Result.success()
        }
        
        val printers = repository.getAllPrinters()
        if (printers.isEmpty()) {
            Log.w(TAG, "Sync skipped: No printers registered.")
            return@coroutineScope Result.success()
        }

        val token = try {
            CryptoManager.decrypt(auth.encryptedToken, auth.iv)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt access token: ${e.message}")
            return@coroutineScope Result.failure()
        }
        
        val uid = auth.uid
        val host = if (auth.isInChina) "cn.mqtt.bambulab.com" else "us.mqtt.bambulab.com"

        val jobs = printers.map { printer ->
            async(Dispatchers.IO) {
                val rawSerial = try {
                    CryptoManager.decrypt(printer.encryptedSerial, printer.iv)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt serial for ${printer.name}")
                    return@async
                }

                val maskedSn = if (rawSerial.length > 8) "${rawSerial.take(4)}...${rawSerial.takeLast(4)}" else rawSerial
                Log.d(TAG, "Starting sync for: ${printer.name} [$maskedSn]")

                val updateFinished = CompletableDeferred<Unit>()
                var isJobDone = false 
                var hasReceivedAnyData = false

                val manager = BambuMqttManager(
                    context = applicationContext,
                    host = host,
                    userName = uid,
                    accessToken = token,
                    serialNumber = rawSerial,
                    onStateUpdate = { newState, rawJson ->
                        if (!isJobDone) {
                            val hasData = rawJson != "{}" && rawJson.isNotBlank()
                            if (hasData) {
                                hasReceivedAnyData = true
                                
                                val isAmsReport = rawJson.contains("\"ams\":")
                                
                                // Save any data received to update DB. 
                                // workerSyncTime is ONLY updated if we got a real AMS report (successful sync).
                                this@coroutineScope.launch(Dispatchers.IO) {
                                    repository.savePrinterStatus(
                                        hashedSn = printer.hashedSerial,
                                        rawJson = rawJson, 
                                        rawSn = rawSerial,
                                        workerSyncTime = if (isAmsReport) workerStartTime else null,
                                        error = null // Clear any previous error on successful data receipt
                                    )
                                    
                                    // Always sync inventory state, but only trigger worker if it's a real AMS report
                                    repository.syncAmsWithInventory(newState, isAmsReport = isAmsReport)
                                    
                                    if (isAmsReport) {
                                        Log.d(TAG, "AMS/Inventory report received for ${printer.name}. Sync complete.")
                                        isJobDone = true 
                                        updateFinished.complete(Unit)
                                    }
                                }
                            }
                        }
                    },
                    onConnectionError = { error ->
                        if (!isJobDone) {
                            val errorMsg = when {
                                error.contains("not authorized", ignoreCase = true) || 
                                error.contains("auth", ignoreCase = true) ||
                                error.contains("401") || error.contains("403") -> "Authentication Failed (Check Token)"
                                
                                error.contains("timeout", ignoreCase = true) -> "Connection Timeout (Printer Offline?)"
                                error.contains("host", ignoreCase = true) || error.contains("network", ignoreCase = true) -> "Network Error (No Internet?)"
                                else -> "Sync Error: $error"
                            }

                            Log.e(TAG, "$errorMsg for ${printer.name}")
                            this@coroutineScope.launch(Dispatchers.IO) {
                                repository.savePrinterStatus(
                                    hashedSn = printer.hashedSerial,
                                    rawJson = "{}",
                                    rawSn = rawSerial,
                                    error = errorMsg
                                )
                            }
                            updateFinished.complete(Unit)
                        }
                    }
                )

                manager.connect()
                
                try {
                    // Maximum wait time for the report
                    withTimeout(60_000) {
                        updateFinished.await()
                    }
                } catch (e: TimeoutCancellationException) {
                    val errorMsg = if (hasReceivedAnyData) "Partial Sync (AMS Data Missing)" else "Timed Out (No Response from Printer)"
                    Log.w(TAG, "$errorMsg from ${printer.name}")
                    this@coroutineScope.launch(Dispatchers.IO) {
                        repository.savePrinterStatus(
                            hashedSn = printer.hashedSerial,
                            rawJson = "{}",
                            rawSn = rawSerial,
                            error = errorMsg
                        )
                    }
                } finally {
                    isJobDone = true
                    manager.disconnect()
                }
            }
        }

        jobs.awaitAll()
        Log.d(TAG, "All printer sync tasks finished.")
        Result.success()
    }

    /**
     * Creates and returns the notification used when the worker is running in the foreground.
     * Required for long-running WorkManager tasks to avoid system termination.
     *
     * @return A [ForegroundInfo] object containing the notification and its ID.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "bambu_update_channel"
        
        val channel = NotificationChannel(
            channelId, "Printer Sync", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Status of printer state synchronization"
        }
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Bambu Filament Manager")
            .setContentText("Syncing printer status...")
            .setSmallIcon(R.drawable.filamentmanagerlogo)
            .setOngoing(true)
            .build()

        return ForegroundInfo(1004, notification)
    }
}
