package com.napps.filamentmanager.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/**
 * Data Access Object for managing inventory limits and their associations with vendor filaments.
 */
@Dao
interface InventoryLimitDao {
    /**
     * Returns all limits including their linked filaments.
     * @return LiveData containing the list of [LimitWithFilaments].
     */
    @Transaction
    @Query("SELECT * FROM inventory_limits")
    fun getAllLimitsWithFilaments(): LiveData<List<LimitWithFilaments>>

    /**
     * Returns all limits including their linked filaments statically.
     * @return A list of [LimitWithFilaments].
     */
    @Transaction
    @Query("SELECT * FROM inventory_limits")
    suspend fun getAllLimitsWithFilamentsStatic(): List<LimitWithFilaments>

    /**
     * Adds a new inventory limit.
     * @param limit The [InventoryLimit] to insert.
     * @return The auto-generated row ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLimit(limit: InventoryLimit): Long

    /**
     * Updates an existing inventory limit.
     * @param limit The [InventoryLimit] to update.
     */
    @Update
    suspend fun updateLimit(limit: InventoryLimit)

    /**
     * Removes an inventory limit.
     * @param limit The [InventoryLimit] to delete.
     */
    @Delete
    suspend fun deleteLimit(limit: InventoryLimit)

    /**
     * Links a vendor filament to an inventory limit.
     * @param crossRef The mapping between limit and filament.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLimitFilamentCrossRef(crossRef: LimitFilamentCrossRef)

    /**
     * Unlinks all filaments from a specific limit.
     * @param limitId The ID of the limit.
     */
    @Query("DELETE FROM limit_filament_cross_ref WHERE limitId = :limitId")
    suspend fun deleteCrossRefsForLimit(limitId: Int)

    /**
     * Atomically updates a limit and refreshes its linked filament associations.
     */
    @Transaction
    suspend fun updateLimitWithFilaments(limit: InventoryLimit, filamentIds: List<Int>): Int {
        val limitId = if (limit.id == 0) {
            insertLimit(limit).toInt()
        } else {
            updateLimit(limit)
            limit.id
        }
        deleteCrossRefsForLimit(limitId)
        filamentIds.forEach { filamentId ->
            insertLimitFilamentCrossRef(LimitFilamentCrossRef(limitId, filamentId))
        }
        return limitId
    }

    /**
     * Updates the association between a limit and its background "Limit Service" tracker.
     * @param limitId The ID of the limit.
     * @param trackerId The ID of the [AvailabilityTracker] or null to unlink.
     */
    @Query("UPDATE inventory_limits SET trackerId = :trackerId WHERE id = :limitId")
    suspend fun updateTrackerId(limitId: Int, trackerId: Int?)

    /**
     * Toggles the active status of an inventory limit.
     * @param limitId The ID of the limit.
     * @param isActive The new active status.
     */
    @Query("UPDATE inventory_limits SET isActive = :isActive WHERE id = :limitId")
    suspend fun updateLimitStatus(limitId: Int, isActive: Boolean)
}
