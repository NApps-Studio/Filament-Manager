package com.napps.filamentmanager.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncReportDao {
    @Insert
    suspend fun insert(report: SyncReport): Long

    @Insert
    suspend fun insertAll(reports: List<SyncReport>)

    @Query("SELECT * FROM sync_reports ORDER BY timestamp DESC")
    fun getAllReports(): Flow<List<SyncReport>>

    @Query("SELECT COUNT(*) FROM sync_reports WHERE isRead = 0 AND isError = 1")
    fun getUnreadErrorCount(): Flow<Int>

    @Update
    suspend fun update(report: SyncReport)

    @Query("UPDATE sync_reports SET isRead = 1")
    suspend fun markAllAsRead()

    @Query("DELETE FROM sync_reports")
    suspend fun clearAll()

    @androidx.room.Delete
    suspend fun delete(report: SyncReport)
}
