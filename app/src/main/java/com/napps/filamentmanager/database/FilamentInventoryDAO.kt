package com.napps.filamentmanager.database

import android.content.Context
import androidx.core.graphics.toColorInt
import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.napps.filamentmanager.mqtt.AmsUnitReport
import com.napps.filamentmanager.mqtt.HMSCodes
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the filament inventory.
 * Contains logic for synchronizing printer AMS status with the local database.
 */
@Dao
abstract class FilamentInventoryDAO {

    @Query("SELECT * FROM color_cross_ref WHERE materialVariantID = :variantId AND brand = :brand AND type = :type AND tagColorRgb = :tagColorRgb LIMIT 1")
    abstract suspend fun getColorCrossRef(brand: String, type: String, variantId: String, tagColorRgb: Int): ColorCrossRef?

    @Query("SELECT colorName FROM color_cross_ref WHERE materialVariantID = :variantId AND brand = :brand AND type = :type AND tagColorRgb = :tagColorRgb LIMIT 1")
    abstract suspend fun getColorName(brand: String, type: String, variantId: String, tagColorRgb: Int): String?

    @Query("SELECT * FROM color_cross_ref WHERE tagColorRgb = :tagColorRgb AND brand = :brand AND type = :type LIMIT 1")
    abstract suspend fun getColorCrossRefByTagColorRgb(brand: String, type: String, tagColorRgb: Int): ColorCrossRef?

    @Query("SELECT * FROM color_cross_ref WHERE colorInt = :ColorRgb AND brand = :brand AND type = :type LIMIT 1")
    abstract suspend fun getColorCrossRefByColorRgb(brand: String, type: String, ColorRgb: Int): ColorCrossRef?


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMapping(mapping: ColorCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun _insert(filament: FilamentInventory): Long

    @Delete
    protected abstract suspend fun _delete(filament: FilamentInventory)

    @Update
    protected abstract suspend fun _update(filament: FilamentInventory)

    /**
     * Standard insert that triggers the InventoryLimitWorker and checks for unmapped filaments.
     */
    @Transaction
    open suspend fun insert(filament: FilamentInventory, context: Context): Long {
        val id = _insert(filament)
        checkAndMarkUnmapped(id.toInt(), filament.brand, filament.type ?: "Unknown", filament.materialVariantID,filament.colorRgb?:-1, filament.tagColorRgb)
        InventoryLimitWorker.schedule(context)
        return id
    }

    /**
     * Standard update that triggers the InventoryLimitWorker and checks for unmapped filaments.
     */
    @Transaction
    open suspend fun update(filament: FilamentInventory, context: Context) {
        _update(filament)
        checkAndMarkUnmapped(filament.id, filament.brand, filament.type ?: "Unknown", filament.materialVariantID,filament.colorRgb?:-1, filament.tagColorRgb)
        InventoryLimitWorker.schedule(context)
    }

    /**
     * Standard delete that triggers the InventoryLimitWorker.
     */
    @Transaction
    open suspend fun delete(filament: FilamentInventory, context: Context) {
        _delete(filament)
        removeUnmapped(filament.id)
        InventoryLimitWorker.schedule(context)
    }

    /**
     * Insert without triggering the worker.
     */
    @Transaction
    open suspend fun insertNoWorker(filament: FilamentInventory): Long {
        val id = _insert(filament)
        checkAndMarkUnmapped(id.toInt(), filament.brand, filament.type ?: "Unknown", filament.materialVariantID,filament.colorRgb?:-1, filament.tagColorRgb)
        return id
    }

    /**
     * Update without triggering the worker.
     */
    @Transaction
    open suspend fun updateNoWorker(filament: FilamentInventory) {
        _update(filament)
        checkAndMarkUnmapped(filament.id, filament.brand, filament.type ?: "Unknown", filament.materialVariantID,filament.colorRgb?:-1, filament.tagColorRgb)
    }

    /**
     * Delete without triggering the worker.
     */
    @Transaction
    open suspend fun deleteNoWorker(filament: FilamentInventory) {
        _delete(filament)
        removeUnmapped(filament.id)
    }


    @Query("SELECT * FROM filaments_inventory")
    abstract fun getAllFilaments(): LiveData<List<FilamentInventory>>

    @Query("SELECT * FROM filaments_inventory")
    abstract suspend fun getAllFilamentsStatic(): List<FilamentInventory>

    @Query("SELECT COUNT(*) FROM filaments_inventory WHERE brand = :brand")
    abstract suspend fun countByBrand(brand: String): Int

    @Query("DELETE FROM filaments_inventory WHERE brand = :brand")
    abstract suspend fun deleteByBrand(brand: String)

    @Query("SELECT id FROM filaments_inventory WHERE availabilityStatus != 4")
    abstract suspend fun getActiveIds(): List<Int>

    @Query("SELECT * FROM filaments_inventory WHERE id = :id LIMIT 1")
    abstract suspend fun getFilamentByIdStatic(id: Int): FilamentInventory?

    @Query("SELECT * FROM filaments_inventory WHERE trayUID = :trayUid LIMIT 1")
    abstract suspend fun getFilamentByTrayUidStatic(trayUid: String): FilamentInventory?

    @Query("SELECT * FROM filaments_inventory WHERE trayUID = :trayUid LIMIT 1")
    abstract fun getFilamentByTrayUid(trayUid: String): LiveData<FilamentInventory>

    @Transaction
    open suspend fun insertOrUpdate(filament: FilamentInventory, context: Context) {
        val existingFilament = getFilamentByTrayUidStatic(filament.trayUID ?: "")
        if (existingFilament != null) {
            update(filament.copy(id = existingFilament.id), context)
        } else {
            insert(filament, context)
        }
    }

    @Transaction
    open suspend fun insertOrUpdateNoWorker(filament: FilamentInventory) {
        val existingFilament = getFilamentByTrayUidStatic(filament.trayUID ?: "")
        if (existingFilament != null) {
            updateNoWorker(filament.copy(id = existingFilament.id))
        } else {
            insertNoWorker(filament)
        }
    }

    /**
     * Synchronizes the local inventory with the provided AMS unit reports.
     * This method handles:
     * 1. Detecting spools that were removed from the AMS (and determining if they ran out).
     * 2. Updating the remaining percentage for spools currently in the AMS.
     * 3. Auto-inserting new spools detected in the AMS that aren't in the database yet.
     *
     * @param context Context for scheduling [InventoryLimitWorker].
     * @param amsUnits The current telemetry for all attached AMS units.
     * @param hmsList Current printer error codes (used to detect filament runout).
     * @param gcodeState Current printer execution state.
     */
    @Transaction
    open suspend fun syncAms(
        context: Context,
        amsUnits: List<AmsUnitReport>,
        hmsList: List<Long>,
        gcodeState: String,
        printError: Long = 0L
    ) {
        val amsStatusFilaments = getFilamentsByStatus(AvailabilityStatus.IN_AMS)
        val activeAmsUuids = amsUnits.flatMap { it.trays }.map { it.uuid }

        amsStatusFilaments.forEach { filament ->
            val isStillInAms = activeAmsUuids.contains(filament.trayUID)
            if (!isStillInAms) {
                val isRunout = HMSCodes.isRunout(hmsList, printError) && (gcodeState == "RUNNING" || gcodeState == "PAUSE")
                val updatedFilament = if (isRunout) {
                    filament.copy(
                        availabilityStatus = AvailabilityStatus.OUT_OF_STOCK,
                        oosTimestamp = System.currentTimeMillis()
                    )
                } else {
                    filament.copy(availabilityStatus = AvailabilityStatus.OPEN)
                }
                updateNoWorker(updatedFilament)
            }
        }

        amsUnits.forEach { unitReport ->
            unitReport.trays.forEach { tray ->
                if (tray.subBrand.equals("Empty", ignoreCase = true) ||
                    tray.subBrand.equals("Non-Bambu Spool", ignoreCase = true) ||
                    tray.subBrand.equals("Null", ignoreCase = true) ||
                    tray.uuid.isBlank() ||
                    tray.uuid.isEmpty() ||
                    tray.subBrand.isBlank()
                ) {
                    return@forEach
                }

                val existingFilament = getFilamentByTrayUidStatic(tray.uuid)
                val clampedUsedPercent = (tray.remain.toFloat() / 100f).coerceAtLeast(0f)

                if (existingFilament != null) {
                    if (existingFilament.usedPercent != clampedUsedPercent || existingFilament.availabilityStatus != AvailabilityStatus.IN_AMS) {
                        val updatedFilament = existingFilament.copy(
                            usedPercent = clampedUsedPercent,
                            availabilityStatus = AvailabilityStatus.IN_AMS
                        )
                        updateNoWorker(updatedFilament)
                    }
                } else {
                    val colorInt = safeParseColor(tray.colorHex)
                    val isBambu = tray.tagUid != "0000000000000000" && tray.tagUid.isNotEmpty()
                    val brand = if (isBambu) "Bambu Lab" else tray.subBrand
                    val type = if (isBambu) tray.subBrand else tray.type
                    val variantId = tray.trayInfoIdx
                    val colorRef = if (colorInt != null && variantId.isNotEmpty()) {
                        getColorCrossRef(brand, type, variantId, colorInt)
                    } else if (colorInt != null) {
                        getColorCrossRefByTagColorRgb(brand, type, colorInt)
                    } else null

                    val newFilament = FilamentInventory(
                        trayUID = tray.uuid,
                        colorRgb = colorRef?.colorInt ?: colorInt,
                        tagColorRgb = colorInt,
                        colorName = colorRef?.colorName ?: "Unknown",
                        brand = brand,
                        type = type,
                        usedPercent = clampedUsedPercent,
                        availabilityStatus = AvailabilityStatus.IN_AMS,
                        weight = tray.weight,
                        materialVariantID = tray.trayInfoIdx,
                        materialID = null,
                        diameter = null,
                        filamentLength = null
                    )
                    insertNoWorker(newFilament)
                }
            }
        }
        // Always schedule limit check after processing an AMS report sync
        InventoryLimitWorker.schedule(context)
    }

    @Transaction
    open suspend fun syncAmsNoWorker(
        amsUnits: List<AmsUnitReport>,
        hmsList: List<Long>,
        gcodeState: String,
        printError: Long = 0L
    ) {
        val amsStatusFilaments = getFilamentsByStatus(AvailabilityStatus.IN_AMS)
        val activeAmsUuids = amsUnits.flatMap { it.trays }.map { it.uuid }

        amsStatusFilaments.forEach { filament ->
            val isStillInAms = activeAmsUuids.contains(filament.trayUID)
            if (!isStillInAms) {
                val isRunout = HMSCodes.isRunout(hmsList, printError) && (gcodeState == "RUNNING" || gcodeState == "PAUSE")
                val updatedFilament = if (isRunout) {
                    filament.copy(
                        availabilityStatus = AvailabilityStatus.OUT_OF_STOCK,
                        oosTimestamp = System.currentTimeMillis()
                    )
                } else {
                    filament.copy(availabilityStatus = AvailabilityStatus.OPEN)
                }
                updateNoWorker(updatedFilament)
            }
        }

        amsUnits.forEach { unitReport ->
            unitReport.trays.forEach { tray ->
                if (tray.subBrand.equals("Empty", ignoreCase = true) ||
                    tray.subBrand.equals("Non-Bambu Spool", ignoreCase = true) ||
                    tray.subBrand.equals("Null", ignoreCase = true) ||
                    tray.uuid.isBlank() ||
                    tray.uuid.isEmpty() ||
                    tray.subBrand.isBlank()
                ) {
                    return@forEach
                }

                val existingFilament = getFilamentByTrayUidStatic(tray.uuid)
                val clampedUsedPercent = (tray.remain.toFloat() / 100f).coerceAtLeast(0f)

                if (existingFilament != null) {
                    if (existingFilament.usedPercent != clampedUsedPercent || existingFilament.availabilityStatus != AvailabilityStatus.IN_AMS) {
                        val updatedFilament = existingFilament.copy(
                            usedPercent = clampedUsedPercent,
                            availabilityStatus = AvailabilityStatus.IN_AMS
                        )
                        updateNoWorker(updatedFilament)
                    }
                } else {
                    val colorInt = safeParseColor(tray.colorHex)
                    val isBambu = tray.tagUid != "0000000000000000" && tray.tagUid.isNotEmpty()
                    val brand = if (isBambu) "Bambu Lab" else tray.subBrand
                    val type = if (isBambu) tray.subBrand else tray.type
                    val variantId = tray.trayInfoIdx
                    val colorRef = if (colorInt != null && variantId.isNotEmpty()) {
                        getColorCrossRef(brand, type, variantId, colorInt)
                    } else if (colorInt != null) {
                        getColorCrossRefByTagColorRgb(brand, type, colorInt)
                    } else null

                    val newFilament = FilamentInventory(
                        trayUID = tray.uuid,
                        colorRgb = colorRef?.colorInt ?: colorInt,
                        tagColorRgb = colorInt,
                        colorName = colorRef?.colorName ?: "Unknown",
                        brand = brand,
                        type = type,
                        usedPercent = clampedUsedPercent,
                        availabilityStatus = AvailabilityStatus.IN_AMS,
                        weight = tray.weight,
                        materialVariantID = tray.trayInfoIdx,
                        materialID = null,
                        diameter = null,
                        filamentLength = null
                    )
                    insertNoWorker(newFilament)
                }
            }
        }
    }

    private fun safeParseColor(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        val cleanHex = hex.removePrefix("#")
        return try {
            val argbHex = when (cleanHex.length) {
                8 -> {
                    val rgb = cleanHex.take(6)
                    val alpha = cleanHex.substring(6, 8)
                    alpha + rgb
                }
                6 -> "FF$cleanHex"
                else -> cleanHex
            }
            "#$argbHex".toColorInt()
        } catch (e: Exception) {
            null
        }
    }

    @Query("SELECT * FROM color_cross_ref WHERE tagColorRgb = :tagColorRgb AND brand = :brand AND type = :type LIMIT 1")
    abstract suspend fun getColorByTagInt(brand: String, type: String, tagColorRgb: Int): ColorCrossRef?

    @Query("SELECT * FROM filaments_inventory WHERE availabilityStatus = :status")
    abstract fun getFilamentsByStatus(status: Int): List<FilamentInventory>

    @Query("SELECT * FROM filaments_inventory WHERE availabilityStatus IN (:statuses)")
    abstract fun getAllByAvailabilityStatus(statuses: List<Int>): LiveData<List<FilamentInventory>>

    @Query("SELECT * FROM filaments_inventory WHERE availabilityStatus IN (:statuses) ORDER BY id DESC")
    abstract fun getAllByAvailabilityStatusPaging(statuses: List<Int>): PagingSource<Int, FilamentInventory>

    @Query("""
        SELECT * FROM filaments_inventory 
        WHERE (:brands IS NULL OR brand IN (:brands))
        AND (:types IS NULL OR type IN (:types))
        AND (:availability IS NULL OR AvailabilityStatus IN (:availability))
        AND (:colors IS NULL OR colorName IN (:colors))
        AND (:weights IS NULL OR weight IN (:weights))
        ORDER BY availabilityStatus,brand,type,colorName,weight DESC
    """)
    abstract fun getFilteredFilaments(
        brands: List<String>?,
        types: List<String>?,
        availability: List<Int>?,
        colors: List<String>?,
        weights: List<String>?
    ): PagingSource<Int, FilamentInventory>


    @Query("""
        SELECT DISTINCT brand FROM filaments_inventory 
        WHERE brand IS NOT NULL 
        AND (:types IS NULL OR type IN (:types))
        AND (:availability IS NULL OR AvailabilityStatus IN (:availability))
        AND (:colors IS NULL OR colorName IN (:colors))
        AND (:weights IS NULL OR weight IN (:weights))
        ORDER BY brand ASC
    """)
    abstract fun getUniqueBrands(
        types: List<String>?, availability: List<Int>?, colors: List<String>?,
         weights: List<String>?
    ): Flow<List<String>>

    @Query("""
        SELECT DISTINCT type FROM filaments_inventory 
        WHERE type IS NOT NULL 
        AND (:brands IS NULL OR brand IN (:brands))
        AND (:availability IS NULL OR AvailabilityStatus IN (:availability))
        AND (:colors IS NULL OR colorName IN (:colors))
        AND (:weights IS NULL OR weight IN (:weights))
        ORDER BY type ASC
    """)
    abstract fun getUniqueTypes(
        brands: List<String>?, availability: List<Int>?, colors: List<String>?,
         weights: List<String>?
    ): Flow<List<String>>

    @Query("""
        SELECT DISTINCT colorName FROM filaments_inventory 
        WHERE colorName IS NOT NULL 
        AND (:brands IS NULL OR brand IN (:brands))
        AND (:types IS NULL OR type IN (:types))
        AND (:availability IS NULL OR AvailabilityStatus IN (:availability))
        AND (:weights IS NULL OR weight IN (:weights))
        ORDER BY colorName ASC
    """)
    abstract fun getUniqueColors(
        brands: List<String>?, types: List<String>?, availability: List<Int>?,
         weights: List<String>?
    ): Flow<List<String>>

    @Query("""
        SELECT DISTINCT weight FROM filaments_inventory 
        WHERE weight IS NOT NULL 
        AND (:brands IS NULL OR brand IN (:brands))
        AND (:types IS NULL OR type IN (:types))
        AND (:availability IS NULL OR AvailabilityStatus IN (:availability))
        AND (:colors IS NULL OR colorName IN (:colors))
        ORDER BY weight ASC
    """)
    abstract fun getUniqueWeights(
        brands: List<String>?, types: List<String>?, availability: List<Int>?,
        colors: List<String>?
    ): Flow<List<String>>

    @Query("""
        SELECT DISTINCT AvailabilityStatus FROM filaments_inventory 
        WHERE AvailabilityStatus IS NOT NULL 
        AND (:brands IS NULL OR brand IN (:brands))
        AND (:types IS NULL OR type IN (:types))
        AND (:colors IS NULL OR colorName IN (:colors))
        AND (:weights IS NULL OR weight IN (:weights))
        ORDER BY AvailabilityStatus ASC
    """)
    abstract fun getUniqueAvailability(
        brands: List<String>?, types: List<String>?, colors: List<String>?,
         weights: List<String>?
    ): Flow<List<Int>>

    @Query("SELECT * FROM filaments_inventory WHERE brand = :brand AND type = :type AND colorName = :colorName")
    abstract fun getMatchingInventorySpools(brand: String, type: String, colorName: String): LiveData<List<FilamentInventory>>

    // --- Potential Out of Stock Section ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun _markPotentialOutOfStock(potential: PotentialOutOfStock)

    @Transaction
    open suspend fun markPotentialOutOfStock(potential: PotentialOutOfStock) {
        val filament = getFilamentByIdStatic(potential.filamentId)
        if (filament != null && filament.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK) {
            _markPotentialOutOfStock(potential)
        }
    }

    @Query("DELETE FROM potential_out_of_stock WHERE filamentId = :filamentId")
    abstract suspend fun removePotentialOutOfStock(filamentId: Int)

    @Query("SELECT * FROM potential_out_of_stock")
    abstract fun getPotentialOutOfStockFlow(): Flow<List<PotentialOutOfStock>>

    @Query("SELECT * FROM potential_out_of_stock")
    abstract suspend fun getPotentialOutOfStockStatic(): List<PotentialOutOfStock>

    @Query("DELETE FROM potential_out_of_stock WHERE reason = :reason")
    abstract suspend fun deletePotentialOosByReason(reason: Int)

    // --- Unmapped Filaments Section ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun _insertUnmapped(unmapped: UnmappedFilament)

    @Query("DELETE FROM unmapped_filaments WHERE filamentId = :filamentId")
    abstract suspend fun removeUnmapped(filamentId: Int)

    @Query("SELECT * FROM unmapped_filaments")
    abstract fun getUnmappedFilamentsFlow(): Flow<List<UnmappedFilament>>

    @Query("SELECT * FROM unmapped_filaments")
    abstract suspend fun getUnmappedFilamentsStatic(): List<UnmappedFilament>

    /**
     * Atomically updates a filament's status and ensures its unmapped state is synchronized.
     *
     * @param filamentId ID of the filament.
     * @param brand Brand name.
     * @param type Material type.
     * @param materialVariantID Optional printer-specific variant ID.
     * @param colorRgb The current color integer.
     * @param tagColorRgb The original NFC/RFID color integer.
     */
    @Transaction
    open suspend fun checkAndMarkUnmapped(filamentId: Int, brand: String, type: String, materialVariantID: String?, colorRgb: Int, tagColorRgb: Int?) {
        val filament = getFilamentByIdStatic(filamentId)
        // Only track unmapped status for AMS/NFC spools (those with a trayUID)
        if (filament?.trayUID.isNullOrBlank()) {
            removeUnmapped(filamentId)
            return
        }

        val mapping = if (materialVariantID != null && tagColorRgb != null) {
            getColorCrossRef(brand, type, materialVariantID, tagColorRgb)
        } else if (tagColorRgb != null) {
            getColorCrossRefByTagColorRgb(brand, type, tagColorRgb)
        } else getColorCrossRefByColorRgb(brand, type, colorRgb)

        if (mapping == null) {
            _insertUnmapped(UnmappedFilament(
                filamentId = filamentId,
                materialVariantID = materialVariantID,
                colorInt = colorRgb,
                tagColorRgb = tagColorRgb
            ))
        } else {
            removeUnmapped(filamentId)
        }
    }
}
