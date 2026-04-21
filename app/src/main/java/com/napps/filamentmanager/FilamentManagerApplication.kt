package com.napps.filamentmanager

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.napps.filamentmanager.database.AppDatabase
import com.napps.filamentmanager.database.BambuRepository
import com.napps.filamentmanager.database.FilamentInventoryRepository
import com.napps.filamentmanager.database.InventoryLimitRepository
import com.napps.filamentmanager.database.UserPreferencesRepository
import com.napps.filamentmanager.database.VendorFilamentsRepository
import com.napps.filamentmanager.mqtt.BambuUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Main application class for the Filament Manager.
 * 
 * Responsibilities:
 * 1. Dependency Management: Lazy initialization of Repositories and Daos.
 * 2. Background Task Scheduling: Enqueues [BambuUpdateWorker] for periodic printer sync.
 * 3. Lifecycle Hooks: Initializes background workers early.
 */
class FilamentManagerApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())

    val database by lazy { AppDatabase.getDatabase(this) }
    val userPreferencesRepository by lazy { UserPreferencesRepository(this) }
    val vendorFilamentRepository by lazy { VendorFilamentsRepository(database.vendorFilamentsDao()) }
    val filamentInventoryRepository by lazy { FilamentInventoryRepository(database.filamentInventoryDao(), this) }
    val bambuRepository by lazy { BambuRepository(database.bambuDao(), database.filamentInventoryDao(), userPreferencesRepository, this) }
    val inventoryLimitRepository by lazy { 
        InventoryLimitRepository(database.inventoryLimitDao(), database.vendorFilamentsDao(), this)
    }

    override fun onCreate() {
        super.onCreate()
        
        // Schedule initial updates and check for pre-populated DB
        applicationScope.launch {
            val userPrefs = userPreferencesRepository
            
            // If the DB has filaments but the flag is false, it means we just pre-populated!
            val hasFilaments = database.vendorFilamentsDao().hasAnyFilamentsStatic()
            val syncFinished = userPrefs.hasFirstSyncFinishedFlow.first()
            
            if (hasFilaments && !syncFinished) {
                userPrefs.setFirstSyncFinished(true)
            }

            val interval = userPrefs.printerUpdateIntervalFlow.first()
            scheduleBambuUpdates(interval)
        }
    }

    /**
     * Schedules periodic printer status updates via [BambuUpdateWorker].
     *
     * @param intervalMinutes The desired frequency of updates. WorkManager enforces a minimum of 15 minutes.
     */
    fun scheduleBambuUpdates(intervalMinutes: Int = 15) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val repeatingRequest = PeriodicWorkRequestBuilder<BambuUpdateWorker>(
            intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "BambuUpdateWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            repeatingRequest
        )
    }
}
