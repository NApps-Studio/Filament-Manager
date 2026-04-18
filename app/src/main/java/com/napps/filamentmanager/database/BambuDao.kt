package com.napps.filamentmanager.database


import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Bambu Lab printer authentication and telemetry records.
 */
@Dao
interface BambuDao {
    // --- Auth Section ---
    /** Returns the primary Bambu Lab account credentials. */
    @Query("SELECT * FROM bambu_auth WHERE id = 1")
    suspend fun getAuth(): BambuLab?

    /** Saves or updates the primary account credentials. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAuth(auth: BambuLab)

    /** Clears all account credentials. */
    @Query("DELETE FROM bambu_auth")
    suspend fun deleteAuth()

    /** Provides a real-time stream of the current account credentials. */
    @Query("SELECT * FROM bambu_auth WHERE id = 1")
    fun getAuthFlow(): Flow<BambuLab?>

    // --- Printers Section ---
    /** Renames a registered printer in the local database. */
    @Query("UPDATE bambu_printers SET name = :newName WHERE hashedSerial = :hashedSn")
    suspend fun updatePrinterName(hashedSn: String, newName: String)

    /** Returns the metadata for a specific printer. */
    @Query("SELECT * FROM bambu_printers WHERE hashedSerial = :hashedSn")
    fun getPrinter(hashedSn: String): LiveData<BambuPrinter?>

    /** Provides a real-time stream of all registered printers. */
    @Query("SELECT * FROM bambu_printers")
    fun getAllPrintersFlow(): Flow<List<BambuPrinter>>

    /** Returns a static list of all registered printers. */
    @Query("SELECT * FROM bambu_printers")
    suspend fun getAllPrinters(): List<BambuPrinter>

    /** Registers a new printer. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrinter(printer: BambuPrinter)

    /** Unregisters a printer by its hash. */
    @Query("DELETE FROM bambu_printers WHERE hashedSerial = :hashedSn")
    suspend fun deletePrinter(hashedSn: String)

    /** Clears all registered printers. */
    @Query("DELETE FROM bambu_printers")
    suspend fun deleteAllPrinters()

    /** Saves a telemetry report (full JSON) for a printer. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePrinterStatus(status: BambuReportEntity)

    /** Returns the most recent telemetry report for a printer. */
    @Query("SELECT * FROM bambu_printer_status WHERE hashedSerialNumber = :hashedSn")
    suspend fun getStatusForPrinter(hashedSn: String): BambuReportEntity?

    /** Returns all stored telemetry reports. */
    @Query("SELECT * FROM bambu_printer_status")
    suspend fun getAllPrinterStatuses(): List<BambuReportEntity>

    /** Provides a stream of all printer telemetry reports. */
    @Query("SELECT * FROM bambu_printer_status")
    fun getAllPrinterStatusesFlow(): Flow<List<BambuReportEntity>>

    @Delete
    suspend fun deletePrinterStatus(status: BambuReportEntity)

    /** Clears all printer telemetry reports. */
    @Query("DELETE FROM bambu_printer_status")
    suspend fun deleteAllPrinterStatuses()

    /** Deletes the telemetry history for a specific printer. */
    @Query("DELETE FROM bambu_printer_status WHERE hashedSerialNumber = :hashedSn")
    suspend fun deletePrinterStatusByHashedSn(hashedSn: String)

    // --- AMS History Section ---
    /**
     * Retrieves the history of a specific AMS slot.
     *
     * @param hashedSn The hashed serial number of the printer.
     * @param amsIdx The index of the AMS unit.
     * @param slotIdx The index of the slot within the AMS unit (0-3).
     * @return The [AmsSlotHistory] record, or null if no history exists for this slot.
     */
    @Query("SELECT * FROM ams_slot_history WHERE hashedSerial = :hashedSn AND amsIndex = :amsIdx AND slotIndex = :slotIdx")
    suspend fun getSlotHistory(hashedSn: String, amsIdx: Int, slotIdx: Int): AmsSlotHistory?

    /** Saves or updates the history for an AMS slot. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSlotHistory(history: AmsSlotHistory)

    /** Returns all slot history records for a specific printer. */
    @Query("SELECT * FROM ams_slot_history WHERE hashedSerial = :hashedSn")
    suspend fun getAllSlotHistoryForPrinter(hashedSn: String): List<AmsSlotHistory>

    /** Clears all historical AMS slot records. */
    @Query("DELETE FROM ams_slot_history")
    suspend fun clearAllSlotHistory()
}
