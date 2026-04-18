package com.napps.filamentmanager.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Main Room database for the Filament Manager application.
 * Manages tables for vendor filaments, availability trackers, filament inventory,
 * and printer connectivity metadata.
 */
@Database(
    entities = [VendorFilament::class, AvailabilityTracker::class, TrackerFilamentCrossRef::class,
        AvailabilityMenuText::class, FilamentInventory::class, ColorCrossRef::class,
        BambuLab::class, BambuReportEntity::class, BambuPrinter::class, InventoryLimit::class, LimitFilamentCrossRef::class,
        PotentialOutOfStock::class, AmsSlotHistory::class, UnmappedFilament::class],
    autoMigrations = [],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    /** Provides access to vendor filament metadata and availability trackers. */
    abstract fun vendorFilamentsDao(): VendorFilamentsDao
    /** Provides access to the user's manual and RFID-based filament inventory. */
    abstract fun filamentInventoryDao(): FilamentInventoryDAO
    /** Provides access to Bambu Lab account and printer telemetry data. */
    abstract fun bambuDao(): BambuDao
    /** Provides access to user-defined inventory limits and low-stock tracking. */
    abstract fun inventoryLimitDao(): InventoryLimitDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "filament_database"
                )
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
