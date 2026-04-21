package com.napps.filamentmanager.database

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository for managing the user's personal filament inventory.
 * Provides abstraction for inventory operations, filtering, and paging.
 */
class FilamentInventoryRepository(
    private val filamentInventoryDao: FilamentInventoryDAO,
    private val context: Context
) {

    /**
     * Finds the human-readable color name for a specific filament variant.
     *
     * @param brand The filament brand (e.g., "Bambu Lab").
     * @param type The material type (e.g., "PLA").
     * @param variantId The specific material variant ID from the RFID tag.
     * @param tagColorRgb The raw RGB color value read from the RFID tag.
     * @return The color name if found in the [ColorCrossRef] table, null otherwise.
     */
    suspend fun getColorName(brand: String, type: String, variantId: String, tagColorRgb: Int): String? {
        return filamentInventoryDao.getColorName(brand, type, variantId, tagColorRgb)
    }

    /**
     * Finds a color mapping by the tag's raw RGB value.
     */
    suspend fun getColorCrossRefBytagColorRgb(brand: String, type: String, tagColorRgb: Int): ColorCrossRef? {
        return filamentInventoryDao.getColorCrossRefByTagColorRgb(brand, type, tagColorRgb)
    }

    /**
     * Finds a color mapping by the mapped display color RGB value.
     */
    suspend fun getColorCrossRefByColorRgb(brand: String, type: String, ColorRgb: Int): ColorCrossRef? {
        return filamentInventoryDao.getColorCrossRefByColorRgb(brand, type, ColorRgb)
    }


    /**
     * Inserts a mapping between a vendor filament and a specific color hex code.
     * After insertion, it attempts to resolve any existing [UnmappedFilament] records
     * that might now match this new mapping.
     */
    suspend fun insertMapping(mapping: ColorCrossRef) {
        filamentInventoryDao.insertMapping(mapping)
        // Refresh all unmapped filaments to see if this mapping resolves any of them
        val unmapped = filamentInventoryDao.getUnmappedFilamentsStatic()
        unmapped.forEach { u ->
            val filament = filamentInventoryDao.getFilamentByIdStatic(u.filamentId) ?: return@forEach
            
            val matchByVariant = u.materialVariantID == mapping.materialVariantID && 
                                 filament.brand == mapping.brand && 
                                 filament.type == mapping.type &&
                                 filament.tagColorRgb == mapping.tagColorRgb
            
            if (matchByVariant) {
                filamentInventoryDao.removeUnmapped(u.filamentId)
            }
        }
    }

    /**
     * Adds a new filament spool to the inventory.
     * Triggers the [InventoryLimitWorker] to re-evaluate stock thresholds.
     */
    suspend fun insert(filament: FilamentInventory): Long {
        return filamentInventoryDao.insert(filament, context)
    }

    /**
     * Removes a filament spool from the inventory and updates limits.
     */
    suspend fun delete(filament: FilamentInventory) {
        filamentInventoryDao.delete(filament, context)
    }

    /**
     * Updates an existing filament spool's properties and re-evaluates limits.
     */
    suspend fun update(filament: FilamentInventory) {
        filamentInventoryDao.update(filament, context)
    }

    /**
     * Retrieves all filament spools in the inventory as LiveData.
     */
    fun getAllFilaments(): LiveData<List<FilamentInventory>> {
        return filamentInventoryDao.getAllFilaments()
    }

    /**
     * Retrieves all filament spools in the inventory as a static list.
     */
    suspend fun getAllFilamentsStatic(): List<FilamentInventory> {
        return filamentInventoryDao.getAllFilamentsStatic()
    }

    /**
     * Counts the number of spools for a specific brand.
     */
    suspend fun countByBrand(brand: String): Int {
        return filamentInventoryDao.countByBrand(brand)
    }

    /**
     * Deletes all filament spools belonging to a specific brand.
     */
    suspend fun deleteByBrand(brand: String) {
        filamentInventoryDao.deleteByBrand(brand)
    }

    /**
     * Returns a list of IDs for all spools that are not marked as Out of Stock.
     */
    suspend fun getActiveIds(): List<Int> {
        return filamentInventoryDao.getActiveIds()
    }

    /**
     * Inserts a new spool or updates an existing one if the ID matches.
     * Used during sync operations where the record may already exist.
     */
    suspend fun insertOrUpdate(filament: FilamentInventory) {
        filamentInventoryDao.insertOrUpdate(filament, context)
    }

    /**
     * Retrieves filaments filtered by their availability status (e.g., In Stock, Low Stock).
     */
    fun getAllByAvailabilityStatus(status: List<Int>): LiveData<List<FilamentInventory>> {
        return filamentInventoryDao.getAllByAvailabilityStatus(status)
    }

    /**
     * Marks a filament as potentially out of stock based on weight thresholds.
     */
    suspend fun markPotentialOutOfStock(filamentId: Int) {
        filamentInventoryDao.markPotentialOutOfStock(PotentialOutOfStock(filamentId))
    }

    /**
     * Removes a filament from the potential out of stock tracking.
     */
    suspend fun removePotentialOutOfStock(filamentId: Int) {
        filamentInventoryDao.removePotentialOutOfStock(filamentId)
    }

    /**
     * Provides a flow of filaments that have been flagged as potentially low on material.
     */
    fun getPotentialOutOfStockFlow(): Flow<List<PotentialOutOfStock>> {
        return filamentInventoryDao.getPotentialOutOfStockFlow()
    }

    /**
     * Finds a filament spool associated with a specific NFC tag (Tray UID).
     */
    fun getFilamentByTrayUid(trayUid: String): LiveData<FilamentInventory> {
        return filamentInventoryDao.getFilamentByTrayUid(trayUid)
    }

    /**
     * Finds a filament spool associated with a specific NFC tag (Tray UID) statically.
     */
    suspend fun getFilamentByTrayUidStatic(trayUid: String): FilamentInventory? {
        return filamentInventoryDao.getFilamentByTrayUidStatic(trayUid)
    }

    /**
     * Provides a paged flow of filaments filtered by status for UI lists.
     */
    fun getFilamentsPaged(statuses: List<Int>): Flow<PagingData<FilamentInventory>> {
        return Pager(
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false,
                prefetchDistance = 5
            ),
            pagingSourceFactory = { filamentInventoryDao.getAllByAvailabilityStatusPaging(statuses) }
        ).flow
    }

    private fun <T> List<T>?.orNull(): List<T>? = if (this.isNullOrEmpty()) null else this

    /**
     * Provides a paged flow of filaments based on complex multi-parameter filters.
     * Handles null/empty lists by converting them to null for Room's @Query compatibility.
     */
    fun getFilteredFilamentsPaged(f: FilamentFilters): Flow<PagingData<FilamentInventory>> {
        return Pager(
            config = PagingConfig(pageSize = 20, enablePlaceholders = false),
            pagingSourceFactory = {
                filamentInventoryDao.getFilteredFilaments(
                    brands = f.brands.orNull(),
                    types = f.types.orNull(),
                    availability = f.availability.orNull(),
                    colors = f.colors.orNull(),
                    weights = f.weights.orNull()
                )
            }
        ).flow
    }

    // --- Filter Option Queries ---

    fun getBrandOptions(f: FilamentFilters) = filamentInventoryDao.getUniqueBrands(
        f.types.orNull(), f.availability.orNull(), f.colors.orNull(), f.weights.orNull()
    )

    fun getTypeOptions(f: FilamentFilters) = filamentInventoryDao.getUniqueTypes(
        f.brands.orNull(), f.availability.orNull(), f.colors.orNull(), f.weights.orNull()
    )

    fun getColorOptions(f: FilamentFilters) = filamentInventoryDao.getUniqueColors(
        f.brands.orNull(), f.types.orNull(), f.availability.orNull(), f.weights.orNull()
    )

    fun getWeightOptions(f: FilamentFilters) = filamentInventoryDao.getUniqueWeights(
        f.brands.orNull(), f.types.orNull(), f.availability.orNull(), f.colors.orNull()
    )

    fun getAvailabilityOptions(f: FilamentFilters) = filamentInventoryDao.getUniqueAvailability(
        f.brands.orNull(), f.types.orNull(), f.colors.orNull(), f.weights.orNull()
    )

    /**
     * Finds inventory spools that match the characteristics of a vendor filament.
     * Used for identifying which physical spools are currently being tracked for a specific stock limit.
     */
    fun getMatchingInventorySpools(brand: String, type: String, colorName: String): LiveData<List<FilamentInventory>> {
        return filamentInventoryDao.getMatchingInventorySpools(brand, type, colorName)
    }

    // --- Unmapped Filaments Section ---

    /**
     * Provides a static list of filaments that are missing color or material mapping information.
     */
    suspend fun getUnmappedFilamentsStatic(): List<UnmappedFilament> = withContext(Dispatchers.IO) {
        filamentInventoryDao.getUnmappedFilamentsStatic()
    }

    /**
     * Retrieves a color cross-reference by brand, type, variant, and tag color.
     */
    suspend fun getColorCrossRef(brand: String, type: String, variantId: String, tagColorRgb: Int): ColorCrossRef? = withContext(Dispatchers.IO) {
        filamentInventoryDao.getColorCrossRef(brand, type, variantId, tagColorRgb)
    }

    /**
     * Retrieves a color cross-reference matching the raw tag color for a specific brand/type.
     */
    suspend fun getColorByTagInt(brand: String, type: String, tagColorRgb: Int): ColorCrossRef? = withContext(Dispatchers.IO) {
        filamentInventoryDao.getColorByTagInt(brand, type, tagColorRgb)
    }

    /**
     * Retrieves a single filament by its database ID.
     */
    suspend fun getFilamentByIdStatic(id: Int): FilamentInventory? = withContext(Dispatchers.IO) {
        filamentInventoryDao.getFilamentByIdStatic(id)
    }

    /**
     * Performs a direct update on a filament record without triggering the [InventoryLimitWorker].
     * Used for internal cleanup and mapping resolutions.
     */
    suspend fun updateNoWorker(filament: FilamentInventory) = withContext(Dispatchers.IO) {
        filamentInventoryDao.updateNoWorker(filament)
    }

    /**
     * Provides a reactive flow of unmapped filament records.
     */
    fun getUnmappedFilamentsFlow(): Flow<List<UnmappedFilament>> {
        return filamentInventoryDao.getUnmappedFilamentsFlow()
    }

    /**
     * Removes an unmapped record, typically after a successful manual mapping.
     */
    suspend fun removeUnmapped(filamentId: Int) {
        filamentInventoryDao.removeUnmapped(filamentId)
    }

    /**
     * Attempts to automatically resolve all currently unmapped filaments by checking 
     * the [ColorCrossRef] table for matching criteria.
     */
    suspend fun resolveAllUnmapped() {
        val unmapped = filamentInventoryDao.getUnmappedFilamentsStatic()
        unmapped.forEach { entry ->
            val filament = filamentInventoryDao.getFilamentByIdStatic(entry.filamentId) ?: return@forEach
            val mapping = if (entry.materialVariantID != null && filament.tagColorRgb != null) {
                filamentInventoryDao.getColorName(filament.brand, filament.type ?: "Unknown", entry.materialVariantID, filament.tagColorRgb)
            } else if (entry.colorInt != null) {
                filamentInventoryDao.getColorByTagInt(filament.brand, filament.type ?: "Unknown", entry.colorInt)?.colorName
            } else null

            if (mapping != null) {
                val colorRef = if (entry.materialVariantID != null && filament.tagColorRgb != null) {
                    filamentInventoryDao.getColorCrossRef(filament.brand, filament.type ?: "Unknown", entry.materialVariantID, filament.tagColorRgb)
                } else if (entry.colorInt != null) {
                    filamentInventoryDao.getColorByTagInt(filament.brand, filament.type ?: "Unknown", entry.colorInt)
                } else null

                filamentInventoryDao.updateNoWorker(filament.copy(
                    colorName = mapping,
                    colorRgb = colorRef?.colorInt ?: filament.colorRgb
                ))
                filamentInventoryDao.removeUnmapped(entry.filamentId)
            }
        }
    }
}
