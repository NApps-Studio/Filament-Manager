package com.napps.filamentmanager.database

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.napps.filamentmanager.mqtt.BambuMqttManager
import com.napps.filamentmanager.mqtt.BambuState
import com.napps.filamentmanager.mqtt.BambuUpdateWorker
import com.napps.filamentmanager.util.CryptoManager
import com.napps.filamentmanager.util.SecuritySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ViewModel for managing Bambu Lab printer states and connections.
 *
 * This class serves as the central hub for printer telemetry, combining persistent
 * data from [BambuRepository] with real-time connectivity status from [BambuMqttManager].
 * It handles the initialization of encrypted sessions, manages MQTT lifecycles,
 * and provides a unified [printerStates] flow for the UI.
 *
 * Key responsibilities:
 * - Observing network connectivity to trigger reconnections.
 * - Decrypting printer serials and access tokens into the [SecuritySession].
 * - Orchestrating background synchronization via [BambuUpdateWorker].
 * - Mapping raw MQTT JSON updates to the local filament inventory.
 */
class BambuViewModel(
    application: Application,
    private val repository: BambuRepository
) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)
    
    // Live Connectivity RAM Store
    private val _connectivityStates = MutableStateFlow<Map<String, Pair<Boolean, String>>>(emptyMap())
    
    // UI Source of Truth: Merges Database Printer Data with Live Connectivity Status
    /**
     * The primary source of truth for the UI.
     * Combines database entities with live connectivity status and decrypted serials.
     */
    val printerStates: StateFlow<Map<String, BambuState>> = combine(
        repository.getAllPrinterStatusesFlow(),
        _connectivityStates,
        SecuritySession.decryptedPrintersFlow
    ) { dbEntities, liveConn, serialMap ->
        dbEntities.associate { entity ->
            val rawSn = serialMap[entity.hashedSerialNumber]
            val state = repository.toBambuState(entity, rawSn) ?: BambuState(serial = rawSn ?: "")
            val conn = liveConn[entity.hashedSerialNumber] ?: Pair(false, "Disconnected")
            
            entity.hashedSerialNumber to state.copy(
                isConnected = conn.first,
                connectionStatus = conn.second
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val workInfos = workManager.getWorkInfosForUniqueWorkLiveData("BambuUpdateWork")

    private val _debugWorkId = MutableLiveData<UUID?>(null)
    val debugWorkInfo: LiveData<WorkInfo?> = _debugWorkId.switchMap { id ->
        if (id != null) workManager.getWorkInfoByIdLiveData(id) else MutableLiveData(null)
    }

    /**
     * Observable list of all registered printers from the database.
     */
    val allPrinters = repository.getPrintersFlow().asLiveData()

    private val _authData = MutableStateFlow<BambuLab?>(null)
    /**
     * Flow of the current Bambu Lab account authentication data.
     */
    val authData = _authData.asStateFlow()

    private val _isTokenExpired = MutableStateFlow(false)
    /**
     * State indicating if the current MQTT access token is expired or unauthorized.
     */
    val isTokenExpired = _isTokenExpired.asStateFlow()

    private val _isDecryptionFailed = MutableStateFlow(false)
    /**
     * State indicating if the Keystore keys don't match the saved data.
     */
    val isDecryptionFailed = _isDecryptionFailed.asStateFlow()

    private val managers = mutableMapOf<String, BambuMqttManager>()

    init {
        setupNetworkListener()
        viewModelScope.launch {
            repository.getAuthFlow().collect { savedAuth ->
                _authData.value = savedAuth
                if (savedAuth != null) {
                    autoInitializeSession(savedAuth)
                }
            }
        }
    }

    private fun setupNetworkListener() {
        val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("BambuViewModel", "Internet Available event. Checking printer connections...")
                startLiveUpdates()
            }
        })
    }

    private suspend fun autoInitializeSession(auth: BambuLab) {
        withContext(Dispatchers.IO) {
            try {
                // Defensive check: Ensure encryption fields are actually present
                if (auth.encryptedToken.isBlank() || auth.iv.isBlank()) {
                    Log.e("BambuViewModel", "Auto-init aborted: Missing encrypted token or IV")
                    return@withContext
                }

                val clearToken = CryptoManager.decrypt(auth.encryptedToken, auth.iv)
                SecuritySession.initialize(clearToken, auth.uid, auth.region, auth.isInChina)

                val printers = repository.getAllPrinters()
                val decryptedSerials = printers.filter {
                    it.encryptedSerial.isNotBlank() && it.iv.isNotBlank()
                }.associate { p ->
                    p.hashedSerial to CryptoManager.decrypt(p.encryptedSerial, p.iv)
                }
                SecuritySession.setDecryptedPrinters(decryptedSerials)

                withContext(Dispatchers.Main) {
                    _isDecryptionFailed.value = false
                    startLiveUpdates()
                }
            } catch (e: Exception) {
                Log.e("BambuViewModel", "Auto-init failed due to decryption error (likely Keystore invalidated).", e)
                withContext(Dispatchers.Main) {
                    _isDecryptionFailed.value = true
                }
            }
        }
    }

    /**
     * Triggers the [BambuUpdateWorker] immediately to sync printer status.
     * Used for manual refresh or debugging.
     */
    fun triggerWorkerNow() {
        val request = OneTimeWorkRequestBuilder<BambuUpdateWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        _debugWorkId.value = request.id
        workManager.enqueueUniqueWork("DebugBambuSync", ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun getAuthDataSync(): BambuLab? = repository.getBambuAuth()
    suspend fun getAllPrinters(): List<BambuPrinter> = repository.getAllPrinters()

    /**
     * Initializes MQTT connections for all registered printers using the current
     * [SecuritySession] credentials.
     */
    fun startLiveUpdates() {
        val token = SecuritySession.getAccessToken() ?: return
        val uid = SecuritySession.getUid() ?: return
        val host = if (SecuritySession.getIsInChina()) "cn.mqtt.bambulab.com" else "us.mqtt.bambulab.com"

        viewModelScope.launch {
            val printers = repository.getAllPrinters()
            printers.forEach { printer ->
                val rawSerial = SecuritySession.getRawSerial(printer.hashedSerial) ?: return@forEach
                val existingManager = managers[printer.hashedSerial]

                if (existingManager == null) {
                    Log.d("BambuViewModel", "Creating new manager for ${printer.name}")
                    val manager = BambuMqttManager(
                        context = getApplication(),
                        host = host,
                        userName = uid,
                        accessToken = token,
                        serialNumber = rawSerial,
                        initialState = printerStates.value[printer.hashedSerial],
                        onStateUpdate = { newState, rawJson ->
                            _connectivityStates.value += (printer.hashedSerial to Pair(newState.isConnected, newState.connectionStatus))

                            if (rawJson != "{}" && rawJson.isNotBlank()) {
                                viewModelScope.launch(Dispatchers.IO) {
                                    repository.savePrinterStatus(
                                        hashedSn = printer.hashedSerial,
                                        rawJson = rawJson,
                                        rawSn = rawSerial,
                                        workerSyncTime = null
                                    )
                                    val isAmsReport = rawJson.contains("\"ams\":")
                                    if (newState.amsUnits.isNotEmpty() || newState.vtTray != null) {
                                        repository.syncAmsWithInventory(newState, isAmsReport = isAmsReport)
                                    }
                                }
                            }
                        },
                        onConnectionError = { error ->
                            Log.e("BambuViewModel", "Connection error for ${printer.name}: $error")
                            _connectivityStates.value += (printer.hashedSerial to Pair(false, "Error: $error"))
                            
                            // Detect if error is due to authentication failure (token expired)
                            if (error.contains("not authorized", ignoreCase = true) || 
                                error.contains("auth", ignoreCase = true) ||
                                error.contains("401") || error.contains("403")) {
                                _isTokenExpired.value = true
                            }
                        }
                    )
                    managers[printer.hashedSerial] = manager
                    manager.connect()
                } else if (!existingManager.isConnected()) {
                    Log.d("BambuViewModel", "Reconnecting existing manager for ${printer.name}")
                    existingManager.connect()
                }
            }
        }
    }

    /**
     * Stops all active MQTT connections and clears the connectivity state map.
     */
    fun stopLiveUpdates() {
        managers.values.forEach { it.disconnect() }
        managers.clear()
        val disconnected = _connectivityStates.value.toMutableMap()
        disconnected.keys.forEach { disconnected[it] = Pair(false, "Disconnected") }
        _connectivityStates.value = disconnected
    }

    /**
     * Requests a full status update (report) from all connected printers
     * via their respective MQTT managers.
     */
    fun forceRefreshAll() {
        Log.d("BambuViewModel", "Force refresh all triggered")
        startLiveUpdates() // Ensure connections are alive
        managers.values.forEach { it.requestFullUpdate() }
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveUpdates()
    }

    /**
     * Saves new Bambu Lab account authentication details to the database and initializes the session.
     *
     * @param uid The Bambu account user ID.
     * @param clearToken The plain-text access token (will be encrypted before storage).
     * @param region The synchronization region (EU, US, etc.).
     * @param isInChina Whether to use the China-specific MQTT broker.
     */
    fun saveAuth(uid: String, clearToken: String, region: SyncRegion, isInChina: Boolean) {
        viewModelScope.launch {
            try {
                val encryptionResult = CryptoManager.encrypt(clearToken)
                val newAuth = BambuLab(uid = uid, encryptedToken = encryptionResult.first, iv = encryptionResult.second, region = region, isInChina = isInChina, lastUpdated = System.currentTimeMillis())
                repository.saveBambuAuth(newAuth)
                SecuritySession.initialize(clearToken, uid, region, isInChina)
                _isTokenExpired.value = false
                _isDecryptionFailed.value = false
                stopLiveUpdates()
                startLiveUpdates()
            } catch (e: Exception) {
                Log.e("BambuViewModel", "Failed to save auth: ${e.message}")
            }
        }
    }

    /**
     * Updates account metadata like region and China server preference without changing credentials.
     *
     * @param region The new synchronization region.
     * @param isInChina The new China server preference.
     */
    fun updateAuthMetadata(region: SyncRegion, isInChina: Boolean) {
        val currentAuth = _authData.value ?: return
        val clearToken = SecuritySession.getAccessToken() ?: return
        viewModelScope.launch {
            val updatedAuth = currentAuth.copy(region = region, isInChina = isInChina, lastUpdated = System.currentTimeMillis())
            repository.saveBambuAuth(updatedAuth)
            SecuritySession.initialize(clearToken, currentAuth.uid, region, isInChina)
            stopLiveUpdates()
            startLiveUpdates()
        }
    }

    /**
     * Updates or adds a printer by its raw serial number.
     * Handles hashing the serial for storage and updating the [SecuritySession].
     */
    fun appendPrinterSerial(sn: String, customName: String = "My Printer", userPreferences: UserPreferencesRepository? = null) {
        if (sn.isBlank()) return
        val currentUid = _authData.value?.uid ?: "unknown_user"
        viewModelScope.launch {
            repository.addPrinter(sn, customName, currentUid)
            val hashedSn = repository.hashSerial(sn)
            SecuritySession.addDecryptedPrinter(hashedSn, sn.trim().uppercase())
            userPreferences?.setHasAddedPrinter(true)
            startLiveUpdates()
        }
    }

    /**
     * Updates an existing printer's details by removing the old record and adding a new one.
     *
     * @param oldHashedSn The current hashed serial number in the DB.
     * @param newRawSn The new raw serial number to register.
     * @param newName The new display name for the printer.
     */
    fun updatePrinterInfo(oldHashedSn: String, newRawSn: String, newName: String) {
        val currentUid = _authData.value?.uid ?: "unknown_user"
        viewModelScope.launch {
            repository.removePrinter(oldHashedSn)
            managers[oldHashedSn]?.disconnect()
            managers.remove(oldHashedSn)
            repository.addPrinter(newRawSn, newName, currentUid)
            val newHashedSn = repository.hashSerial(newRawSn)
            SecuritySession.addDecryptedPrinter(newHashedSn, newRawSn.trim().uppercase())
            startLiveUpdates()
        }
    }

    /**
     * Removes a printer from the database and terminates its MQTT connection.
     *
     * @param hashedSn The hashed serial number of the printer to remove.
     * @param userPreferences Optional preferences repository to update the "has added printer" flag.
     */
    fun removePrinter(hashedSn: String, userPreferences: UserPreferencesRepository? = null) {
        viewModelScope.launch {
            repository.removePrinter(hashedSn)
            managers[hashedSn]?.disconnect()
            managers.remove(hashedSn)
            val newConn = _connectivityStates.value.toMutableMap()
            newConn.remove(hashedSn)
            _connectivityStates.value = newConn
            
            val remaining = repository.getAllPrinters().size
            userPreferences?.setHasAddedPrinter(remaining > 0)
        }
    }

    fun clearBambuAuth() {
        viewModelScope.launch {
            stopLiveUpdates()
            repository.clearAuth()
            repository.clearAllPrinters()
            SecuritySession.clear()
            _connectivityStates.value = emptyMap()
            _isTokenExpired.value = false
            _isDecryptionFailed.value = false
        }
    }

    fun debugTriggerRunoutCheck(state: BambuState) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.debugTriggerRunoutCheck(state)
        }
    }

    fun bambuDao() = repository.bambuDao()

    fun reEvaluateLowStockWarnings() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.reEvaluateLowStockWarnings()
        }
    }
}

    /**
     * Factory for creating [BambuViewModel] instances with the required dependencies.
     */
class BambuViewModelFactory(private val application: Application, private val repository: BambuRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BambuViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BambuViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
