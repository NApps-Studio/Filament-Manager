package com.napps.filamentmanager.database

import kotlinx.coroutines.flow.Flow

class SyncReportRepository(private val syncReportDao: SyncReportDao) {
    val allReports: Flow<List<SyncReport>> = syncReportDao.getAllReports()
    val unreadErrorCount: Flow<Int> = syncReportDao.getUnreadErrorCount()

    suspend fun markAllAsRead() = syncReportDao.markAllAsRead()
    suspend fun clearAll() = syncReportDao.clearAll()
    suspend fun update(report: SyncReport) = syncReportDao.update(report)
    suspend fun delete(report: SyncReport) = syncReportDao.delete(report)
}
