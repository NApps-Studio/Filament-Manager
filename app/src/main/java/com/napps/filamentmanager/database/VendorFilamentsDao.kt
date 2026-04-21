package com.napps.filamentmanager.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.paging.PagingSource

/**
 * Data Access Object for the vendor filament catalog and availability trackers.
 * Handles complex filtering for the global database and subscription-based stock tracking.
 */
@Dao
interface VendorFilamentsDao {

    // --- Availability Menu Text (Status Labels) ---
    
    /** Saves or updates localized display text for an availability status. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(text: AvailabilityMenuText)

    /** Deletes an availability status label. */
    @Delete
    suspend fun delete(text: AvailabilityMenuText)

    /** Updates an availability status label. */
    @Update
    suspend fun update(text: AvailabilityMenuText)

    /** Retrieves the display text for a specific status. */
    @Query("SELECT * FROM availability_menu_text WHERE name = :name LIMIT 1")
    fun getMenuText(name: String): LiveData<AvailabilityMenuText>

    /** Retrieves the display text for a specific status statically. */
    @Query("SELECT * FROM availability_menu_text WHERE name = :name LIMIT 1")
    suspend fun getMenuTextStatic(name: String): AvailabilityMenuText?

    // --- Vendor Filament Catalog ---

    /** Adds a new filament variant to the global catalog. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vendorFilament: VendorFilament)

    /** Removes a filament variant from the catalog. */
    @Delete
    suspend fun delete(vendorFilament: VendorFilament)

    /** Updates an existing filament variant record. */
    @Update
    suspend fun update(vendorFilament: VendorFilament)

    /** Finds a filament by its unique SKU (Stock Keeping Unit). */
    @Query("SELECT * FROM vendor_filaments WHERE sku = :sku LIMIT 1")
    suspend fun getFilamentBySku(sku: String): VendorFilament?

    /**
     * Atomically inserts or updates a filament based on its SKU.
     * Ensures that existing records are updated while preserving their local database IDs.
     */
    @Transaction
    suspend fun insertOrUpdate(filament: VendorFilament) {
        val existingFilament = getFilamentBySku(filament.sku?:"")
        if (existingFilament != null) {
            update(filament.copy(id = existingFilament.id))
        } else {
            insert(filament)
        }
    }

    // --- Dynamic Filter Option Queries ---
    // These queries return unique values for the dropdown filters in the UI,
    // accounting for the current selection in other filters to provide a reactive experience.

    @Query("""SELECT DISTINCT brand FROM vendor_filaments 
        WHERE brand IS NOT NULL AND brand != ''
        AND (type = :type OR :type = 'Any' OR :type IS NULL)
        AND (colorName = :colorName OR :colorName = 'Any' OR :colorName IS NULL)
        AND (packageType = :packageType OR :packageType = 'Any' OR :packageType IS NULL)
        AND (weight = :weight OR :weight = 'Any' OR :weight IS NULL)
        AND (
            :searchText IS NULL OR :searchText = ''
            OR brand LIKE '%' || :searchText || '%'
            OR type LIKE '%' || :searchText || '%'
            OR variantName LIKE '%' || :searchText || '%'
            OR colorName LIKE '%' || :searchText || '%'
            OR sku LIKE '%' || :searchText || '%'
            )ORDER BY brand ASC
    """)
    fun getUniqueBrands(
        searchText: String,
        type: String?,
        colorName: String?,
        packageType: String?,
        weight: String?
    ): LiveData<List<String>>

    @Query("""SELECT DISTINCT type FROM vendor_filaments 
        WHERE type IS NOT NULL AND type != ''
        AND  (brand = :brand OR :brand = 'Any' OR :brand IS NULL)
        AND (colorName = :colorName OR :colorName = 'Any' OR :colorName IS NULL)
        AND (packageType = :packageType OR :packageType = 'Any' OR :packageType IS NULL)
        AND (weight = :weight OR :weight = 'Any' OR :weight IS NULL)
        AND (
            :searchText IS NULL OR :searchText = ''
            OR brand LIKE '%' || :searchText || '%'
            OR type LIKE '%' || :searchText || '%'
            OR variantName LIKE '%' || :searchText || '%'
            OR colorName LIKE '%' || :searchText || '%'
            OR sku LIKE '%' || :searchText || '%'
            )ORDER BY type ASC
        """)
    fun getUniqueTypes(
        searchText: String,
        brand: String?,
        colorName: String?,
        packageType: String?,
        weight: String?
    ): LiveData<List<String>>

    @Query("""SELECT DISTINCT colorName, colorRgb FROM vendor_filaments 
        WHERE colorName IS NOT NULL AND colorName != ''
        AND(brand = :brand OR :brand = 'Any' OR :brand IS NULL)
        AND (type = :type OR :type = 'Any' OR :type IS NULL)
        AND (packageType = :packageType OR :packageType = 'Any' OR :packageType IS NULL)
        AND (weight = :weight OR :weight = 'Any' OR :weight IS NULL)
        AND (
            :searchText IS NULL OR :searchText = ''
            OR brand LIKE '%' || :searchText || '%'
            OR type LIKE '%' || :searchText || '%'
            OR variantName LIKE '%' || :searchText || '%'
            OR colorName LIKE '%' || :searchText || '%'
            OR sku LIKE '%' || :searchText || '%'
            )ORDER BY colorName ASC
    """)
    fun getUniqueColors(
        searchText: String,
        brand: String?,
        type: String?,
        packageType: String?,
        weight: String?
    ): LiveData<List<ColorInfo>>

    @Query("""SELECT DISTINCT packageType FROM vendor_filaments 
        WHERE packageType IS NOT NULL AND packageType != ''
         AND   (brand = :brand OR :brand = 'Any' OR :brand IS NULL)
        AND (type = :type OR :type = 'Any' OR :type IS NULL)
        AND (colorName = :colorName OR :colorName = 'Any' OR :colorName IS NULL)
        AND (weight = :weight OR :weight = 'Any' OR :weight IS NULL)
        AND (
            :searchText IS NULL OR :searchText = ''
            OR brand LIKE '%' || :searchText || '%'
            OR type LIKE '%' || :searchText || '%'
            OR variantName LIKE '%' || :searchText || '%'
            OR colorName LIKE '%' || :searchText || '%'
            OR sku LIKE '%' || :searchText || '%'
            )ORDER BY packageType ASC
        """)
    fun getUniquePackageTypes(
        searchText: String,
        brand: String?,
        type: String?,
        colorName: String?,
        weight: String?
    ): LiveData<List<String>>

    @Query("""SELECT DISTINCT weight FROM vendor_filaments 
        WHERE weight IS NOT NULL
          AND  (brand = :brand OR :brand = 'Any' OR :brand IS NULL)
        AND (type = :type OR :type = 'Any' OR :type IS NULL)
        AND (colorName = :colorName OR :colorName = 'Any' OR :colorName IS NULL)
        AND (packageType = :packageType OR :packageType = 'Any' OR :packageType IS NULL)
        AND (
            :searchText IS NULL OR :searchText = ''
            OR brand LIKE '%' || :searchText || '%'
            OR type LIKE '%' || :searchText || '%'
            OR variantName LIKE '%' || :searchText || '%'
            OR colorName LIKE '%' || :searchText || '%'
            OR sku LIKE '%' || :searchText || '%'
            )ORDER BY weight ASC
    """)
    fun getUniqueWeights(
        searchText: String,
        brand: String?,
        type: String?,
        colorName: String?,
        packageType: String?,
    ): LiveData<List<String>>


    /** Returns all filaments currently marked as In Stock. */
    @Query("SELECT * FROM vendor_filaments WHERE isAvailable = 1")
    fun getAvailable(): LiveData<List<VendorFilament>>

    /** Returns all filaments currently marked as Out of Stock. */
    @Query("SELECT * FROM vendor_filaments WHERE isAvailable = 0")
    fun getUnavailable(): LiveData<List<VendorFilament>>

    /** Returns filaments with a specific scraping status code. */
    @Query("SELECT * FROM vendor_filaments WHERE status = :status")
    fun getByStatus(status: Int): LiveData<List<VendorFilament>>

    /** Returns filaments with a specific error flag. */
    @Query("SELECT * FROM vendor_filaments WHERE error = :error")
    fun getByError(error: Int): LiveData<List<VendorFilament>>

    /**
     * Primary query for the filament database screen.
     * Supports multi-parameter filtering and full-text search.
     */
    @Query("""
        SELECT * FROM vendor_filaments
        WHERE
            (brand = :brand OR :brand = 'Any' OR :brand IS NULL)
        AND (type = :type OR :type = 'Any' OR :type IS NULL)
        AND (colorName = :colorName OR :colorName = 'Any' OR :colorName IS NULL)
        AND (packageType = :packageType OR :packageType = 'Any' OR :packageType IS NULL)
        AND (weight = :weight OR :weight = 'Any' OR :weight IS NULL)
        AND (
            :searchText IS NULL OR :searchText = ''
            OR brand LIKE '%' || :searchText || '%'
            OR type LIKE '%' || :searchText || '%'
            OR variantName LIKE '%' || :searchText || '%'
            OR colorName LIKE '%' || :searchText || '%'
            OR sku LIKE '%' || :searchText || '%'
            ) ORDER BY brand, type, colorName, packageType
    """)
    fun getFilteredFilamentListData(
        searchText: String,
        brand: String?,
        type: String?,
        colorName: String?,
        packageType: String?,
        weight: String?
    ): LiveData<List<VendorFilament>>

    /** Paging-ready version of the filtered filament query. */
    @Query("""
        SELECT * FROM vendor_filaments
        WHERE
            (brand = :brand OR :brand = 'Any' OR :brand IS NULL)
        AND (type = :type OR :type = 'Any' OR :type IS NULL)
        AND (colorName = :colorName OR :colorName = 'Any' OR :colorName IS NULL)
        AND (packageType = :packageType OR :packageType = 'Any' OR :packageType IS NULL)
        AND (weight = :weight OR :weight = 'Any' OR :weight IS NULL)
        AND (
            :searchText IS NULL OR :searchText = ''
            OR brand LIKE '%' || :searchText || '%'
            OR type LIKE '%' || :searchText || '%'
            OR variantName LIKE '%' || :searchText || '%'
            OR colorName LIKE '%' || :searchText || '%'
            OR sku LIKE '%' || :searchText || '%'
            )ORDER BY brand, type, colorName, packageType
    """)
    fun getFilteredFilamentListDataPaging(
        searchText: String,
        brand: String?,
        type: String?,
        colorName: String?,
        packageType: String?,
        weight: String?
    ): PagingSource<Int, VendorFilament>

    /** Returns the entire vendor catalog. */
    @Query("SELECT * FROM vendor_filaments")
    fun getAllVendorFilaments(): LiveData<List<VendorFilament>>

    /** Returns the entire vendor catalog statically. */
    @Query("SELECT * FROM vendor_filaments")
    suspend fun getAllVendorFilamentsStatic(): List<VendorFilament>

    /**
     * Efficiently checks if the vendor catalog has any entries.
     * @return LiveData containing true if at least one filament exists.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM vendor_filaments)")
    fun hasAnyFilaments(): LiveData<Boolean>

    /**
     * Efficiently checks if the vendor catalog has any entries (Suspend version).
     */
    @Query("SELECT EXISTS(SELECT 1 FROM vendor_filaments)")
    suspend fun hasAnyFilamentsStatic(): Boolean

    /** Clears the entire vendor catalog. */
    @Query("DELETE FROM vendor_filaments")
    suspend fun deleteAllFilaments()

    /** Clears all availability trackers. */
    @Query("DELETE FROM availability_trackers")
    suspend fun deleteAllTrackers()

    // --- Availability Trackers (Subscriptions) ---

    /** Retrieves all trackers that have notifications enabled. */
    @Transaction
    @Query("SELECT * FROM availability_trackers WHERE notificationEnabled = 1")
    fun getAllTrackersWithNotifications(): LiveData<List<TrackerWithFilaments>>

    /** Retrieves all trackers that have notifications enabled statically. */
    @Transaction
    @Query("SELECT * FROM availability_trackers WHERE notificationEnabled = 1")
    suspend fun getAllTrackersWithNotificationsStatic(): List<TrackerWithFilaments>

    /**
     * Finds trackers where *all* associated filaments are currently available.
     * Used by the background service to trigger "Back in Stock" notifications.
     */
    @Transaction
    @Query("""
    SELECT * FROM availability_trackers t
    WHERE notificationEnabled = 1 AND NOT EXISTS (
        SELECT 1 FROM tracker_filament_cross_ref xr
        JOIN vendor_filaments f ON xr.filamentId = f.id
        WHERE xr.trackerId = t.id AND f.isAvailable = 0
    )
    AND EXISTS (SELECT 1 FROM tracker_filament_cross_ref xr WHERE xr.trackerId = t.id)
""")
    suspend fun getFullyAvailableTrackersStatic(): List<TrackerWithFilaments>

    /** Adds a new availability tracker. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(availabilityTracker: AvailabilityTracker)

    /** Removes an availability tracker. */
    @Delete
    suspend fun delete(availabilityTracker: AvailabilityTracker)

    /** Updates tracker metadata. */
    @Update
    suspend fun update(availabilityTracker: AvailabilityTracker)

    /** Returns all trackers including their associated filaments. */
    @Transaction
    @Query("SELECT * FROM availability_trackers")
    fun getAllAvailabilityTrackers(): LiveData<List<TrackerWithFilaments>>

    /** Returns all trackers including their associated filaments statically. */
    @Transaction
    @Query("SELECT * FROM availability_trackers")
    suspend fun getAllAvailabilityTrackersStatic(): List<TrackerWithFilaments>

    /** Inserts a tracker and returns its generated ID. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracker(tracker: AvailabilityTracker): Long

    /**
     * Atomically creates a tracker and links it to a list of filament IDs.
     */
    @Transaction
    suspend fun insertTrackerWithFilaments(tracker: AvailabilityTracker, filamentIds: List<Int>) {
        val newTrackerId = insertTracker(tracker).toInt()
        filamentIds.forEach { filId ->
            insertCrossRef(TrackerFilamentCrossRef(trackerId = newTrackerId, filamentId = filId))
        }
    }

    /**
     * Updates a tracker's metadata and its linked filament associations.
     */
    @Transaction
    suspend fun updateTrackerWithFilaments(tracker: TrackerWithFilaments, filamentIds: List<Int>) {
        update(tracker.tracker)
        tracker.filaments.forEach { fil ->
            deleteCrossRef(TrackerFilamentCrossRef(tracker.tracker.id, fil.id))
        }
        filamentIds.forEach { filId ->
            insertCrossRef(TrackerFilamentCrossRef(tracker.tracker.id, filId))
        }
    }

    /** Links a filament to a tracker. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: TrackerFilamentCrossRef)

    /** Unlinks a filament from a tracker. */
    @Delete
    suspend fun deleteCrossRef(crossRef: TrackerFilamentCrossRef)

}
