package com.napps.filamentmanager.database

import android.content.Context
import android.util.Log
import androidx.core.graphics.toColorInt
import com.google.gson.Gson
import com.napps.filamentmanager.mqtt.BambuMqttManager
import com.napps.filamentmanager.mqtt.BambuState
import com.napps.filamentmanager.util.CryptoManager
import com.napps.filamentmanager.util.SecuritySession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.security.MessageDigest


/**
 * Central repository for managing all Bambu Lab printer-related data.
 * Handles printer registration, authentication, and telemetry storage.
 */
class BambuRepository(
    private val bambuDao: BambuDao, 
    private val inventoryDao: FilamentInventoryDAO,
    private val userPrefs: UserPreferencesRepository,
    private val context: Context 
) {
    private val gson = Gson()

    /**
     * Generates a SHA-256 hash of the printer's serial number for privacy-safe identification.
     */
    fun hashSerial(serial: String): String {
        if (serial.isBlank()) return ""
        val bytes = serial.trim().uppercase().toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    /**
     * Retrieves the authentication data flow for the Bambu Lab account.
     * @return A Flow of the current [BambuLab] authentication record.
     */
    fun getAuthFlow() = bambuDao.getAuthFlow()

    /**
     * Gets the current Bambu Lab authentication data.
     * @return The [BambuLab] authentication record, or null if not set.
     */
    suspend fun getBambuAuth() = bambuDao.getAuth()

    /**
     * Saves the Bambu Lab authentication data.
     * @param auth The [BambuLab] record to save.
     */
    suspend fun saveBambuAuth(auth: BambuLab) = bambuDao.saveAuth(auth)

    /**
     * Clears all printer and authentication data from the local database.
     */
    suspend fun clearAuth() {
        bambuDao.deleteAuth()
        bambuDao.deleteAllPrinters()
        bambuDao.deleteAllPrinterStatuses()
        bambuDao.clearAllSlotHistory()
    }

    /**
     * Updates the display name for a specific printer.
     * @param hashedSn The SHA-256 hash of the printer's serial number.
     * @param newName The new name to assign to the printer.
     */
    suspend fun updatePrinterName(hashedSn: String, newName: String) {
        bambuDao.updatePrinterName(hashedSn, newName)
    }

    /**
     * Retrieves a printer by its hashed serial number.
     * @param hashedSn The SHA-256 hash of the serial number.
     * @return A LiveData containing the [BambuPrinter], or null.
     */
    fun getPrinter(hashedSn: String) = bambuDao.getPrinter(hashedSn)

    /**
     * Deletes all printer records from the database.
     */
    suspend fun clearAllPrinters() = bambuDao.deleteAllPrinters()

    /**
     * Provides a Flow of all registered printers.
     * @return A Flow containing a list of all [BambuPrinter]s.
     */
    fun getPrintersFlow() = bambuDao.getAllPrintersFlow()

    /**
     * Gets a list of all registered printers.
     * @return A static list of all [BambuPrinter]s.
     */
    suspend fun getAllPrinters() = bambuDao.getAllPrinters()
    
    /**
     * Registers a new printer by encrypting its serial number and generating a unique hash.
     *
     * @param rawSerial The plain-text serial number of the printer.
     * @param name The display name for the printer.
     * @param accountUid The Bambu account UID this printer is linked to.
     */
    suspend fun addPrinter(rawSerial: String, name: String, accountUid: String) {
        val hashedSn = hashSerial(rawSerial)
        val encryptedResult = CryptoManager.encrypt(rawSerial.trim().uppercase())
        
        val printer = BambuPrinter(
            hashedSerial = hashedSn,
            encryptedSerial = encryptedResult.first,
            iv = encryptedResult.second,
            name = name,
            accountUid = accountUid
        )
        bambuDao.insertPrinter(printer)
    }
    
    /**
     * Removes a printer and its associated status telemetry from the database.
     * @param hashedSn The hash of the serial number to remove.
     */
    suspend fun removePrinter(hashedSn: String) {
        bambuDao.deletePrinter(hashedSn)
        bambuDao.deletePrinterStatusByHashedSn(hashedSn)
    }

    /**
     * Converts a database status entity back into a structured [BambuState] object.
     * This method handles version migration from raw MQTT fragments to full object JSON.
     *
     * @param entity The database record containing the JSON telemetry.
     * @param rawSn The decrypted serial number (required for MQTT fragment parsing).
     * @return A structured state object or null if parsing fails.
     */
    fun toBambuState(entity: BambuReportEntity, rawSn: String? = null): BambuState? {
        val json = entity.rawJson
        if (json.isBlank()) return null
        
        // Priority for rawSn: Parameter > SecuritySession > Persistent state (if any)
        val finalRawSn = rawSn ?: SecuritySession.getRawSerial(entity.hashedSerialNumber)

        return try {
            val state = if (!json.contains("\"print\":")) {
                // NEW FORMAT: Full BambuState object
                gson.fromJson(json, BambuState::class.java)
            } else {
                // OLD FORMAT: Raw MQTT message
                if (finalRawSn != null) {
                    BambuMqttManager.parseRawJson(json, finalRawSn)
                } else null
            }
            
            // Re-inject the serial and sync time from the secure context and column metadata
            state?.copy(
                lastWorkerSync = entity.lastWorkerSync ?: state.lastWorkerSync,
                serial = finalRawSn ?: state.serial
            )
        } catch (e: Exception) {
            Log.e("BambuRepository", "Deserialization failed", e)
            if (finalRawSn != null) {
                BambuMqttManager.parseRawJson(json, finalRawSn)?.copy(lastWorkerSync = entity.lastWorkerSync)
            } else null
        }
    }

    /**
     * Saves the latest printer status cumulatively.
     * This merges the incoming [rawJson] fragment with the existing stored state
     * to ensure that data fields not present in the current update (like AMS data)
     * are not lost.
     */
    /**
     * Saves the latest printer status cumulatively.
     * This merges the incoming [rawJson] fragment with the existing stored state
     * to ensure that data fields not present in the current update (like AMS data)
     * are not lost.
     */
    suspend fun savePrinterStatus(
        hashedSn: String, 
        rawJson: String, 
        rawSn: String? = null, 
        workerSyncTime: Long? = null,
        error: String? = null
    ) {
        try {
            val existingEntity = bambuDao.getStatusForPrinter(hashedSn)
            val finalRawSn = rawSn ?: SecuritySession.getRawSerial(hashedSn) ?: existingEntity?.let { toBambuState(it, null)?.serial }

            if (finalRawSn == null || finalRawSn.isBlank()) {
                Log.w("BambuRepository", "Aborting save: No raw serial available for $hashedSn")
                return
            }

            // 1. Reconstruct current state from DB
            val currentState = existingEntity?.let { toBambuState(it, finalRawSn) }

            // 2. Parse new fragment into current state
            val updatedState = BambuMqttManager.parseRawJson(rawJson, finalRawSn, currentState)

            if (updatedState != null) {
                // 3. Determine the final worker sync time (prefer incoming workerSyncTime)
                val finalWorkerTime = workerSyncTime ?: currentState?.lastWorkerSync
                
                if (workerSyncTime != null) {
                    Log.d("BambuRepository", "Applying worker sync time: $workerSyncTime to $hashedSn")
                }

                // 4. Prepare state for storage: STRIP SENSITIVE DATA
                // We do NOT persist the raw serial in the telemetry JSON.
                val stateToSave = updatedState.copy(
                    serial = "", 
                    lastWorkerSync = finalWorkerTime,
                    isConnected = false // Never save 'true' to DB
                )
                
                // 5. Save FULL state to DB
                val fullJson = gson.toJson(stateToSave)
                val report = BambuReportEntity(
                    hashedSerialNumber = hashedSn,
                    rawJson = fullJson,
                    lastUpdated = System.currentTimeMillis(),
                    lastWorkerSync = finalWorkerTime,
                    lastSyncError = error
                )
                bambuDao.savePrinterStatus(report)
            } else if (error != null && existingEntity != null) {
                bambuDao.savePrinterStatus(existingEntity.copy(lastSyncError = error, lastUpdated = System.currentTimeMillis()))
            }
        } catch (e: Exception) {
            Log.e("BambuRepository", "Error saving status", e)
        }
    }
    
    /**
     * Retrieves the latest stored status for a specific printer.
     * @param hashedSn The hash of the serial number.
     * @return The [BambuReportEntity] containing the last telemetry JSON.
     */
    suspend fun getStatusForPrinter(hashedSn: String) = bambuDao.getStatusForPrinter(hashedSn)

    /**
     * Retrieves all stored printer statuses.
     * @return A list of all [BambuReportEntity] records.
     */
    suspend fun getAllPrinterStatuses() = bambuDao.getAllPrinterStatuses()

    /**
     * Provides a Flow of all stored printer statuses.
     * @return A Flow of [BambuReportEntity] lists.
     */
    fun getAllPrinterStatusesFlow(): Flow<List<BambuReportEntity>> = bambuDao.getAllPrinterStatusesFlow()

    /**
     * Accessor for the underlying DAO.
     * @return The [BambuDao] instance.
     */
    fun bambuDao() = bambuDao

    /**
     * Synchronizes AMS (Automatic Material System) data with the local inventory.
     * If [isAmsReport] is true, it triggers a worker for deep synchronization including
     * cloud/NFC data. Otherwise, it performs a local update without external worker triggers.
     */
    suspend fun syncAmsWithInventory(state: BambuState, isAmsReport: Boolean = false) {
        

        // 2. Proceed with standard inventory sync
        if (isAmsReport) {
            // 1. First, update slot history and check for potential out of stock conditions
            scrubForPotentialOutOfStock(state)
            inventoryDao.syncAms(context, state.amsUnits, state.hmsList, state.gcodeState, state.printError)
        } else {
            inventoryDao.syncAmsNoWorker(state.amsUnits, state.hmsList, state.gcodeState, state.printError)
        }
    }

    /**
     * Manually triggers a runout check for a specific printer state.
     * Used for debugging or forced re-evaluation of stock levels.
     * @param state The [BambuState] to evaluate.
     */
    suspend fun debugTriggerRunoutCheck(state: BambuState) {
        scrubForPotentialOutOfStock(state)
    }

    /**
     * Marks a filament as potentially out of stock.
     * @param filamentId The ID of the [FilamentInventory] item.
     */
    suspend fun markPotentialOutOfStock(filamentId: Int) {
        inventoryDao.markPotentialOutOfStock(PotentialOutOfStock(filamentId = filamentId))
    }

    /**
     * Removes a filament from the potential out of stock list.
     * @param filamentId The ID of the [FilamentInventory] item.
     */
    suspend fun removePotentialOutOfStock(filamentId: Int) {
        inventoryDao.removePotentialOutOfStock(filamentId)
    }

    /**
     * Provides a Flow of all filaments currently marked as potentially out of stock.
     * @return A Flow of [PotentialOutOfStock] lists.
     */
    fun getPotentialOutOfStockFlow() = inventoryDao.getPotentialOutOfStockFlow()

    /**
     * Analyzes printer telemetry to detect low filament levels or runout events.
     * If a low stock condition is detected, it marks the filament in the [PotentialOutOfStock] table.
     *
     * @param state The current [BambuState] telemetry.
     */
    private suspend fun scrubForPotentialOutOfStock(state: BambuState) {
        val hashedSn = hashSerial(state.serial)
        val thresholdG = userPrefs.lowFilamentThresholdGFlow.first()
        val autoDetectRunout = userPrefs.autoDetectRunoutFlow.first()
        
        val hasRunoutError = autoDetectRunout && com.napps.filamentmanager.mqtt.HMSCodes.isRunout(state.hmsList, state.printError)

        // 1. Regular weight-based check and history update
        state.amsUnits.forEach { unit ->
            val amsIdx = unit.amsIndex.toIntOrNull() ?: return@forEach
            unit.trays.forEach { tray ->
                val slotIdx = tray.trayIndex.toIntOrNull() ?: return@forEach
                val trayUid = tray.uuid
                
                if (trayUid.isBlank() || tray.subBrand.equals("Empty", ignoreCase = true)) {
                    updateSlotHistory(hashedSn, amsIdx, slotIdx, null)
                    return@forEach
                }

                val filament = inventoryDao.getFilamentByTrayUidStatic(trayUid)
                if (filament != null) {
                    updateSlotHistory(hashedSn, amsIdx, slotIdx, filament.id)
                    val remainG = tray.weight.filter { it.isDigit() }.toIntOrNull() ?: 1000
                    if (remainG < thresholdG) {
                        inventoryDao.markPotentialOutOfStock(PotentialOutOfStock(filamentId = filament.id, reason = PotentialOosReason.LOW_THRESHOLD))
                    }
                }
            }
        }

        // 2. Runout Detection via History (Handles trayNow == 255/254 edge cases)
        if (hasRunoutError) {
            // Find the slot that was most recently marked as empty (null) but previously had a filament
            val latestEmptyingSlot = bambuDao.getAllSlotHistoryForPrinter(hashedSn)
                .filter { it.currentFilamentId == null && it.previousFilamentId != null }
                .maxByOrNull { it.lastUpdated }

            latestEmptyingSlot?.previousFilamentId?.let { filamentId ->
                inventoryDao.markPotentialOutOfStock(PotentialOutOfStock(filamentId = filamentId, reason = PotentialOosReason.AMS_RUNOUT))
            }
            
            // Still check Virtual Tray as a fallback if no AMS history matches
            if (latestEmptyingSlot == null) {
                state.vtTray?.let { vt ->
                    if (vt.uuid.isNotBlank()) {
                        val filament = inventoryDao.getFilamentByTrayUidStatic(vt.uuid)
                        if (filament != null) inventoryDao.markPotentialOutOfStock(PotentialOutOfStock(filamentId = filament.id, reason = PotentialOosReason.AMS_RUNOUT))
                    }
                }
            }
        }
        
        // Handle Virtual Tray regular weight check (non-error)
        if (!hasRunoutError) {
            state.vtTray?.let { vt ->
                if (vt.uuid.isNotBlank()) {
                    val filament = inventoryDao.getFilamentByTrayUidStatic(vt.uuid)
                    if (filament != null) {
                        val remainG = vt.trayWeight.filter { it.isDigit() }.toIntOrNull() ?: 1000
                        if (remainG < thresholdG) {
                            inventoryDao.markPotentialOutOfStock(PotentialOutOfStock(filamentId = filament.id, reason = PotentialOosReason.LOW_THRESHOLD))
                        }
                    }
                }
            }
        }
    }

    /**
     * Re-evaluates all "LOW_THRESHOLD" entries in the potential out of stock list.
     * If a filament's weight is now above the threshold, its entry is removed.
     */
    suspend fun reEvaluateLowStockWarnings() {
        val thresholdG = userPrefs.lowFilamentThresholdGFlow.first()
        val potentials = inventoryDao.getPotentialOutOfStockStatic().filter { it.reason == PotentialOosReason.LOW_THRESHOLD }
        
        potentials.forEach { potential ->
            val filament = inventoryDao.getFilamentByIdStatic(potential.filamentId)
            if (filament != null) {
                val weightG = filament.weight?.filter { it.isDigit() }?.toIntOrNull() ?: 1000
                if (weightG >= thresholdG) {
                    inventoryDao.removePotentialOutOfStock(potential.filamentId)
                }
            }
        }
    }

    /**
     * Updates the historical record for an AMS slot to track filament changes.
     *
     * @param hashedSn Hash of the printer's serial.
     * @param amsIdx Index of the AMS unit.
     * @param slotIdx Index of the slot (0-3).
     * @param filamentId The ID of the filament currently detected in the slot.
     */
    private suspend fun updateSlotHistory(hashedSn: String, amsIdx: Int, slotIdx: Int, filamentId: Int?) {
        val history = bambuDao.getSlotHistory(hashedSn, amsIdx, slotIdx)
        if (history == null) {
            bambuDao.saveSlotHistory(AmsSlotHistory(hashedSn, amsIdx, slotIdx, currentFilamentId = filamentId))
        } else if (history.currentFilamentId != filamentId) {
            bambuDao.saveSlotHistory(history.copy(
                previousFilamentId = history.currentFilamentId,
                currentFilamentId = filamentId,
                lastUpdated = System.currentTimeMillis()
            ))
        }
    }

    /**
     * Safely parses a hex color string into an Android color integer.
     * Supports 6-digit (RRGGBB) and 8-digit (AARRGGBB or RRGGBBAA) formats.
     */
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
}
