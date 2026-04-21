package com.napps.filamentmanager.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.napps.filamentmanager.database.FilamentInventory
import com.napps.filamentmanager.database.FilamentInventoryViewModel
import com.napps.filamentmanager.database.InventoryLimit
import com.napps.filamentmanager.database.InventoryLimitViewModel
import com.napps.filamentmanager.database.LimitWithFilaments
import com.napps.filamentmanager.database.UserPreferencesRepository
import com.napps.filamentmanager.database.VendorFilament
import com.napps.filamentmanager.database.VendorFilamentsViewModel
import com.napps.filamentmanager.formatMass
import com.napps.filamentmanager.util.DynamicCsvHelper
import com.napps.filamentmanager.util.tourTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * UI for managing and monitoring filament inventory limits.
 *
 * This screen allows users to:
 * 1. Define "Limits": Requirements for a minimum number of spools for specific filament groups.
 * 2. Track Real-time Stock: View current inventory levels against defined thresholds.
 * 3. Import/Export: Backup and restore limit configurations via robust JSON (handling name-based matching).
 * 4. Automatic Sync: Integrates with [com.napps.filamentmanager.database.VendorFilamentsViewModel] to sync limits with store trackers.
 *
 * A "Limit" is considered triggered if the number of spools above the [minWeightThreshold]
 * drops below [minFilamentsNeeded].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryLimitsScreen(
    viewModel: InventoryLimitViewModel,
    inventoryViewModel: FilamentInventoryViewModel,
    vendorViewModel: VendorFilamentsViewModel,
    tourTargets: SnapshotStateMap<String, Rect>,
    lazyListState: LazyListState = rememberLazyListState(),
    expandedLimits: MutableMap<Int, Boolean> = remember { mutableStateMapOf() },
    onBack: () -> Unit
) {
    val limits by viewModel.allLimitsWithFilaments.observeAsState(emptyList())
    var showAddLimitDialog by remember { mutableStateOf(false) }
    var selectedLimitForEdit by remember { mutableStateOf<LimitWithFilaments?>(null) }
    var limitToDelete by remember { mutableStateOf<InventoryLimit?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    remember { UserPreferencesRepository(context) }

    val isLibraryEmpty by vendorViewModel.hasAnyFilaments.observeAsState(initial = false).let { state ->
        derivedStateOf { !state.value }
    }

    val hasFirstSyncFinished by vendorViewModel.hasFirstSyncFinished.collectAsStateWithLifecycle(initialValue = true)
    val ignoreSyncWarning by inventoryViewModel.ignoreSyncWarning.collectAsStateWithLifecycle()
    var showSyncWarning by remember { mutableStateOf(false) }

    var showImportDialog by remember { mutableStateOf(false) }
    var pendingLimitImportList by remember {
        mutableStateOf<List<Pair<Map<String, String>, List<Map<String, String>>>>>(
            emptyList()
        )
    }

    val exportLimitsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val allLimits = viewModel.getAllLimitsWithFilamentsStatic()
                val jsonString = DynamicCsvHelper.exportLimitsToRobustJson(allLimits)
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(jsonString.toByteArray())
                }
            }
        }
    }

    val importLimitsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val content = context.contentResolver.openInputStream(it)?.bufferedReader()
                    ?.use { it.readText() }
                content?.let { json ->
                    val importedLimits = DynamicCsvHelper.importLimitsFromRobustJson(json)
                    if (importedLimits.isNotEmpty()) {
                        pendingLimitImportList = importedLimits
                        showImportDialog = true
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Inventory Limits") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        var showMenu by remember { mutableStateOf(false) }
                        // TOUR: More Options (Linked to lim_more in MainActivity)
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.tourTarget("lim_more", tourTargets)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Export Limits (JSON)") },
                                onClick = {
                                    showMenu = false
                                    exportLimitsLauncher.launch("limits_backup_${System.currentTimeMillis()}.json")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import Limits (JSON)") },
                                onClick = {
                                    showMenu = false
                                    importLimitsLauncher.launch(arrayOf("application/json"))
                                }
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isLibraryEmpty) {
                        Text(
                            "Sync required to configure limits",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    // TOUR: Add Limit (Linked to lim_fab in MainActivity)
                    Box {
                        ExtendedFloatingActionButton(
                            onClick = {
                                if (!isLibraryEmpty) {
                                    if (!hasFirstSyncFinished && !ignoreSyncWarning) {
                                        showSyncWarning = true
                                    } else {
                                        showAddLimitDialog = true
                                    }
                                }
                            },
                            text = { Text("Limit") },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            containerColor = if (isLibraryEmpty) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isLibraryEmpty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                            expanded = !isLibraryEmpty,
                            modifier = Modifier.tourTarget("lim_fab", tourTargets)
                        )
                        if (!hasFirstSyncFinished && !isLibraryEmpty) {
                            WarningIconOverlay()
                        }
                    }
                }
            },
            floatingActionButtonPosition = FabPosition.Center
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (limits.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No limits configured", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(limits, key = { it.limit.id }) { limitWithFilaments ->
                            val isFirst = limits.indexOf(limitWithFilaments) == 0
                            LimitCard(
                                limitWithFilaments = limitWithFilaments,
                                inventoryViewModel = inventoryViewModel,
                                isExpanded = expandedLimits[limitWithFilaments.limit.id] ?: false,
                                onExpandChange = {
                                    expandedLimits[limitWithFilaments.limit.id] = it
                                },
                                onToggleActive = { active ->
                                    viewModel.toggleLimitActive(
                                        limitWithFilaments.limit.id,
                                        active
                                    )
                                },
                                onClick = {
                                    selectedLimitForEdit = limitWithFilaments
                                    showAddLimitDialog = true
                                },
                                onDelete = { limitToDelete = limitWithFilaments.limit },
                                onDuplicate = {
                                    viewModel.updateLimitWithFilaments(
                                        limitWithFilaments.limit.copy(
                                            id = 0,
                                            name = limitWithFilaments.limit.name + " (Copy)"
                                        ),
                                        limitWithFilaments.filaments.map { it.id }
                                    )
                                },
                                tourTargets = tourTargets,
                                modifier = if (isFirst) {
                                    // TOUR: Limit Card (Linked to lim_card in MainActivity)
                                    Modifier.tourTarget("lim_card", tourTargets)
                                } else Modifier
                            )
                        }
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }

            if (showSyncWarning) {
                SyncWarningDialog(
                    onDismiss = { showSyncWarning = false },
                    onConfirm = { doNotShowAgain: Boolean ->
                        if (doNotShowAgain) {
                            inventoryViewModel.setIgnoreSyncWarning(true)
                        }
                        showSyncWarning = false
                        showAddLimitDialog = true
                    }
                )
            }

            if (showImportDialog) {
                AlertDialog(
                    onDismissRequest = { showImportDialog = false },
                    title = { Text("Import Limits Conflicts") },
                    text = { Text("How should the app handle limits that already exist (matched by name)?") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.importRobustLimits(
                                pendingLimitImportList,
                                replaceDuplicates = true
                            ) { count ->
                                viewModel.syncLimitsWithTrackers()
                                Toast.makeText(
                                    context,
                                    "Updated/Added $count limits",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            showImportDialog = false
                        }) { Text("Replace Existing") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            viewModel.importRobustLimits(
                                pendingLimitImportList,
                                replaceDuplicates = false
                            ) { count ->
                                viewModel.syncLimitsWithTrackers()
                                Toast.makeText(
                                    context,
                                    "Added $count new limits",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            showImportDialog = false
                        }) { Text("Ignore/Skip") }
                    }
                )
            }

            if (limitToDelete != null) {
                AlertDialog(
                    onDismissRequest = { limitToDelete = null },
                    title = { Text("Delete Limit") },
                    text = { Text("Are you sure you want to delete limit '${limitToDelete?.name}'?") },
                    confirmButton = {
                        TextButton(onClick = {
                            limitToDelete?.let { viewModel.deleteLimit(it) }
                            limitToDelete = null
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { limitToDelete = null }) { Text("Cancel") }
                    }
                )
            }

            if (showAddLimitDialog) {
                AddEditLimitDialog(
                    limitWithFilaments = selectedLimitForEdit,
                    onDismiss = {
                        showAddLimitDialog = false
                        selectedLimitForEdit = null
                    },
                    onSave = { limit, filamentIds ->
                        viewModel.updateLimitWithFilaments(limit, filamentIds)
                        viewModel.syncLimitsWithTrackers()
                        showAddLimitDialog = false
                        selectedLimitForEdit = null
                    },
                    inventoryViewModel = inventoryViewModel,
                    vendorViewModel = vendorViewModel
                )
            }
        }
    }
}


/**
 * Component representing a single inventory limit in the list.
 *
 * Displays the limit name, current status summary, and controls for editing,
 * deleting, duplicating, and toggling active status.
 *
 * @param limitWithFilaments The limit data and associated filaments.
 * @param inventoryViewModel The [FilamentInventoryViewModel] for spool calculations.
 * @param isExpanded Whether the card is currently expanded to show details.
 * @param onExpandChange Callback when the expansion state changes.
 * @param onToggleActive Callback when the limit's active status is toggled.
 * @param onClick Callback when the card is clicked for editing.
 * @param onDelete Callback when the delete action is triggered.
 * @param onDuplicate Callback when the duplicate action is triggered.
 * @param tourTargets Map for UI tour highlighting.
 * @param modifier Modifier for the card.
 */
@Composable
fun LimitCard(
    limitWithFilaments: LimitWithFilaments,
    inventoryViewModel: FilamentInventoryViewModel,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    tourTargets: SnapshotStateMap<String, Rect>,
    modifier: Modifier = Modifier
) {
    val isActive = limitWithFilaments.limit.isActive

    Card(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = if (isActive) CardDefaults.cardColors() else CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = 0.5f
            )
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onExpandChange(!isExpanded) },
                    modifier = Modifier.size(32.dp).padding(end = 4.dp)
                        .tourTarget("lim_card_expand", tourTargets)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        limitWithFilaments.limit.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Min: ${limitWithFilaments.limit.minFilamentsNeeded}; > ${
                            formatMass(
                                limitWithFilaments.limit.minWeightThreshold.toDouble()
                            )
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy((-12).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // TOUR: Duplicate Limit (Linked to lim_copy in MainActivity)
                    IconButton(
                        onClick = onDuplicate,
                        modifier = Modifier.tourTarget("lim_copy", tourTargets)
                    ) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Duplicate",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // TOUR: Delete Limit (Linked to lim_delete in MainActivity)
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.tourTarget("lim_delete", tourTargets)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    // TOUR: Toggle Limit (Linked to lim_toggle in MainActivity)
                    Switch(
                        checked = isActive,
                        onCheckedChange = onToggleActive,
                        modifier = Modifier.scale(0.6f)
                            .tourTarget("lim_toggle", tourTargets)
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                limitWithFilaments.filaments.forEach { filament ->
                    TrackedFilamentRow(
                        filament,
                        inventoryViewModel,
                        limitWithFilaments.limit.minWeightThreshold,
                        limitWithFilaments.limit.minFilamentsNeeded
                    )
                }
            }
        }
    }
}

/**
 * Visual representation of a filament type tracked by a limit.
 *
 * Shows current stock levels across all matching spools in the inventory,
 * highlighting if the stock is below the required threshold.
 */
@Composable
fun TrackedFilamentRow(
    vendorFilament: VendorFilament,
    inventoryViewModel: FilamentInventoryViewModel,
    threshold: Float,
    minFilaments: Int
) {
    var expanded by remember { mutableStateOf(false) }

    val inventorySpools by inventoryViewModel.getMatchingInventorySpools(
        vendorFilament.brand ?: "",
        vendorFilament.type ?: "",
        vendorFilament.colorName ?: ""
    ).observeAsState(emptyList())

    val totalWeight = inventorySpools.sumOf { spool ->
        val weightStr = spool.weight?.replace(Regex("[^0-9]"), "") ?: "1000"
        val weight = weightStr.toDoubleOrNull() ?: 1000.0
        (weight * (spool.usedPercent ?: 1.0f))
    }

    val nonEmptySpools = inventorySpools.count { spool ->
        val weightStr = spool.weight?.replace(Regex("[^0-9]"), "") ?: "1000"
        val weight = weightStr.toDoubleOrNull() ?: 1000.0
        (weight * (spool.usedPercent ?: 1.0f)) >= threshold
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .clickable { expanded = !expanded },
            color = if (expanded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val filamentColor =
                        Color(0xFF000000 or (vendorFilament.colorRgb?.toLong() ?: 0L))
                    Box(
                        modifier = Modifier.size(12.dp)
                            .background(filamentColor, CircleShape)
                            .border(1.dp, Color.LightGray, CircleShape)
                    )
                    val packageShort = if (vendorFilament.packageType?.contains(
                            "Refill",
                            ignoreCase = true
                        ) == true
                    ) "Ref" else "Spl"
                    Text(
                        packageShort,
                        fontSize = 7.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${vendorFilament.brand} ${vendorFilament.type} ${vendorFilament.colorName ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (nonEmptySpools < minFilaments) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Total: ${formatMass(totalWeight)} | Spools: $nonEmptySpools / ${inventorySpools.size}",
                        style = MaterialTheme.typography.labelSmall,

                        )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (expanded) {
            Column(modifier = Modifier.padding(top = 2.dp)) {
                if (inventorySpools.isEmpty()) {
                    Text(
                        "No matching spools",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(
                            start = 28.dp,
                            top = 2.dp,
                            bottom = 4.dp
                        ),
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    inventorySpools.forEach { spool ->
                        SpoolDetailRow(spool, threshold)
                    }
                }
            }
        }
    }
}

/**
 * Detailed view of a single [com.napps.filamentmanager.database.FilamentInventory] spool within a [TrackedFilamentRow].
 *
 * Displays the spool's ID, calculated current weight, and a progress bar
 * representing remaining filament relative to the limit threshold.
 */
@Composable
fun SpoolDetailRow(spool: FilamentInventory, threshold: Float) {
    val weightStr = spool.weight?.replace(Regex("[^0-9]"), "") ?: "1000"
    val totalWeight = weightStr.toFloatOrNull() ?: 1000f
    val currentWeight = totalWeight * (spool.usedPercent ?: 1.0f)
    val progress = if (totalWeight > 0) currentWeight / totalWeight else 0f

    Column(
        modifier = Modifier.padding(
            start = 28.dp,
            top = 4.dp,
            bottom = 4.dp,
            end = 8.dp
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Spool #${spool.id} | ${formatMass(currentWeight.toDouble())}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            if (!spool.trayUID.isNullOrBlank()) {
                Icon(
                    Icons.Default.Nfc,
                    contentDescription = "NFC Scanned",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                color = if (currentWeight >= threshold) MaterialTheme.colorScheme.primary else Color(
                    0xFFFF8A80
                ),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round
            )
        }
    }
}

/**
 * Dialog for creating or editing an [InventoryLimit].
 *
 * Allows configuring the limit name, minimum filament count, weight threshold,
 * and the specific [VendorFilament]s to be tracked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditLimitDialog(
    limitWithFilaments: LimitWithFilaments?,
    onDismiss: () -> Unit,
    onSave: (InventoryLimit, List<Int>) -> Unit,
    inventoryViewModel: FilamentInventoryViewModel,
    vendorViewModel: VendorFilamentsViewModel
) {
    var name by remember { mutableStateOf(limitWithFilaments?.limit?.name ?: "") }
    var minFilaments by remember {
        mutableStateOf(
            limitWithFilaments?.limit?.minFilamentsNeeded?.toString() ?: "1"
        )
    }
    var minWeight by remember {
        mutableStateOf(
            limitWithFilaments?.limit?.minWeightThreshold?.toString() ?: "100"
        )
    }
    val selectedFilaments = remember {
        mutableStateListOf<VendorFilament>().apply {
            limitWithFilaments?.filaments?.let { addAll(it) }
        }
    }
    var showFilamentSelection by remember { mutableStateOf(false) }

    val allVendorFilaments by vendorViewModel.allVendorFilaments.observeAsState(emptyList())

    val view = LocalView.current
    (view.parent as? DialogWindowProvider)?.window?.let { window ->
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxSize()
            ) {
                Text(
                    if (limitWithFilaments == null) "New Limit" else "Edit Limit",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Limit Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Min. amount of filaments needed:",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = {
                            val current = minFilaments.toIntOrNull() ?: 1
                            if (current > 1) minFilaments = (current - 1).toString()
                        }) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                        }
                        OutlinedTextField(
                            value = minFilaments,
                            onValueChange = { if (it.all { c -> c.isDigit() }) minFilaments = it },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(textAlign = TextAlign.Center)
                        )
                        IconButton(onClick = {
                            val current = minFilaments.toIntOrNull() ?: 0
                            minFilaments = (current + 1).toString()
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Increase")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Min filament considered empty [g]:",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = {
                            val current = minWeight.toDoubleOrNull() ?: 100.0
                            if (current >= 100.0) minWeight = (current - 100.0).toInt().toString()
                        }) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                        }
                        OutlinedTextField(
                            value = minWeight,
                            onValueChange = {
                                if (it.isEmpty() || it.toDoubleOrNull() != null) minWeight = it
                            },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(textAlign = TextAlign.Center)
                        )
                        IconButton(onClick = {
                            val current = minWeight.toDoubleOrNull() ?: 0.0
                            minWeight = (current + 100.0).toInt().toString()
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Increase")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Tracked Filaments", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { showFilamentSelection = true }) {
                            Text("Add")
                        }
                    }

                    selectedFilaments.forEach { filament ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val filamentColor =
                                    Color(0xFF000000 or (filament.colorRgb?.toLong() ?: 0L))
                                Box(
                                    modifier = Modifier.size(16.dp)
                                        .background(filamentColor, CircleShape)
                                        .border(1.dp, Color.LightGray, CircleShape)
                                )
                                val packageShort = if (filament.packageType?.contains(
                                        "Refill",
                                        ignoreCase = true
                                    ) == true
                                ) "Refill" else "Spool"
                                Text(
                                    packageShort,
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${filament.brand} ${filament.type}",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    filament.colorName ?: "Unknown Color",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            // PACKAGE TYPE TOGGLE
                            val isRefill =
                                filament.packageType?.contains("Refill", ignoreCase = true) == true
                            val hasBothVariants = remember(filament, allVendorFilaments) {
                                val variants = allVendorFilaments.filter {
                                    it.brand == filament.brand &&
                                            it.type == filament.type &&
                                            it.colorName == filament.colorName
                                }
                                val hasRefill = variants.any {
                                    it.packageType?.contains(
                                        "Refill",
                                        ignoreCase = true
                                    ) == true
                                }
                                val hasSpool = variants.any {
                                    it.packageType?.contains(
                                        "with Spool",
                                        ignoreCase = true
                                    ) == true
                                }
                                hasRefill && hasSpool
                            }

                            Button(
                                onClick = {
                                    val otherVariant = allVendorFilaments.find { vf ->
                                        vf.brand == filament.brand &&
                                                vf.type == filament.type &&
                                                vf.colorName == filament.colorName &&
                                                ((!isRefill && vf.packageType?.contains(
                                                    "Refill",
                                                    ignoreCase = true
                                                ) == true) ||
                                                        (isRefill && vf.packageType?.contains(
                                                            "with Spool",
                                                            ignoreCase = true
                                                        ) == true))
                                    }
                                    if (otherVariant != null) {
                                        val index = selectedFilaments.indexOf(filament)
                                        if (index != -1) {
                                            selectedFilaments[index] = otherVariant
                                        }
                                    }
                                },
                                enabled = hasBothVariants,
                                modifier = Modifier.padding(end = 4.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRefill) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = if (isRefill) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.5f
                                    ),
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = 0.5f
                                    )
                                )
                            ) {
                                Text(if (isRefill) "Refill" else "Spool", fontSize = 12.sp)
                            }

                            IconButton(onClick = { selectedFilaments.remove(filament) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = {
                            val limit =
                                (limitWithFilaments?.limit ?: InventoryLimit(name = name)).copy(
                                    name = name,
                                    minFilamentsNeeded = minFilaments.toIntOrNull() ?: 1,
                                    minWeightThreshold = minWeight.toFloatOrNull() ?: 100f
                                )
                            onSave(limit, selectedFilaments.map { it.id })
                        },
                        enabled = name.isNotBlank() && selectedFilaments.isNotEmpty()
                    ) {
                        Text("Save")
                    }
                }
            }
        }

        if (showFilamentSelection) {
            LimitFilamentSelectionDialog(
                onDismiss = { showFilamentSelection = false },
                onAdd = { filaments: List<VendorFilament> ->
                    filaments.forEach { f ->
                        // Default to Refill when adding
                        val refillVariant = allVendorFilaments.find { vf ->
                            vf.brand == f.brand &&
                                    vf.type == f.type &&
                                    vf.colorName == f.colorName &&
                                    vf.packageType?.contains("Refill", ignoreCase = true) == true
                        } ?: f

                        if (selectedFilaments.none { it.brand == refillVariant.brand && it.type == refillVariant.type && it.colorName == refillVariant.colorName }) {
                            selectedFilaments.add(refillVariant)
                        }
                    }
                    showFilamentSelection = false
                },
                inventoryViewModel = inventoryViewModel,
                vendorViewModel = vendorViewModel
            )
        }
    }
}

/**
 * Dialog for selecting filaments to be tracked by a limit.
 *
 * Provides two tabs:
 * 1. Inventory: Filaments currently present in the user's inventory.
 * 2. All Library: All filaments available in the app's database.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitFilamentSelectionDialog(
    onDismiss: () -> Unit,
    onAdd: (List<VendorFilament>) -> Unit,
    inventoryViewModel: FilamentInventoryViewModel,
    vendorViewModel: VendorFilamentsViewModel
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    val tabs = listOf("Inventory", "All Library")
    val selectedFilaments = remember { mutableStateListOf<VendorFilament>() }

    val view = LocalView.current
    (view.parent as? DialogWindowProvider)?.window?.let { window ->
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .imePadding(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            @Suppress("DEPRECATION")
            (Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                Text("Select Filaments", style = MaterialTheme.typography.titleLarge)

                SecondaryTabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title) }
                        )
                    }
                }

                // SEARCH BAR
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    placeholder = { Text("Search Material, Brand, Color...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Box(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (selectedTabIndex == 0) {
                        InventoryFilamentList(
                            searchQuery,
                            inventoryViewModel,
                            vendorViewModel
                        ) { filament ->
                            val alreadySelected =
                                selectedFilaments.find { it.brand == filament.brand && it.type == filament.type && it.colorName == filament.colorName }
                            if (alreadySelected != null) {
                                selectedFilaments.remove(alreadySelected)
                            } else {
                                selectedFilaments.add(filament)
                            }
                        }
                    } else {
                        AllVendorFilamentList(searchQuery, vendorViewModel) { filament ->
                            val alreadySelected =
                                selectedFilaments.find { it.brand == filament.brand && it.type == filament.type && it.colorName == filament.colorName }
                            if (alreadySelected != null) {
                                selectedFilaments.remove(alreadySelected)
                            } else {
                                selectedFilaments.add(filament)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = { onAdd(selectedFilaments) },
                        enabled = selectedFilaments.isNotEmpty()
                    ) {
                        Text("Add")
                    }
                }
            })
        }
    }
}

/**
 * Helper to check if a filament matches a multi-word search query.
 */
private fun matchesSearch(filament: VendorFilament, query: String): Boolean {
    if (query.isBlank()) return true
    val words = query.trim().split("\\s+".toRegex())
    return words.all { word ->
        (filament.brand?.contains(word, ignoreCase = true) == true) ||
        (filament.type?.contains(word, ignoreCase = true) == true) ||
        (filament.colorName?.contains(word, ignoreCase = true) == true)
    }
}

@Composable
fun InventoryFilamentList(
    searchQuery: String,
    inventoryViewModel: FilamentInventoryViewModel,
    vendorViewModel: VendorFilamentsViewModel,
    onToggle: (VendorFilament) -> Unit
) {
    val allVendorFilaments by vendorViewModel.allVendorFilaments.observeAsState(emptyList())
    val allInventory by inventoryViewModel.getAllFilaments().observeAsState(emptyList())

    val groupedByColor = remember(allInventory, allVendorFilaments, searchQuery) {
        val list = mutableListOf<VendorFilament>()
        allInventory.forEach { inv ->
            allVendorFilaments.find { vf ->
                (vf.brand?.trim()?.equals(inv.brand.trim(), ignoreCase = true) == true) &&
                        (vf.type?.trim()?.equals(inv.type?.trim(), ignoreCase = true) == true) &&
                        (vf.colorName?.trim()
                            ?.equals(inv.colorName?.trim(), ignoreCase = true) == true)
            }?.let { found ->
                if (matchesSearch(found, searchQuery) &&
                    list.none { it.brand == found.brand && it.type == found.type && it.colorName == found.colorName }
                ) {
                    list.add(found)
                }
            }
        }
        list
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(groupedByColor, key = { it.id }) { filament ->
            var isSelected by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        isSelected = !isSelected
                        onToggle(filament)
                    }
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = 0.3f
                        ) else Color.Transparent
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filamentColor = Color(0xFF000000 or (filament.colorRgb?.toLong() ?: 0L))
                Box(
                    modifier = Modifier.size(16.dp).background(filamentColor, CircleShape)
                        .border(1.dp, Color.LightGray, CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${filament.brand} ${filament.type}",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        filament.colorName ?: "No Color",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun AllVendorFilamentList(
    searchQuery: String,
    vendorViewModel: VendorFilamentsViewModel,
    onToggle: (VendorFilament) -> Unit
) {
    val allVendorFilaments by vendorViewModel.allVendorFilaments.observeAsState(emptyList())

    val uniqueByColor = remember(allVendorFilaments, searchQuery) {
        allVendorFilaments
            .filter { matchesSearch(it, searchQuery) }
            .distinctBy { "${it.brand}_${it.type}_${it.colorName}" }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(uniqueByColor, key = { it.id }) { filament ->
            var isSelected by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        isSelected = !isSelected
                        onToggle(filament)
                    }
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = 0.3f
                        ) else Color.Transparent
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filamentColor = Color(0xFF000000 or (filament.colorRgb?.toLong() ?: 0L))
                Box(
                    modifier = Modifier.size(16.dp).background(filamentColor, CircleShape)
                        .border(1.dp, Color.LightGray, CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${filament.brand} ${filament.type}",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        filament.colorName ?: "No Color",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}