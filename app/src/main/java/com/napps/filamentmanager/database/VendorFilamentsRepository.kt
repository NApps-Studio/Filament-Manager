package com.napps.filamentmanager.database

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource

/**
 * Repository for managing the vendor filament database and availability trackers.
 * Handles the logic for filtering the global filament catalog and synchronizing stock trackers.
 */
class VendorFilamentsRepository(private val vendorFilamentsDao: VendorFilamentsDao) {

    /**
     * Inserts human-readable menu text for availability statuses.
     */
    suspend fun insert(availabilityMenuText: AvailabilityMenuText) {
        vendorFilamentsDao.insert(availabilityMenuText)
    }

    /**
     * Retrieves the display text for a specific availability status.
     */
    fun getMenuText(name:String): LiveData<AvailabilityMenuText> {
        return vendorFilamentsDao.getMenuText(name)
    }

    /**
     * Retrieves the display text for a specific availability status statically.
     */
    suspend fun getMenuTextStatic(name: String): AvailabilityMenuText? {
        return vendorFilamentsDao.getMenuTextStatic(name)
    }

    /**
     * Updates existing availability menu text.
     */
    suspend fun update(availabilityMenuText: AvailabilityMenuText) {
        vendorFilamentsDao.update(availabilityMenuText)
    }

    // --- Filter Option Queries (LiveData) ---

    /**
     * Retrieves a list of unique brand names currently present in the filtered catalog.
     * Used to populate filter chips or dropdowns in the UI.
     */
    fun getUniqueBrands(
        searchText: String,
        type: String?,
        colorInfo: ColorInfo?,
        packageType: String?,
        weight: String?
    ): LiveData<List<String>> = vendorFilamentsDao.getUniqueBrands(searchText,  type, colorInfo?.colorName, packageType, weight)

    /**
     * Retrieves a list of unique material types (e.g., PLA, PETG) currently present in the filtered catalog.
     */
    fun getUniqueTypes(
        searchText: String,
        brand: String?,
        colorInfo: ColorInfo?,
        packageType: String?,
        weight: String?
    ): LiveData<List<String>> = vendorFilamentsDao.getUniqueTypes(searchText, brand,  colorInfo?.colorName, packageType, weight)

    /**
     * Retrieves a list of unique color names and their metadata currently present in the filtered catalog.
     */
    fun getUniqueColors(
        searchText: String,
        brand: String?,
        type: String?,
        packageType: String?,
        weight: String?
    ): LiveData<List<ColorInfo>> = vendorFilamentsDao.getUniqueColors(searchText, brand, type, packageType, weight)

    /**
     * Retrieves a list of unique package types (e.g., Spool, Refill) currently present in the filtered catalog.
     */
    fun getUniquePackageTypes(
        searchText: String,
        brand: String?,
        type: String?,
        colorInfo: ColorInfo?,
        weight: String?
    ): LiveData<List<String>> = vendorFilamentsDao.getUniquePackageTypes(searchText, brand, type, colorInfo?.colorName,  weight)

    /**
     * Retrieves a list of unique spool weights currently present in the filtered catalog.
     */
    fun getUniqueWeights(
        searchText: String,
        brand: String?,
        type: String?,
        colorInfo: ColorInfo?,
        packageType: String?
    ): LiveData<List<String>> = vendorFilamentsDao.getUniqueWeights(searchText, brand, type, colorInfo?.colorName, packageType)

    /**
     * Observable list of filaments currently in stock at the vendor.
     */
    val availableFilaments: LiveData<List<VendorFilament>> = vendorFilamentsDao.getAvailable()

    /**
     * Observable list of filaments currently out of stock at the vendor.
     */
    val unavailableFilaments: LiveData<List<VendorFilament>> = vendorFilamentsDao.getUnavailable()

    /**
     * Provides a paged flow of vendor filaments based on complex multi-parameter filters.
     */
    fun getFilteredFilamentListDataPaging(
        searchText: String,
        brand: String?,
        type: String?,
        colorInfo: ColorInfo?,
        packageType: String?,
        weight: String?
    ): PagingSource<Int, VendorFilament> {
        return vendorFilamentsDao.getFilteredFilamentListDataPaging(searchText, brand, type, colorInfo?.colorName, packageType, weight)
    }

    /**
     * Retrieves all trackers that have notifications enabled.
     */
    fun getAllTrackersWithNotifications(): LiveData<List<TrackerWithFilaments>> {
        return vendorFilamentsDao.getAllTrackersWithNotifications()
    }

    /**
     * Retrieves all trackers that have notifications enabled statically.
     */
    suspend fun getAllTrackersWithNotificationsStatic(): List<TrackerWithFilaments>{
        return vendorFilamentsDao.getAllTrackersWithNotificationsStatic()
    }

    /**
     * Adds a new vendor filament to the database.
     */
    suspend fun insert(vendorFilament: VendorFilament) {
        vendorFilamentsDao.insert(vendorFilament)
    }

    /**
     * Deletes a vendor filament from the database.
     */
    suspend fun delete(vendorFilament: VendorFilament) {
        vendorFilamentsDao.delete(vendorFilament)
    }

    /**
     * Updates an existing vendor filament record.
     */
    suspend fun update(vendorFilament: VendorFilament) {
        vendorFilamentsDao.update(vendorFilament)
    }

    /**
     * Retrieves filaments filtered by availability status code.
     */
    fun getByStatus(status: Int): LiveData<List<VendorFilament>> {
        return vendorFilamentsDao.getByStatus(status)
    }

    /**
     * Retrieves filaments with specific error flags (e.g., scraping errors).
     */
    fun getByError(error: Int): LiveData<List<VendorFilament>> {
        return vendorFilamentsDao.getByError(error)
    }

    /**
     * Retrieves all vendor filaments in the catalog.
     */
    val getAllVendorFilaments: LiveData<List<VendorFilament>> = vendorFilamentsDao.getAllVendorFilaments()

    /**
     * Efficiently checks if the vendor catalog has any entries.
     */
    val hasAnyFilaments: LiveData<Boolean> = vendorFilamentsDao.hasAnyFilaments()

    /**
     * Retrieves all vendor filaments statically.
     */
    suspend fun getAllVendorFilamentsStatic(): List<VendorFilament> {
        return vendorFilamentsDao.getAllVendorFilamentsStatic()
    }

    // --- Availability Tracker Management ---

    /**
     * Inserts a new availability tracker.
     */
    suspend fun insert(availabilityTracker: AvailabilityTracker) {
        vendorFilamentsDao.insert(availabilityTracker)
    }

    /**
     * Deletes an availability tracker.
     */
    suspend fun delete(availabilityTracker: AvailabilityTracker) {
        vendorFilamentsDao.delete(availabilityTracker)
    }

    /**
     * Updates an existing availability tracker.
     */
    suspend fun update(availabilityTracker: AvailabilityTracker) {
        vendorFilamentsDao.update(availabilityTracker)
    }

    /**
     * Inserts a new filament or updates an existing one if it exists.
     */
    suspend fun insertOrUpdate(filament: VendorFilament) {
        vendorFilamentsDao.insertOrUpdate(filament)
    }

    /**
     * Retrieves all availability trackers including their associated filaments.
     */
    fun getAllAvailabilityTrackers(): LiveData<List<TrackerWithFilaments>> {
        return vendorFilamentsDao.getAllAvailabilityTrackers()
    }

    /**
     * Retrieves all availability trackers statically.
     */
    suspend fun getAllAvailabilityTrackersStatic(): List<TrackerWithFilaments> {
        return vendorFilamentsDao.getAllAvailabilityTrackersStatic()
    }

    /**
     * Creates a new tracker and links it to a list of filaments.
     */
    suspend fun insertTrackerWithFilaments (tracker: AvailabilityTracker, filamentIds: List<Int>) {
        vendorFilamentsDao.insertTrackerWithFilaments(tracker, filamentIds)
    }

    /**
     * Updates an existing tracker and updates its linked filaments.
     */
    suspend fun updateTrackerWithFilaments (tracker: TrackerWithFilaments, filamentIds: List<Int>) {
        vendorFilamentsDao.updateTrackerWithFilaments(tracker, filamentIds)
    }

    /**
     * Links a specific filament to an existing tracker.
     */
    suspend fun addFilamentToTracker(trackerId: Int, filamentId: Int) {
        vendorFilamentsDao.insertCrossRef(TrackerFilamentCrossRef(trackerId, filamentId))
    }

    /**
     * Removes a specific filament from a tracker's observation list.
     */
    suspend fun removeFilamentFromTracker(trackerId: Int, filamentId: Int) {
        vendorFilamentsDao.deleteCrossRef(TrackerFilamentCrossRef(trackerId, filamentId))
    }

    /**
     * Finds a vendor filament by its unique SKU.
     */
    suspend fun getFilamentBySkuStatic(sku: String): VendorFilament? {
        return vendorFilamentsDao.getFilamentBySku(sku)
    }

    /**
     * Import trackers from a robust export format.
     * Matches incoming filaments by SKU or a combination of properties (brand, type, color, package).
     *
     * @param incomingTrackers List of trackers and their associated filament property maps.
     * @param replaceDuplicates If true, existing trackers with the same name will be overwritten.
     * @param onComplete Callback invoked with the total number of successfully imported trackers.
     */
    suspend fun importRobustTrackers(
        incomingTrackers: List<Pair<Map<String, String>, List<Map<String, String>>>>,
        replaceDuplicates: Boolean,
        onComplete: (Int) -> Unit
    ) {
        val existingTrackers = vendorFilamentsDao.getAllAvailabilityTrackersStatic()
        val allVendorFilaments = vendorFilamentsDao.getAllVendorFilamentsStatic()
        var importedCount = 0
        
        incomingTrackers.forEach { (map, filamentsInfo) ->
            val name = map["name"] ?: return@forEach
            // Skip "Limit Service" trackers - they are managed by the Inventory Limits system
            if (name.startsWith("Limit Service: ")) return@forEach
            
            val existingMatch = existingTrackers.find { it.tracker.name == name }
            
            // Resolve local filament IDs by matching properties across the local database
            val resolvedFilamentIds = filamentsInfo.mapNotNull { info ->
                val sku = info["sku"]
                val brand = info["brand"]
                val type = info["type"]
                val colorName = info["colorName"]
                val packageType = info["packageType"]
                
                // Priority: match by SKU first, then by physical properties
                allVendorFilaments.find { it.sku == sku && !sku.isNullOrBlank() }?.id
                    ?: allVendorFilaments.find { 
                        it.brand == brand && it.type == type && 
                        it.colorName == colorName && it.packageType == packageType
                    }?.id
            }

            if (existingMatch != null) {
                if (replaceDuplicates) {
                    val tracker = existingMatch.tracker.copy(
                        notificationEnabled = map["notificationEnabled"]?.toBoolean() ?: existingMatch.tracker.notificationEnabled
                    )
                    updateTrackerWithFilaments(TrackerWithFilaments(tracker, emptyList()), resolvedFilamentIds)
                    importedCount++
                }
            } else {
                val tracker = AvailabilityTracker(
                    name = name,
                    notificationEnabled = map["notificationEnabled"]?.toBoolean() ?: true
                )
                insertTrackerWithFilaments(tracker, resolvedFilamentIds)
                importedCount++
            }
        }
        onComplete(importedCount)
    }
}
