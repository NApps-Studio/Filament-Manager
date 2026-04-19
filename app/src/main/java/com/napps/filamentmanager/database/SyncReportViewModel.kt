package com.napps.filamentmanager.database

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SyncReportViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: SyncReportRepository
    val allReports: Flow<List<SyncReport>>
    val unreadErrorCount: Flow<Int>

    private val _isShowingReports = MutableStateFlow(false)
    val isShowingReports: StateFlow<Boolean> = _isShowingReports.asStateFlow()

    fun setShowingReports(show: Boolean) {
        _isShowingReports.value = show
    }

    init {
        val database = AppDatabase.getDatabase(application)
        repository = SyncReportRepository(database.syncReportDao())
        allReports = repository.allReports
        unreadErrorCount = repository.unreadErrorCount
    }

    fun markAllAsRead() = viewModelScope.launch {
        repository.markAllAsRead()
    }

    fun clearAll() = viewModelScope.launch {
        repository.clearAll()
    }
    
    fun markAsRead(report: SyncReport) = viewModelScope.launch {
        repository.update(report.copy(isRead = true))
    }

    fun deleteReport(report: SyncReport) = viewModelScope.launch {
        repository.delete(report)
    }
}
