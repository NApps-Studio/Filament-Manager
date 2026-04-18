package com.napps.filamentmanager.database

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * ViewModel for managing inventory limits and their relationship with filaments.
 * 
 * Inventory limits allow users to set thresholds for specific filament types. 
 * This ViewModel handles the persistence of these limits, their active status, 
 * and synchronization with availability trackers.
 */
class InventoryLimitViewModel(private val repository: InventoryLimitRepository) : ViewModel() {

    /**
     * LiveData stream of all limits and their associated filaments.
     */
    val allLimitsWithFilaments: LiveData<List<LimitWithFilaments>> = repository.allLimitsWithFilaments

    /**
     * Updates an existing limit or creates a new one, and updates its associated filaments.
     */
    fun updateLimitWithFilaments(limit: InventoryLimit, filamentIds: List<Int>) {
        viewModelScope.launch {
            repository.updateLimitWithFilaments(limit, filamentIds)
        }
    }

    /**
     * Deletes an inventory limit from the database.
     * @param limit The [InventoryLimit] to remove.
     */
    fun deleteLimit(limit: InventoryLimit) {
        viewModelScope.launch {
            repository.deleteLimit(limit)
        }
    }

    /**
     * Toggles whether a specific limit is active.
     * @param limitId ID of the limit.
     * @param isActive The new state.
     */
    fun toggleLimitActive(limitId: Int, isActive: Boolean) {
        viewModelScope.launch {
            repository.updateLimitStatus(limitId, isActive)
        }
    }

    /**
     * Retrieves all limits and filaments once without a LiveData stream.
     * @return A list of [LimitWithFilaments].
     */
    suspend fun getAllLimitsWithFilamentsStatic(): List<LimitWithFilaments> {
        return repository.getAllLimitsWithFilamentsStatic()
    }

    /**
     * Imports a set of limits from an external source (e.g., CSV/JSON export).
     *
     * @param incomingLimits A complex list of limits and their associated filament variants as maps.
     * @param replaceDuplicates If true, existing limits with the same name will be overwritten.
     * @param onComplete Callback with the count of imported items.
     */
    fun importRobustLimits(
        incomingLimits: List<Pair<Map<String, String>, List<Map<String, String>>>>,
        replaceDuplicates: Boolean,
        onComplete: (Int) -> Unit
    ) {
        viewModelScope.launch {
            repository.importRobustLimits(incomingLimits, replaceDuplicates, onComplete)
        }
    }

    /**
     * Manually triggers a synchronization check between inventory levels and stock trackers.
     */
    fun syncLimitsWithTrackers() {
        viewModelScope.launch {
            repository.syncLimitsWithTrackers()
        }
    }
}

/**
 * Factory for creating [InventoryLimitViewModel] with the necessary repository.
 */
class InventoryLimitViewModelFactory(private val repository: InventoryLimitRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InventoryLimitViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return InventoryLimitViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
