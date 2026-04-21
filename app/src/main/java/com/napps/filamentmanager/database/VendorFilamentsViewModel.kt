package com.napps.filamentmanager.database

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for managing the database of all known filament products and user availability trackers.
 * 
 * This class handles:
 * - Reactive filtering of the large vendor filament database (Paging 3).
 * - Management of "Availability Trackers" which monitor stock status for specific filaments.
 * - Integration with [UserPreferencesRepository] for onboarding status.
 * - Dynamic UI text management for the Availability screen.
 */
class VendorFilamentsViewModel(
    private val repository: VendorFilamentsRepository,
    private val userPrefs: UserPreferencesRepository
) : ViewModel() {

    val hasFirstSyncFinished = userPrefs.hasFirstSyncFinishedFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    fun getMenuText(name:String): LiveData<AvailabilityMenuText> = repository.getMenuText(name)

    suspend fun getMenuTextStatic(name: String): AvailabilityMenuText? = repository.getMenuTextStatic(name)

    suspend fun insert(availabilityMenuText: AvailabilityMenuText) {
        repository.insert(availabilityMenuText)
    }
    suspend fun update(availabilityMenuText: AvailabilityMenuText) {
        repository.update(availabilityMenuText)
    }

    /**
     * Resets the error state for trackers, effectively triggering a re-check
     * of filament availability.
     */
    fun refreshAllTrackers() = viewModelScope.launch {
            repository.getByError(-1)
    }

    // --- UI State ---
    val shouldExpandReady = MutableLiveData(false)

    fun setExpandReady(expand: Boolean) {
        shouldExpandReady.value = expand
    }

    // --- Filter State ---
     val searchText = MutableLiveData("")
     val selectedBrand = MutableLiveData<String?>("Any")
     val selectedType = MutableLiveData<String?>("Any")
     val selectedColorInfo = MutableLiveData<ColorInfo?>(null)
     val selectedPackageType = MutableLiveData<String?>("Any")
     val selectedWeight = MutableLiveData<String?>(null)

    // --- Combined flow for filters ---
    /**
     * Combined flow of all active filters used to narrow down the vendor filament list.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val filtersFlow = combine(
        searchText.asFlow(),
        selectedBrand.asFlow(),
        selectedType.asFlow(),
        selectedColorInfo.asFlow(),
        selectedPackageType.asFlow(),
        selectedWeight.asFlow()
    ) { filterValues ->
        val text = filterValues[0] as String
        val brand = filterValues[1] as String?
        val type = filterValues[2] as String?
        val color = filterValues[3] as ColorInfo?
        val packageType = filterValues[4] as String?
        val weight = filterValues[5] as String?

        FilterParams(text, brand, type, color, packageType, weight)
    }

    // --- Reactive LiveData for populating filter dropdowns ---
    @OptIn(ExperimentalCoroutinesApi::class)
    val uniqueBrands: LiveData<List<String>> = filtersFlow.flatMapLatest { params ->
        repository.getUniqueBrands(params.searchText, params.type, params.colorInfo, params.packageType,params.weight).asFlow()
    }.asLiveData()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uniqueTypes: LiveData<List<String>> = filtersFlow.flatMapLatest { params ->
        repository.getUniqueTypes(params.searchText,params.brand,  params.colorInfo, params.packageType,params.weight).asFlow()
    }.asLiveData()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uniqueColors: LiveData<List<ColorInfo>> = filtersFlow.flatMapLatest { params ->
        repository.getUniqueColors(params.searchText,params.brand, params.type,  params.packageType,params.weight).asFlow()
    }.asLiveData()

    val allBrands: LiveData<List<String>> = repository.getUniqueBrands("", null, null, null, null)
    val allTypes: LiveData<List<String>> = repository.getUniqueTypes("", null, null, null, null)
    val allColorInfo: LiveData<List<ColorInfo>> = repository.getUniqueColors("", null, null, null, null)

    fun getTypesByBrand(brand: String?): LiveData<List<String>> {
        return repository.getUniqueTypes("", brand, null, null, null)
    }

    fun getColorsByTypeAndBrand(type: String?, brand: String?): LiveData<List<ColorInfo>> {
        return repository.getUniqueColors("", brand, type, null, null)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uniquePackageTypes: LiveData<List<String>> = filtersFlow.flatMapLatest { params ->
        repository.getUniquePackageTypes(params.searchText,params.brand, params.type, params.colorInfo,params.weight).asFlow()
    }.asLiveData()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uniqueWeights: LiveData<List<String>> = filtersFlow.flatMapLatest { params ->
        repository.getUniqueWeights(params.searchText,params.brand, params.type, params.colorInfo, params.packageType).asFlow()
    }.asLiveData()

    val allVendorFilaments: LiveData<List<VendorFilament>> = repository.getAllVendorFilaments

    /**
     * Efficiently checks if the vendor catalog has any entries.
     */
    val hasAnyFilaments: LiveData<Boolean> = repository.hasAnyFilaments

    /**
     * Reactive PagingData flow for the vendor filament list.
     * Automatically updates when any filter (text, brand, type, etc.) changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredFilamentsPaging: Flow<PagingData<VendorFilament>> = combine(
    searchText.asFlow(),
    selectedBrand.asFlow(),
    selectedType.asFlow(),
    selectedColorInfo.asFlow(),
    selectedPackageType.asFlow(),
    selectedWeight.asFlow()
) { filterValues ->
    val text = filterValues[0] as String
    val brand = filterValues[1] as String?
    val type = filterValues[2] as String?
    val color = filterValues[3] as ColorInfo?
    val packageType = filterValues[4] as String?
    val weight = filterValues[5] as String?

    FilterParams(text, brand, type, color, packageType, weight)
}.flatMapLatest { params ->
    Pager(
        config = PagingConfig(
            pageSize = 20, 
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            repository.getFilteredFilamentListDataPaging(
                searchText = params.searchText ?: "",
                brand = params.brand,
                type = params.type,
                colorInfo = params.colorInfo,
                packageType = params.packageType,
                weight = params.weight
            )
        }
    ).flow
}.cachedIn(viewModelScope) 


    private data class FilterParams(
        val searchText: String,
        val brand: String?,
        val type: String?,
        val colorInfo: ColorInfo?,
        val packageType: String?,
        val weight: String?
    )

    val TrackersWithNotifications: LiveData<List<TrackerWithFilaments>> = repository.getAllTrackersWithNotifications().asFlow().asLiveData()

    suspend fun getAllTrackersWithNotificationsStatic(): List<TrackerWithFilaments> {
        return repository.getAllTrackersWithNotificationsStatic()
    }

    // --- Public functions for the UI to call ---
    fun setSearchText(text: String) {
        searchText.value = text
    }

    fun setSelectedBrand(brand: String?) {
        selectedBrand.value = brand
    }

    fun setSelectedType(type: String?) {
        selectedType.value = type
    }

    fun setSelectedColor(colorInfo: ColorInfo?) {
        selectedColorInfo.value = colorInfo
    }

    fun setSelectedPackageType(packageType: String?) {
        selectedPackageType.value = packageType
    }

    /**
     * Updates the selected weight filter for the filament catalog.
     */
    fun setSelectedWeight(weight: String?) {
        selectedWeight.value = weight
    }

    /**
     * Resets the filter values to their defaults, clearing searches and selections.
     */
    fun clearFilters() {
        searchText.value = ""
        selectedBrand.value = "Any"
        selectedType.value = "Any"
        selectedColorInfo.value = null
        selectedPackageType.value = "Any"
        selectedWeight.value = null
    }

    fun insert(vendorFilament: VendorFilament) = viewModelScope.launch {
        repository.insert(vendorFilament)
    }
    fun update(vendorFilament: VendorFilament) = viewModelScope.launch {
        repository.update(vendorFilament)
    }
    fun delete(vendorFilament: VendorFilament) = viewModelScope.launch {
        repository.delete(vendorFilament)
    }
    fun insertOrUpdate(filament: VendorFilament) {
        viewModelScope.launch {
            repository.insertOrUpdate(filament)
        }
    }


    // --- Functions for Availability trackers
    fun insert(availabilityTracker: AvailabilityTracker) = viewModelScope.launch {
        repository.insert(availabilityTracker)
        userPrefs.setHasAddedTracker(true)
        TrackersTrigger.value = Unit
    }
    fun update(availabilityTracker: AvailabilityTracker) = viewModelScope.launch {
        repository.update(availabilityTracker)
        TrackersTrigger.value = Unit
    }
    fun delete(availabilityTracker: AvailabilityTracker) = viewModelScope.launch {
        repository.delete(availabilityTracker)
        TrackersTrigger.value = Unit
        val remaining = repository.getAllAvailabilityTrackersStatic().size
        userPrefs.setHasAddedTracker(remaining > 0)
    }

    fun addFilamentToTracker(trackerId: Int, filamentId: Int) = viewModelScope.launch {
        repository.addFilamentToTracker(trackerId, filamentId)
        TrackersTrigger.value = Unit
    }

    fun removeFilamentFromTracker(trackerId: Int, filamentId: Int) = viewModelScope.launch {
        repository.removeFilamentFromTracker(trackerId, filamentId)
        TrackersTrigger.value = Unit
    }


    private val TrackersTrigger = MutableLiveData(Unit)

    val allTrackersWithFilaments: LiveData<List<TrackerWithFilaments>> = TrackersTrigger.switchMap {
        repository.getAllAvailabilityTrackers()
    }

    suspend fun getAllAvailabilityTrackersStatic(): List<TrackerWithFilaments> {
        return repository.getAllAvailabilityTrackersStatic()
    }

    fun insertTrackerWithFilaments(tracker: AvailabilityTracker, filamentIds: List<Int>) = viewModelScope.launch(Dispatchers.IO) {
        repository.insertTrackerWithFilaments(tracker, filamentIds)
        userPrefs.setHasAddedTracker(true)
        withContext(Dispatchers.Main) {
            TrackersTrigger.value = Unit
        }
    }

    suspend fun insertTrackerWithFilamentsStatic(tracker: AvailabilityTracker, filamentIds: List<Int>) {
        repository.insertTrackerWithFilaments(tracker, filamentIds)
        userPrefs.setHasAddedTracker(true)
        withContext(Dispatchers.Main) {
            TrackersTrigger.value = Unit
        }
    }

    suspend fun deleteTrackerBySkuStatic(sku: String) {
        val allTrackers = repository.getAllAvailabilityTrackersStatic()
        val toDelete = allTrackers.filter { tracker ->
            tracker.filaments.any { it.sku == sku }
        }
        toDelete.forEach {
            repository.delete(it.tracker)
        }
        val remaining = (allTrackers.size - toDelete.size)
        userPrefs.setHasAddedTracker(remaining > 0)
        withContext(Dispatchers.Main) {
            TrackersTrigger.value = Unit
        }
    }
    fun updateTrackerWithFilaments(tracker: TrackerWithFilaments, filamentIds: List<Int>) = viewModelScope.launch {
        repository.updateTrackerWithFilaments(tracker, filamentIds)
        TrackersTrigger.value = Unit
    }

    suspend fun getFilamentBySkuStatic(sku: String) : VendorFilament? {
        return repository.getFilamentBySkuStatic(sku)
    }

    fun importRobustTrackers(
        incomingTrackers: List<Pair<Map<String, String>, List<Map<String, String>>>>,
        replaceDuplicates: Boolean,
        onComplete: (Int) -> Unit
    ) {
        viewModelScope.launch {
            repository.importRobustTrackers(incomingTrackers, replaceDuplicates, { count ->
                if (count > 0) {
                    viewModelScope.launch {
                        userPrefs.setHasAddedTracker(true)
                    }
                }
                onComplete(count)
            })
            TrackersTrigger.value = Unit
        }
    }

}

/**
 * Factory for creating [VendorFilamentsViewModel] with required dependencies.
 */
class VendorFilamentsViewModelFactory(
    private val repository: VendorFilamentsRepository,
    private val userPrefs: UserPreferencesRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VendorFilamentsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VendorFilamentsViewModel(repository, userPrefs) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
