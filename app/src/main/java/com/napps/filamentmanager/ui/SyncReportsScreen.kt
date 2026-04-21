package com.napps.filamentmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.napps.filamentmanager.database.SyncReport
import com.napps.filamentmanager.database.SyncReportViewModel
import com.napps.filamentmanager.database.VendorFilament
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncReportsScreen(
    viewModel: SyncReportViewModel,
    onBack: () -> Unit,
    tourTargets: SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() },
    isTourActive: Boolean = false
) {
    val reports by viewModel.allReports.collectAsState(initial = emptyList())
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var reportToDelete by remember { mutableStateOf<SyncReport?>(null) }
    var selectedReportForDetail by remember { mutableStateOf<SyncReport?>(null) }

    // Mark all as read when screen is opened
    LaunchedEffect(Unit) {
        viewModel.markAllAsRead()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Reports") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (reports.isNotEmpty()) {
                        IconButton(onClick = { showDeleteAllDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (reports.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No sync reports found", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(reports) { report ->
                    ReportItem(
                        report = report,
                        onDelete = { reportToDelete = report },
                        onClick = { selectedReportForDetail = report }
                    )
                }
            }
        }

        if (showDeleteAllDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteAllDialog = false },
                title = { Text("Delete All Reports") },
                text = { Text("Are you sure you want to delete all synchronization reports? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.clearAll()
                            showDeleteAllDialog = false
                        }
                    ) {
                        Text("Delete All", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAllDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (reportToDelete != null) {
            AlertDialog(
                onDismissRequest = { reportToDelete = null },
                title = { Text("Delete Report") },
                text = { Text("Are you sure you want to delete this sync report?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteReport(reportToDelete!!)
                            if (selectedReportForDetail?.id == reportToDelete?.id) {
                                selectedReportForDetail = null
                            }
                            reportToDelete = null
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { reportToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (selectedReportForDetail != null) {
            ReportDetailDialog(
                report = selectedReportForDetail!!,
                onDismiss = { selectedReportForDetail = null },
                onDelete = { reportToDelete = selectedReportForDetail }
            )
        }
    }
}

@Composable
fun ReportItem(
    report: SyncReport,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val dateStr = sdf.format(Date(report.timestamp))

    val backgroundColor = when {
        !report.isError -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        report.isRead -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    }
    
    val icon = when {
        !report.isError -> Icons.Default.CheckCircle
        report.errorCount > 0 && report.affectedVariants > 0 -> Icons.Default.Warning
        else -> Icons.Default.Error
    }
    
    val iconColor = when {
        !report.isError -> Color(0xFF4CAF50)
        report.errorCount > 0 && report.affectedVariants > 0 -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = if (report.isError && !report.isRead) 
                    CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error)) 
                 else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = report.syncType,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (report.isError) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp).padding(start = 8.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Report", modifier = Modifier.size(16.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = report.summary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (report.isError) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Synced: ${report.affectedVariants}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50)
                )
                if (report.errorCount > 0) {
                    Text(
                        text = "Errors: ${report.errorCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun ReportDetailDialog(
    report: SyncReport,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val sdf = SimpleDateFormat("EEEE, MMMM dd, yyyy HH:mm:ss", Locale.getDefault())
    val dateStr = sdf.format(Date(report.timestamp))

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Report Details",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    DetailRow("Type", report.syncType)
                    DetailRow("Timestamp", dateStr)
                    DetailRow("Status", report.summary)
                    DetailRow("Affected", "${report.affectedVariants} variants")
                    DetailRow("Errors", "${report.errorCount}")

                    if (!report.syncedContent.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Synced Filaments:",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        SyncedFilamentList(report.syncedContent)
                    }

                    if (!report.details.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Error Log:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = report.details,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SyncedFilamentList(json: String) {
    val filaments = remember(json) {
        try {
            val type = object : TypeToken<List<VendorFilament>>() {}.type
            Gson().fromJson<List<VendorFilament>>(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    if (filaments.isEmpty()) return

    // Grouping: Brand -> Type -> Color Name -> List of variants (Spool/Refill)
    val grouped = filaments.groupBy { it.brand ?: "Unknown" }
        .mapValues { (_, brandItems) ->
            brandItems.groupBy { it.type ?: "Unknown" }
                .mapValues { (_, typeItems) ->
                    typeItems.groupBy { it.colorName ?: "Unknown" }
                        .toSortedMap()
                }.toSortedMap()
        }.toSortedMap()

    Column(modifier = Modifier.padding(top = 8.dp)) {
        grouped.forEach { (brand, types) ->
            Text(
                text = brand,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            types.forEach { (type, colors) ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.padding(bottom = 4.dp).fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = type,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        colors.forEach { (colorName, variants) ->
                            val packageTypes = variants.mapNotNull { it.packageType }
                                .distinct()
                                .map { pkg ->
                                    if (pkg.contains("Spool", ignoreCase = true)) "Spool"
                                    else if (pkg.contains("Refill", ignoreCase = true)) "Refill"
                                    else pkg.replaceFirstChar { char -> char.uppercase() }
                                }
                                .distinct()
                                .sortedDescending() // Usually puts Spool before Refill
                            
                            val packageStr = if (packageTypes.isNotEmpty()) {
                                " - " + packageTypes.joinToString(" | ")
                            } else ""
                            
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
                                val firstVariant = variants.firstOrNull()
                                if (firstVariant?.colorRgb != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(Color(0xFF000000 or firstVariant.colorRgb.toLong()), shape = RoundedCornerShape(2.dp))
                                            .padding(end = 4.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                } else {
                                    Text("• ", style = MaterialTheme.typography.bodySmall)
                                }
                                
                                Text(
                                    text = "$colorName$packageStr",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ReportItemPreview() {
    Column(modifier = Modifier.padding(16.dp)) {
        ReportItem(
            report = SyncReport(
                id = 1,
                timestamp = System.currentTimeMillis(),
                syncType = "Full Sync",
                summary = "Completed with 2 errors",
                details = "Error 1: Failed to parse variants from: https://example.com/p1\nError 2: Failed to parse variants from: https://example.com/p2\n",
                affectedVariants = 45,
                errorCount = 2,
                isRead = false,
                isError = true
            ),
            onDelete = {},
            onClick = {}
        )
    }
}
