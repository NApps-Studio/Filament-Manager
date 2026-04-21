package com.napps.filamentmanager.database

import android.util.Log
import androidx.core.graphics.toColorInt
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.napps.filamentmanager.FabAction
import com.napps.filamentmanager.util.BambuTagReader.BambuSpoolData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data class to hold all active filter states for the inventory screen.
 */
data class FilamentFilters(
    val brands: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val availability: List<Int> = emptyList(),
    val colors: List<String> = emptyList(),
    val weights: List<String> = emptyList()
)

/**
 * Represents a group of filament spools of the same material type.
 */
data class FilamentGroup(
    val type: String,
    val filaments: List<FilamentInventory>,
    val totalMassG: Double,
    val activeSpools: Int,
    val hasUnmapped: Boolean = false,
    val hasPotentialOos: Boolean = false,
    val unmappedFilamentIds: Set<Int> = emptySet()
)

/**
 * Represents all filament groups belonging to a specific vendor/brand.
 */
data class VendorGroup(
    val vendor: String,
    val groups: List<FilamentGroup>,
    val totalMassG: Double,
    val activeSpools: Int,
    val hasUnmapped: Boolean = false,
    val hasPotentialOos: Boolean = false
)

/**
 * Summary statistics for the user's filament inventory.
 */
data class InventorySummary(
    val totalSpools: Int,
    val oosCount: Int,
    val amsCount: Int,
    val openCount: Int,
    val inUseCount: Int,
    val newCount: Int,
    val totalWeightKg: Double
)

/**
 * ViewModel for managing the user's personal filament inventory.
 * 
 * Handles all CRUD operations for the inventory, manages NFC scanning state for 
 * RFID-tagged spools, and provides complex data aggregations for the UI 
 * (e.g., grouping by Vendor/Type, calculating total weights, and low stock alerts).
 * 
 * This ViewModel acts as the primary coordinator for the main inventory screen, 
 * integrating data from the [FilamentInventoryRepository] and [UserPreferencesRepository].
 */
class FilamentInventoryViewModel(
    private val repository: FilamentInventoryRepository,
    private val userPrefs: UserPreferencesRepository
) : ViewModel() {

    /**
     * Resolves a human-readable color name based on the provided material metadata.
     */
    suspend fun getColorName(brand: String, type: String?, variantId: String?, tagColorRgb: Int?): String? {
        if (variantId == null || type == null || tagColorRgb == null) return null
        return repository.getColorName(brand, type, variantId, tagColorRgb)
    }

    /**
     * Retrieves a [ColorCrossRef] mapping by its raw tag color.
     */
    suspend fun getColorCrossRefBytagColorRgb(brand: String, type: String?, tagColorRgb: Int?): ColorCrossRef? {
        if (type == null || tagColorRgb == null) return null
        return repository.getColorCrossRefBytagColorRgb(brand, type, tagColorRgb)
    }

    /**
     * Manually inserts a new color mapping.
     */
    suspend fun insertMapping(mapping: ColorCrossRef) {
        repository.insertMapping(mapping)
    }


    // --- NAVIGATION ---
    private val _showLimitsScreen = MutableStateFlow(false)
    val showLimitsScreen = _showLimitsScreen.asStateFlow()

    /** Navigates to the inventory stock limits screen. */
    fun navigateToLimits() { _showLimitsScreen.value = true }
    /** Navigates back from the limits screen. */
    fun navigateBackFromLimits() { _showLimitsScreen.value = false }


    // --- FAB & PERSISTENCE ---

    /** The current action assigned to the Floating Action Button. */
    val currentFabMode: StateFlow<FabAction> = userPrefs.fabModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FabAction.SCAN_RF_ID
        )

    /** Sets the user's preferred FAB action. */
    fun setFabMode(mode: FabAction) {
        viewModelScope.launch {
            userPrefs.saveFabMode(mode)
        }
    }

    /** Whether to include empty spools in the main inventory list. */
    val showEmptySpools: StateFlow<Boolean> = userPrefs.showEmptySpoolsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** The weight threshold below which a spool is flagged as low stock. */
    val lowFilamentThresholdG: StateFlow<Int> = userPrefs.lowFilamentThresholdGFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 25)

    /** Flag indicating if the first-run synchronization with Bambu Cloud has completed. */
    val hasFirstSyncFinished: StateFlow<Boolean> = userPrefs.hasFirstSyncFinishedFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Whether to suppress warnings about incomplete cloud synchronization. */
    val ignoreSyncWarning: StateFlow<Boolean> = userPrefs.ignoreSyncWarningFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Persistence for the last manually selected brand. */
    val lastUsedBrand: StateFlow<String?> = userPrefs.lastUsedBrandFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Persistence for the last manually selected material type. */
    val lastUsedType: StateFlow<String?> = userPrefs.lastUsedTypeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Persistence for the last manually selected color name. */
    val lastUsedColorName: StateFlow<String?> = userPrefs.lastUsedColorNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Persistence for the last manually selected color RGB value. */
    val lastUsedColorRgb: StateFlow<Int?> = userPrefs.lastUsedColorRgbFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Saves manual entry field states for quick reuse during the next add operation. */
    fun saveLastUsedManualFilament(brand: String, type: String, colorName: String, colorRgb: Int?) {
        viewModelScope.launch {
            userPrefs.saveLastUsedManualFilament(brand, type, colorName, colorRgb)
        }
    }

    /** Toggles the sync warning visibility. */
    fun setIgnoreSyncWarning(ignore: Boolean) {
        viewModelScope.launch {
            userPrefs.setIgnoreSyncWarning(ignore)
        }
    }

    /** Toggles visibility of empty spools and refreshes the current session's visible ID set. */
    fun toggleShowEmptySpools() {
        viewModelScope.launch {
            val newValue = !showEmptySpools.value
            userPrefs.setShowEmptySpools(newValue)
            
            // Flush session visibility: when toggling, we "reset" the view 
            // so that only currently active (non-OOS) filaments stay in the 'sticky' list.
            val currentActiveIds = withContext(Dispatchers.IO) {
                repository.getActiveIds()
            }
            _sessionVisibleIds.value = currentActiveIds.toSet()
        }
    }

    // Session-based visibility: spools that were active when the session started or added during it
    // This allows newly marked OOS spools to stay visible until the next eye toggle or app restart.
    private val _sessionVisibleIds = MutableStateFlow<Set<Int>>(emptySet())
    
    init {
        viewModelScope.launch {
            val initialActiveIds = withContext(Dispatchers.IO) {
                repository.getActiveIds()
            }
            _sessionVisibleIds.value = initialActiveIds.toSet()
        }

        // --- Low Stock Auto-Detection ---
        viewModelScope.launch {
            combine(
                repository.getAllFilaments().asFlow(),
                lowFilamentThresholdG
            ) { filaments, threshold ->
                filaments.forEach { spool ->
                    if (spool.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK && !isUnknownSpool(spool)) {
                        val weightStr = spool.weight?.replace(Regex("[^0-9]"), "") ?: "1000"
                        val totalWeight = weightStr.toDoubleOrNull() ?: 1000.0
                        val currentWeight = totalWeight * (spool.usedPercent ?: 1.0f)
                        
                        if (currentWeight < threshold.toDouble()) {
                            repository.markPotentialOutOfStock(spool.id)
                        }
                    }
                }
            }.collect()
        }
    }

    // --- NFC / RFID STATE ---

    /** Represents the current state of the NFC scanning process. */
    sealed class NfcStatus {
        object Idle : NfcStatus()
        object Scanning : NfcStatus()
        object Success : NfcStatus()
        data class Error(val message: String) : NfcStatus()
    }

    private val _nfcScanningState = MutableStateFlow<NfcStatus>(NfcStatus.Idle)
    val nfcScanningState = _nfcScanningState.asStateFlow()

    private val _resolvingUnmappedFilament = MutableStateFlow<FilamentInventory?>(null)
    val resolvingUnmappedFilament = _resolvingUnmappedFilament.asStateFlow()

    private val _hasMoreUnmapped = MutableStateFlow(false)
    val hasMoreUnmapped = _hasMoreUnmapped.asStateFlow()

    /** Sets the filament currently being manually mapped to a color. */
    fun setResolvingUnmapped(filament: FilamentInventory?) {
        _resolvingUnmappedFilament.value = filament
    }

    /** 
     * Finds the next filament requiring manual mapping. 
     * Attempts to auto-resolve using [ColorCrossRef] first.
     */
    fun resolveNextUnmapped() {
        viewModelScope.launch {
            val unmapped = repository.getUnmappedFilamentsStatic()
            if (unmapped.isEmpty()) {
                _resolvingUnmappedFilament.value = null
                _hasMoreUnmapped.value = false
                return@launch
            }
            val manualNeeds = mutableListOf<FilamentInventory>()
            
            for (entry in unmapped) {
                val filament = repository.getFilamentByIdStatic(entry.filamentId) ?: continue
                val colorRef = if (filament.tagColorRgb != null) {
                    repository.getColorCrossRefBytagColorRgb(filament.brand, filament.type ?: "Unknown", filament.tagColorRgb)
                } else repository.getColorCrossRefByColorRgb(filament.brand, filament.type ?: "Unknown", filament.colorRgb?:-1)



                if (colorRef != null) {
                    repository.updateNoWorker(filament.copy(
                        colorName = colorRef.colorName,
                        colorRgb = colorRef.colorInt ?: filament.tagColorRgb
                    ))
                    repository.removeUnmapped(entry.filamentId)
                   } else {
                    manualNeeds.add(filament)
                }
            }

            // Group by unique mapping criteria (Brand + Type + Variant ID + ColorInt)
            val uniqueManualNeeds = manualNeeds.distinctBy { filament ->
                val entry = unmapped.find { it.filamentId == filament.id }
                if (entry?.tagColorRgb != null) {
                    "${filament.brand}_${filament.type}_${filament.materialVariantID}_${filament.tagColorRgb}"
                } else {
                    "${filament.brand}_${filament.type}_COLOR_${filament.colorRgb ?: -1}"
                }
            }

            if (uniqueManualNeeds.isNotEmpty()) {
                _resolvingUnmappedFilament.value = uniqueManualNeeds.first()
                _hasMoreUnmapped.value = uniqueManualNeeds.size > 1
            } else {
                _resolvingUnmappedFilament.value = null
                _hasMoreUnmapped.value = false
            }
        }
    }

    /** 
     * Resolves a batch of unmapped filaments that share the same characteristics 
     * as the provided [filament]. Saves the new mapping to the database.
     */
    fun resolveUnmappedBatch(filament: FilamentInventory, colorName: String, colorRgb: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            val allUnmapped = repository.getUnmappedFilamentsStatic()
            //val targetEntry = allUnmapped.find { it.filamentId == filament.id } ?: return@launch

            // 1. Add to ColorCrossRef if we have a variant
            //if (targetEntry.materialVariantID != null) {
                repository.insertMapping(ColorCrossRef(
                    brand = filament.brand,
                    type = filament.type ?: "Unknown",
                    materialVariantID = filament.materialVariantID,
                    colorName = colorName,
                    colorInt = colorRgb ?: filament.tagColorRgb?:-1,
                    tagColorRgb = filament.tagColorRgb ?: filament.colorRgb?:-1
                ))
            //}

            // 2. Find all unmapped entries that match this one exactly (Same Brand, Type, and Tag Color)
            val matches = allUnmapped.filter { entry ->
                val f = repository.getFilamentByIdStatic(entry.filamentId) ?: return@filter false
                if (filament.tagColorRgb!=null){
                    if (f.brand != filament.brand || f.type != filament.type || f.tagColorRgb!=filament.tagColorRgb ) return@filter false
                }else {
                    if (f.brand != filament.brand || f.type != filament.type || f.colorRgb != filament.colorRgb) return@filter false
                }
                true
            }

            // 3. Update all associated filaments and remove unmapped entries
            matches.forEach { entry ->
                val f = repository.getFilamentByIdStatic(entry.filamentId)
                if (f != null) {
                    repository.updateNoWorker(f.copy(
                        colorName = colorName, 
                        colorRgb = colorRgb ?: f.colorRgb
                    ))
                }
                repository.removeUnmapped(entry.filamentId)
            }

            // 4. Trigger next resolution
            withContext(Dispatchers.Main) {
                resolveNextUnmapped()
            }
        }
    }

    private val _highlightFullSync = MutableStateFlow(false)
    val highlightFullSync: StateFlow<Boolean> = _highlightFullSync.asStateFlow()

    fun setHighlightFullSync(highlight: Boolean) {
        _highlightFullSync.value = highlight
    }

    private val _isScanSheetVisible = MutableStateFlow(false)
    val isScanSheetVisible = _isScanSheetVisible.asStateFlow()

    private val _scannedSpoolData = MutableStateFlow<BambuSpoolData?>(null)
    val scannedSpoolData = _scannedSpoolData.asStateFlow()
    private val _scannedExistingFilament = MutableStateFlow<FilamentInventory?>(null)
    val scannedExistingFilament = _scannedExistingFilament.asStateFlow()
    private val _scanSummary = MutableStateFlow<String?>(null)
    val scanSummary = _scanSummary.asStateFlow()

    /** Toggles the visibility of the NFC scanning bottom sheet. */
    fun setShowScanSheet(show: Boolean) {
        _isScanSheetVisible.value = show
        if (show) {
            _nfcScanningState.value = NfcStatus.Scanning
        } else {
            _nfcScanningState.value = NfcStatus.Idle
            _scannedSpoolData.value = null // Clear the structured data
            _scannedExistingFilament.value = null
            _scanSummary.value = null      // Clear the summary string
        }
    }

    /**
     * Processes raw data from a scanned NFC tag.
     * Updates the UI state with the tag's summary and attempts to look up 
     * existing records for the same physical spool in the database.
     * 
     * @param summary A human-readable text summary of the scanned tag.
     * @param spoolData Decoded Bambu Lab RFID data structure.
     */
    fun processNfcData(summary: String, spoolData: BambuSpoolData?) {
        _scanSummary.value = summary
        _scannedSpoolData.value = spoolData

        if (spoolData != null) {
            _nfcScanningState.value = NfcStatus.Success
            Log.d("napps_NFC", "Success: ${spoolData.filamentType} detected.")
            viewModelScope.launch(Dispatchers.IO) {
                val existing = repository.getFilamentByTrayUidStatic(spoolData.trayUid)
                _scannedExistingFilament.value = existing
            }
        } else {
            _nfcScanningState.value = NfcStatus.Error("Could not decode Bambu Data")
            _scannedExistingFilament.value = null
            viewModelScope.launch {
                delay(1500)
                retryScan()
            }
        }
    }

    /** Manually resets the NFC state to Scanning. */
    fun retryScan() {
        _nfcScanningState.value = NfcStatus.Scanning
        _scannedSpoolData.value = null
        _scannedExistingFilament.value = null
        _scanSummary.value = null
    }

    // --- POTENTIAL OUT OF STOCK ---

    /** Filaments flagged as potentially low on material but not yet marked as OOS. */
    val potentialOutOfStockFilaments: StateFlow<List<FilamentInventory>> = combine(
        repository.getPotentialOutOfStockFlow(),
        repository.getAllFilaments().asFlow()
    ) { potentialList, allFilaments ->
        val potentialIds = potentialList.map { it.filamentId }.toSet()
        allFilaments.filter { it.id in potentialIds && it.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Alias for potentialOutOfStockFilaments used for low stock alerts. */
    val lowStockFilaments: StateFlow<List<FilamentInventory>> = potentialOutOfStockFilaments

    /** Clears the low stock warning for a specific spool. */
    fun removePotentialOutOfStock(filamentID: Int){
        viewModelScope.launch {
            repository.removePotentialOutOfStock(filamentID)
        }
    }

    // --- UNMAPPED FILAMENTS ---

    /** Filaments that are missing critical color or material metadata. */
    val unmappedFilaments: StateFlow<List<FilamentInventory>> = combine(
        repository.getUnmappedFilamentsFlow(),
        repository.getAllFilaments().asFlow()
    ) { unmappedList, allFilaments ->
        val unmappedIds = unmappedList.map { it.filamentId }.toSet()
        allFilaments.filter { it.id in unmappedIds }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Dismisses the unmapped status for a spool. */
    fun removeUnmapped(filamentID: Int) {
        viewModelScope.launch {
            repository.removeUnmapped(filamentID)
        }
    }

    // --- REPOSITORY CRUD ---

    /** Inserts a new spool and adds it to the current session's visible set. */
    suspend fun insert(filament: FilamentInventory) {
        val newId = repository.insert(filament).toInt()
        _sessionVisibleIds.update { it + newId }
        userPrefs.setHasAddedFilament(true)
    }
    
    /** Deletes a spool and updates the global 'has added filament' preference. */
    suspend fun delete(filament: FilamentInventory) {
        repository.delete(filament)
        val remaining = repository.getAllFilamentsStatic().count { it.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK }
        userPrefs.setHasAddedFilament(remaining > 0)
    }

    /** Updates a spool's properties and refreshes the 'has added filament' preference. */
    suspend fun update(filament: FilamentInventory) {
        repository.update(filament)
        val remaining = repository.getAllFilamentsStatic().count { it.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK }
        userPrefs.setHasAddedFilament(remaining > 0)
    }

    /** Clears the entire inventory. */
    suspend fun deleteAll(){
        repository.getAllFilaments().value?.forEach {
            repository.delete(it)
        }
        userPrefs.setHasAddedFilament(false)
    }

    /** Returns LiveData for all spools. */
    fun getAllFilaments(): LiveData<List<FilamentInventory>> = repository.getAllFilaments()

    /** Returns a static list of all spools. */
    suspend fun getAllFilamentsStatic(): List<FilamentInventory> = repository.getAllFilamentsStatic()

    /** Deletes all spools matching a specific manufacturer SKU. */
    suspend fun deleteBySkuStatic(sku: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = repository.getAllFilamentsStatic()
            all.filter { it.materialVariantID == sku }.forEach {
                repository.delete(it)
            }
        }
    }

    /** Counts spools for a specific brand asynchronously. */
    fun countByBrand(brand: String, onResult: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = repository.countByBrand(brand)
            withContext(Dispatchers.Main) {
                onResult(count)
            }
        }
    }

    /** Deletes all spools for a specific brand and updates global preferences. */
    fun deleteByBrand(brand: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteByBrand(brand)
            val remaining = repository.getAllFilamentsStatic().count { it.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK }
            userPrefs.setHasAddedFilament(remaining > 0)
        }
    }


    /** Finds a spool by its NFC UID statically. */
    suspend fun getFilamentByTrayUidStatic(trayUid: String): FilamentInventory? {
        return repository.getFilamentByTrayUidStatic(trayUid)
    }

    /** Returns LiveData for a spool matching the NFC UID. */
    fun getFilamentByTrayUid(trayUid: String): LiveData<FilamentInventory> {
        return repository.getFilamentByTrayUid(trayUid)
    }

    // Helper to safely parse color strings from Bambu's format to Android Color Ints.
    private fun safeParseColor(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null

        // 1. Clean the string
        val cleanHex = hex.removePrefix("#")

        return try {
            val argbHex = when (cleanHex.length) {
                8 -> {
                    // Input: RRGGBBAA (e.g., 112233FF)
                    val rgb = cleanHex.take(6) // 112233
                    val alpha = cleanHex.substring(6, 8) // FF
                    alpha + rgb // Result: FF112233 (AARRGGBB)
                }
                6 -> "FF$cleanHex" // No alpha provided, assume fully opaque
                else -> cleanHex
            }

            "#$argbHex".toColorInt()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Creates a new [FilamentInventory] record from scanned Bambu Lab RFID data.
     * Automatically maps material IDs to human-readable names and hex colors.
     * 
     * @return A Pair containing the LiveData for the inserted filament and a 
     * boolean indicating if it was a new insertion (true) or an existing record (false).
     */
    suspend fun insertScannedFilamentBambuLab(
        filamentData: BambuSpoolData?,
        colorNameOverride: String? = null,
        colorRgbOverride: Int? = null
    ): Pair<LiveData<FilamentInventory>, Boolean> {
        //check if filament exist by trayUID

        val existing = getFilamentByTrayUidStatic(filamentData?.trayUid ?: "")
        if (existing == null) {
            val colorRgb = safeParseColor(filamentData?.colorHex)

            // 1. Add to ColorCrossRef if we have an override
            if (colorNameOverride != null && colorRgbOverride != null && colorRgb != null) {
                repository.insertMapping(ColorCrossRef(
                    brand = "Bambu Lab",
                    type = filamentData?.detailedType ?: "Unknown",
                    materialVariantID = filamentData?.materialVariantId ?: "",
                    tagColorRgb = colorRgb,
                    colorName = colorNameOverride,
                    colorInt = colorRgbOverride
                ))
            }

            val colorRef = if (colorRgb != null && filamentData?.materialVariantId != null) {
                repository.getColorCrossRef("Bambu Lab", filamentData.detailedType ?: "Unknown", filamentData.materialVariantId, colorRgb)
            } else if (colorRgb != null) {
                repository.getColorByTagInt("Bambu Lab", filamentData?.detailedType ?: "Unknown", colorRgb)
            } else null

            val filament = FilamentInventory(
                brand = "Bambu Lab",
                type = filamentData?.detailedType,
                materialVariantID = filamentData?.materialVariantId,
                materialID = filamentData?.materialId,
                diameter = filamentData?.diameterMm,
                colorName = colorNameOverride ?: colorRef?.colorName ?: "Unknown",
                colorRgb = colorRgbOverride ?: colorRef?.colorInt ?: colorRgb,
                tagColorRgb = colorRgb,
                trayUID = filamentData?.trayUid,
                timestamp = System.currentTimeMillis(),
                weight = (filamentData?.totalWeightG?:1000).toString() + " g",
                filamentLength = filamentData?.filamentLengthM,
                availabilityStatus = AvailabilityStatus.NEW
            )
            val newId = repository.insert(filament).toInt()
            _sessionVisibleIds.update { it + newId }
            userPrefs.setHasAddedFilament(true)
            return Pair(getFilamentByTrayUid(filamentData?.trayUid?:""),true)
        } else {
            return Pair(getFilamentByTrayUid(filamentData?.trayUid?:""),false)
        }
    }
    
    // --- FILTERING & PAGING ---

    private val _filterState = MutableStateFlow(FilamentFilters())
    val filterState = _filterState.asStateFlow()

    /** Provides a paged flow of inventory items that react to the current [filterState]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pagedFilteredFilaments: Flow<PagingData<FilamentInventory>> = _filterState
        .flatMapLatest { filters ->
            repository.getFilteredFilamentsPaged(filters)
        }
        .cachedIn(viewModelScope)

    /** Reactive list of available brand filters. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val brandOptions = _filterState.flatMapLatest { repository.getBrandOptions(it) }
    /** Reactive list of available material type filters. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val typeOptions = _filterState.flatMapLatest { repository.getTypeOptions(it) }
    /** Reactive list of available color name filters. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val colorOptions = _filterState.flatMapLatest { repository.getColorOptions(it) }
    /** Reactive list of available spool weight filters. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val weightOptions = _filterState.flatMapLatest { repository.getWeightOptions(it) }
    /** Reactive list of available availability status filters. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val availabilityOptions = _filterState.flatMapLatest { repository.getAvailabilityOptions(it) }

    // --- TOGGLE HELPERS ---

    /** Toggles the inclusion of a brand in the active filters. */
    fun toggleBrand(brand: String) {
        val current = _filterState.value.brands.toMutableList()
        if (current.contains(brand)) current.remove(brand) else current.add(brand)
        _filterState.value = _filterState.value.copy(brands = current)
    }

    /** Toggles the inclusion of a material type in the active filters. */
    fun toggleType(type: String) {
        val current = _filterState.value.types.toMutableList()
        if (current.contains(type)) current.remove(type) else current.add(type)
        _filterState.value = _filterState.value.copy(types = current)
    }

    /** Toggles the inclusion of a color in the active filters. */
    fun toggleColor(color: String) {
        val current = _filterState.value.colors.toMutableList()
        if (current.contains(color)) current.remove(color) else current.add(color)
        _filterState.value = _filterState.value.copy(colors = current)
    }

    /** Toggles the inclusion of an availability status in the active filters. */
    fun toggleAvailability(status: Int) {
        val current = _filterState.value.availability.toMutableList()
        if (current.contains(status)) current.remove(status) else current.add(status)
        _filterState.value = _filterState.value.copy(availability = current)
    }

    /** Toggles the inclusion of a weight in the active filters. */
    fun toggleWeight(weight: String) {
        val current = _filterState.value.weights.toMutableList()
        if (current.contains(weight)) current.remove(weight) else current.add(weight)
        _filterState.value = _filterState.value.copy(weights = current)
    }

    /** Resets all inventory filters. */
    fun clearAllFilters() {
        _filterState.value = FilamentFilters()
    }

    /** Clears the brand filter selection. */
    fun clearBrandFilter() {
        _filterState.value = _filterState.value.copy(brands = emptyList())

    }

    /** Clears the material type filter selection. */
    fun clearTypeFilter() {
        _filterState.value = _filterState.value.copy(types = emptyList())
    }

    /** Clears the color filter selection. */
    fun clearColorFilter() {
        _filterState.value = _filterState.value.copy(colors = emptyList())
    }

    /** Clears the availability status filter selection. */
    fun clearStatusFilter() {
        _filterState.value = _filterState.value.copy(availability = emptyList())
    }

    /**
     * Imports a list of filament records into the database.
     * 
     * @param incomingList The list of spools to import.
     * @param replaceDuplicates If true, existing records matching by TrayUID or properties 
     * will be updated. If false, they will be skipped.
     * @param onComplete Callback invoked with the final count of imported/updated items.
     */
    fun importAll(incomingList: List<FilamentInventory>, replaceDuplicates: Boolean, onComplete: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Get the current state of the DB to compare
            val existingList = repository.getAllFilamentsStatic() // You'll need this in Repository
            var importedCount = 0
            incomingList.forEach { incoming ->
                // 2. Find if this spool already exists based on your rules
                val existingMatch = existingList.find { existing ->
                    if (!incoming.trayUID.isNullOrBlank()) {
                        // Rule 1: Scanned spools match by TrayUID
                        existing.trayUID == incoming.trayUID
                    } else {
                        // Rule 2: Manual spools match by "Same Data" (Brand, Type, Color, etc.)
                        existing.brand == incoming.brand &&
                                existing.type == incoming.type &&
                                existing.colorRgb == incoming.colorRgb &&
                                existing.trayUID.isNullOrBlank() // Ensure we aren't matching a scanned one
                    }
                }

                if (existingMatch != null) {
                    // IT'S A DUPLICATE
                    if (replaceDuplicates) {
                        // Update the existing record (maintain the existing ID)
                        val updatedItem = incoming.copy(id = existingMatch.id)
                        repository.update(updatedItem)
                        importedCount++
                    } else {
                        // Ignore: Do nothing for this item
                        Log.d("Import", "Ignoring duplicate: ${incoming.brand}")
                    }
                } else {
                    // IT'S NEW
                    // Force ID to 0 to ensure Room creates a new entry and doesn't overwrite an existing ID
                    val newId = repository.insert(incoming.copy(id = 0)).toInt()
                    _sessionVisibleIds.update { it + newId }
                    userPrefs.setHasAddedFilament(true)
                    importedCount++
                }

            }

// Switch back to Main thread to trigger the UI callback
            withContext(Dispatchers.Main) {
                onComplete(importedCount)
            }
        }
    }

    /** Finds inventory spools matching a specific vendor filament definition. */
    fun getMatchingInventorySpools(brand: String?, type: String?, colorName: String?): LiveData<List<FilamentInventory>> {
        return repository.getMatchingInventorySpools(brand ?: "", type ?: "", colorName ?: "")
    }

    /**
     * Finds the human-readable color name for a specific filament variant.
     */
    suspend fun getColorNameByVariant(variantId: String?): String? {
        if (variantId == null) return null
        return repository.getColorName("Bambu Lab", "Unknown", variantId, 0) // Placeholder logic, should be improved with full context
    }

    // Identifies if a spool lacks both material type and a valid color name.
    private fun isUnknownSpool(spool: FilamentInventory): Boolean {
        val typeEmpty = spool.type.isNullOrBlank() || spool.type == "Unknown"
        val nameUnknown = spool.colorName?.contains("unknown", ignoreCase = true) == true
        return typeEmpty && nameUnknown
    }

    // Calculates the hue of a color for rainbow-order sorting.
    private fun getHue(colorInt: Int?): Float {
        if (colorInt == null) return 361f
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(colorInt, hsv)
        
        // Rainbow order adjustment:
        // Grayscale (low saturation) should ideally be separate.
        // Let's put low saturation colors at the end.
        if (hsv[1] < 0.1f) {
            return 400f + (1f - hsv[2]) // Sort by brightness if gray, after 0-360 range
        }
        return hsv[0]
    }

    /**
     * A reactive summary of the entire inventory, including counts for various 
     * statuses and total weight in kilograms.
     */
    val inventorySummary: StateFlow<InventorySummary> = repository.getAllFilaments().asFlow()
        .map { list ->
            val filteredList = list.filterNot { isUnknownSpool(it) }
            val totalSpools = filteredList.size
            val oos = filteredList.count { it.availabilityStatus == AvailabilityStatus.OUT_OF_STOCK }
            val ams = filteredList.count { it.availabilityStatus == AvailabilityStatus.IN_AMS }
            val open = filteredList.count { it.availabilityStatus == AvailabilityStatus.OPEN }
            val inUse = filteredList.count { it.availabilityStatus == AvailabilityStatus.IN_USE }
            val new = filteredList.count { it.availabilityStatus == AvailabilityStatus.NEW }
            
            val totalWeightG = filteredList.sumOf { spool ->
                val weightStr = spool.weight?.replace(Regex("[^0-9]"), "") ?: "1000"
                val totalWeight = weightStr.toDoubleOrNull() ?: 1000.0
                totalWeight * (spool.usedPercent ?: 1.0f)
            }
            InventorySummary(totalSpools, oos, ams, open, inUse, new, totalWeightG / 1000.0)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InventorySummary(0, 0, 0, 0, 0, 0, 0.0))

    /**
     * Grouped and sorted view of the inventory for the main screen.
     * Filaments are grouped by Vendor, then by Type, and sorted by color (rainbow order).
     * Respects the "Show Empty Spools" user preference and maintains visibility 
     * for newly added or OOS-marked spools in the current session until a toggle occurs.
     */
    val groupedFilaments: StateFlow<List<VendorGroup>> = combine(
        repository.getAllFilaments().asFlow(),
        showEmptySpools,
        _sessionVisibleIds,
        unmappedFilaments,
        potentialOutOfStockFilaments
    ) { list, showEmpty, sessionIds, unmapped, poos ->
        val unmappedIds = unmapped.map { it.id }.toSet()
        val poosIds = poos.map { it.id }.toSet()

        list.filterNot { isUnknownSpool(it) }
            .filter { spool ->
                showEmpty || spool.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK || spool.id in sessionIds
            }
            .groupBy { it.brand }
            .map { (brand, vendorFilaments) ->
                val typeGroups = vendorFilaments.groupBy { it.type ?: "Unknown" }
                    .map { (type, typeFilaments) ->
                        val massG = typeFilaments.sumOf { spool ->
                            val weightStr = spool.weight?.replace(Regex("[^0-9]"), "") ?: "1000"
                            val totalWeight = weightStr.toDoubleOrNull() ?: 1000.0
                            totalWeight * (spool.usedPercent ?: 1.0f)
                        }
                        val active = typeFilaments.count { it.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK }
                        
                        // Sort filaments by "Rainbow Order" (Hue) then by internal ID (Smallest first)
                        val sortedFilaments = typeFilaments.sortedWith(
                            compareBy<FilamentInventory> { getHue(it.colorRgb) }
                                .thenBy { it.id }
                        )

                        val groupHasUnmapped = typeFilaments.any { it.id in unmappedIds }
                        val groupHasPoos = typeFilaments.any { it.id in poosIds }
                        val groupUnmappedIds = typeFilaments.filter { it.id in unmappedIds }.map { it.id }.toSet()

                        FilamentGroup(type, sortedFilaments, massG, active, groupHasUnmapped, groupHasPoos, groupUnmappedIds)
                    }.sortedBy { it.type }
                
                val totalMassG = typeGroups.sumOf { it.totalMassG }
                val totalActive = typeGroups.sumOf { it.activeSpools }
                val vendorHasUnmapped = typeGroups.any { it.hasUnmapped }
                val vendorHasPoos = typeGroups.any { it.hasPotentialOos }
                VendorGroup(brand, typeGroups, totalMassG, totalActive, vendorHasUnmapped, vendorHasPoos)
            }.sortedBy { it.vendor }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** List of all brands currently represented in the user's inventory. */
    val inventoryBrands: StateFlow<List<String>> = groupedFilaments
        .map { vendors -> vendors.map { it.vendor }.distinct().sorted() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Marks a list of spools as Out of Stock and sets their used percentage to 0. */
    fun markAllOutOfStock(filaments: List<FilamentInventory>) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            filaments.forEach { filament ->
                repository.update(filament.copy(
                    availabilityStatus = AvailabilityStatus.OUT_OF_STOCK, 
                    usedPercent = 0f,
                    oosTimestamp = now
                ))
                repository.removePotentialOutOfStock(filament.id)
            }
        }
    }

    /** Updates the low stock threshold and persists it to user preferences. */
    fun setLowFilamentThresholdG(threshold: Int) {
        viewModelScope.launch {
            userPrefs.saveLowFilamentThresholdG(threshold)
        }
    }


    /** Attempts to auto-resolve all unmapped filaments across the entire database. */
    fun resolveAllUnmapped() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.resolveAllUnmapped()
        }
    }
}

/**
 * Factory class for creating [FilamentInventoryViewModel] instances with 
 * required dependencies.
 */
class FilamentInventoryViewModelFactory(
    private val repository: FilamentInventoryRepository,
    private val userPrefs: UserPreferencesRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FilamentInventoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FilamentInventoryViewModel(repository, userPrefs) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
