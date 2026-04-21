package com.napps.filamentmanager.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Main Room database for the Filament Manager application.
 * Manages tables for vendor filaments, availability trackers, filament inventory,
 * and printer connectivity metadata.
 */
@Database(
    entities = [VendorFilament::class, AvailabilityTracker::class, TrackerFilamentCrossRef::class,
        AvailabilityMenuText::class, FilamentInventory::class, ColorCrossRef::class,
        BambuLab::class, BambuReportEntity::class, BambuPrinter::class, InventoryLimit::class, LimitFilamentCrossRef::class,
        PotentialOutOfStock::class, AmsSlotHistory::class, UnmappedFilament::class, SyncReport::class],
    autoMigrations = [],
    version = 5,
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
    /** Provides access to sync failure reports. */
    abstract fun syncReportDao(): SyncReportDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_reports` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL, " +
                            "`pageUrl` TEXT NOT NULL, " +
                            "`reason` TEXT NOT NULL, " +
                            "`affectedVariants` INTEGER NOT NULL, " +
                            "`isRead` INTEGER NOT NULL, " +
                            "`syncType` TEXT NOT NULL)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add the isError column that was missing in the original version 2
                db.execSQL("ALTER TABLE `sync_reports` ADD COLUMN `isError` INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop and recreate table to match the new combined report structure
                db.execSQL("DROP TABLE IF EXISTS `sync_reports`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_reports` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL, " +
                            "`syncType` TEXT NOT NULL, " +
                            "`summary` TEXT NOT NULL, " +
                            "`details` TEXT, " +
                            "`affectedVariants` INTEGER NOT NULL, " +
                            "`errorCount` INTEGER NOT NULL, " +
                            "`isRead` INTEGER NOT NULL, " +
                            "`isError` INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_reports` ADD COLUMN `syncedContent` TEXT")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "filament_database"
                )
                    .createFromAsset("Prefiled_database")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
