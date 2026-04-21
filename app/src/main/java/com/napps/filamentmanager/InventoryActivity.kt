/**
 * The primary UI Activity for managing the filament inventory.
 *
 * This file contains the [InventoryScreen] and its associated components, providing
 * a comprehensive interface for:
 * - Visualizing stock levels via material-grouped cards.
 * - Processing Bambu Lab RFID tags using NFC.
 * - Performing manual CRUD operations on filament records.
 * - Importing and exporting inventory data in CSV format.
 * - Managing low-stock alerts and bulk updates.
 */
package com.napps.filamentmanager

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.LiveData
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import com.napps.filamentmanager.database.AvailabilityStatus
import com.napps.filamentmanager.database.ColorCrossRef
import com.napps.filamentmanager.database.ColorInfo
import com.napps.filamentmanager.database.FilamentGroup
import com.napps.filamentmanager.database.FilamentInventory
import com.napps.filamentmanager.database.FilamentInventoryViewModel
import com.napps.filamentmanager.database.InventoryLimitViewModel
import com.napps.filamentmanager.database.UserPreferencesRepository
import com.napps.filamentmanager.database.VendorFilamentsViewModel
import com.napps.filamentmanager.database.VendorGroup
import com.napps.filamentmanager.ui.SyncWarningDialog
import com.napps.filamentmanager.ui.WarningIconOverlay
import com.napps.filamentmanager.util.BambuTagReader.BambuSpoolData
import com.napps.filamentmanager.util.DynamicCsvHelper
import com.napps.filamentmanager.util.tourTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.sqrt

// --- HELPER FOR MASS FORMATTING ---
fun formatMass(massG: Double): String {
    return if (massG >= 1000.0) {
        "%.2fkg".format(massG / 1000.0)
    } else {
        "%.0fg".format(massG)
    }
}

// --- ENUMS ---

enum class FabAction(val label: String, val icon: ImageVector) {
    ADD("Add Manual", Icons.Default.Add),
    SCAN_RF_ID("Scan RF ID", Icons.Default.Nfc)
}
// --- SCREENS & COMPOSABLES ---

/**
 * The primary screen for managing the user's filament inventory.
 *
 * Features:
 * - Grouped list of filaments by Vendor and Material Type.
 * - NFC Scanning for Bambu RFID tags (via [NfcScanSheet]).
 * - Manual filament entry and editing.
 * - Low stock detection and bulk "Out of Stock" marking.
 * - CSV Import/Export for database backups and portability.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InventoryScreen(
    viewModel: FilamentInventoryViewModel,
    vendorFilamentsViewModel: VendorFilamentsViewModel,
    limitsViewModel: InventoryLimitViewModel? = null, // Added this to handle limits export
    tourTargets: SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() },
    isTourActive: Boolean = false,
    lazyListState: LazyListState = rememberLazyListState(),
    expandedGroups: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    val currentFabMode by viewModel.currentFabMode.collectAsStateWithLifecycle()
    val hasFirstSyncFinished by vendorFilamentsViewModel.hasFirstSyncFinished.collectAsStateWithLifecycle(initialValue = true)

    val context = LocalContext.current
    val userPrefs = remember { UserPreferencesRepository(context) }

    val hasAnyFilaments by vendorFilamentsViewModel.hasAnyFilaments.observeAsState(initial = true)
    val isLibraryEmpty = !hasAnyFilaments

    var showMenuMoreVert by remember { mutableStateOf(false) }
    var showSummaryDropdown by remember { mutableStateOf(false) }
    var showSpoolAddMenu by remember { mutableStateOf(false) }

    val summary by viewModel.inventorySummary.collectAsStateWithLifecycle()
    val showEmptySpools by viewModel.showEmptySpools.collectAsStateWithLifecycle()
    val ignoreSyncWarning by viewModel.ignoreSyncWarning.collectAsStateWithLifecycle()

    val debugForceOos by userPrefs.debugForceOosFlow.collectAsState(initial = false)
    val debugForceUnmapped by userPrefs.debugForceUnmappedFlow.collectAsState(initial = false)

    var showSyncWarning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var isFetchingColors by remember { mutableStateOf(false) }
    val showScanSheet by viewModel.isScanSheetVisible.collectAsStateWithLifecycle()
    val nfcStatus by viewModel.nfcScanningState.collectAsStateWithLifecycle()
    val spoolData by viewModel.scannedSpoolData.collectAsState()
    var scanResultPair by remember { mutableStateOf<Pair<LiveData<FilamentInventory>, Boolean>?>(null) }

    var clickedAdd by remember { mutableStateOf(false) }

    var showImportDialog by remember { mutableStateOf(false) }
    var pendingImportList by remember { mutableStateOf<List<FilamentInventory>>(emptyList()) }

    // Low stock state
    val lowStock by viewModel.lowStockFilaments.collectAsStateWithLifecycle()
    var showLowStockPopup by remember { mutableStateOf(false) }

    // Editing state
    var editingFilament by remember { mutableStateOf<FilamentInventory?>(null) }
    val suggestions by vendorFilamentsViewModel.uniqueColors.observeAsState(emptyList())

    val exportFilamentsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val allData = viewModel.getAllFilamentsStatic()
                val csvString = DynamicCsvHelper.exportAll(allData)
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(csvString.toByteArray())
                }
            }
        }
    }

    val importFilamentsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { it.readText() }
                content?.let { csv ->
                    val list = DynamicCsvHelper.parseDynamic(csv)
                    if (list.isNotEmpty()) {
                        pendingImportList = list
                        showImportDialog = true
                    }
                }
            }
        }
    }

    LaunchedEffect(editingFilament) {
        if (editingFilament != null) {
            vendorFilamentsViewModel.setSelectedBrand(editingFilament?.brand)
            vendorFilamentsViewModel.setSelectedType(editingFilament?.type)
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Conflicts") },
            text = { Text("How should the app handle filaments that already exist in your inventory?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importAll(pendingImportList, replaceDuplicates = true,
                        { count ->
                            Toast.makeText(context, "Updated/Added $count filaments", Toast.LENGTH_SHORT).show()
                        })
                    showImportDialog = false
                }) { Text("Replace Existing") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.importAll(pendingImportList, replaceDuplicates = false,
                        { count ->
                            Toast.makeText(context, "Updated/Added $count filaments", Toast.LENGTH_SHORT).show()
                        })
                    showImportDialog = false

                }) { Text("Ignore/Skip") }
            }
        )
    }

    if (clickedAdd) {
        val lastBrand by viewModel.lastUsedBrand.collectAsStateWithLifecycle()
        val lastType by viewModel.lastUsedType.collectAsStateWithLifecycle()
        val lastColorName by viewModel.lastUsedColorName.collectAsStateWithLifecycle()
        val lastColorRgb by viewModel.lastUsedColorRgb.collectAsStateWithLifecycle()

        val allBrands by vendorFilamentsViewModel.uniqueBrands.observeAsState(null)
        val allTypes by vendorFilamentsViewModel.uniqueTypes.observeAsState(null)
        val allColors by vendorFilamentsViewModel.uniqueColors.observeAsState(null)

        if (allBrands != null && allTypes != null && allColors != null) {
            val type = lastType ?: allTypes?.firstOrNull() ?: ""
            val brand = lastBrand ?: (allBrands?.firstOrNull() ?: "")
            val colorName = lastColorName ?: allColors?.firstOrNull()?.colorName ?: ""
            val colorRgb = lastColorRgb ?: allColors?.firstOrNull()?.colorRgb ?: 0xFFFFFFFF.toInt()

            ManualFilamentEditDialog(
                filament = FilamentInventory(
                    id = 0,
                    brand = brand,
                    type = type,
                    materialVariantID = null,
                    materialID = null,
                    diameter = 1.75f,
                    colorName = colorName,
                    colorRgb = colorRgb,
                    trayUID = null,
                    timestamp = System.currentTimeMillis(),
                    weight = "1000 g",
                    usedPercent = 1.0f,
                    filamentLength = null,
                    availabilityStatus = 1,
                    status = 0,
                    error = 0
                ),
                inventoryViewModel = viewModel,
                vendorViewModel = vendorFilamentsViewModel,
                onDismiss = { clickedAdd = false },
                onSave = { fill ->
                    viewModel.saveLastUsedManualFilament(fill.brand, fill.type ?: "", fill.colorName ?: "", fill.colorRgb)
                    scope.launch(Dispatchers.IO) {
                        val toInsert = if (fill.availabilityStatus == AvailabilityStatus.OUT_OF_STOCK) {
                            fill.copy(oosTimestamp = System.currentTimeMillis())
                        } else fill
                        viewModel.insert(toInsert)
                    }
                },
                onDelete = { fill ->
                    scope.launch(Dispatchers.IO) {
                        viewModel.delete(fill)
                    }
                }
            )
        }
    }

    if (editingFilament != null) {
        val filament = editingFilament!!

        if (!filament.trayUID.isNullOrBlank()) {
            ScannedFilamentEditDialog(
                filament = filament,
                colorOptions = suggestions,
                onDismiss = { editingFilament = null },
                onSave = { updated ->
                    scope.launch(Dispatchers.IO) {
                        viewModel.update(updated)
                        // If color changed, update the cross reference mapping
                        if (updated.colorName != filament.colorName || updated.colorRgb != filament.colorRgb) {
                            updated.materialVariantID?.let { variantId ->
                                viewModel.insertMapping(
                                    ColorCrossRef(
                                        brand = updated.brand,
                                        type = updated.type ?: "Unknown",
                                        materialVariantID = variantId,
                                        colorName = updated.colorName ?: "Unknown",
                                        colorInt = updated.colorRgb,
                                        tagColorRgb = updated.tagColorRgb
                                    )
                                )
                            }
                        }
                    }
                    editingFilament = null
                },
                onDelete = {
                    scope.launch(Dispatchers.IO) {
                        viewModel.delete(it)
                        editingFilament = null
                    }
                }
            )
        } else {
            ManualFilamentEditDialog(
                filament = filament,
                inventoryViewModel = viewModel,
                vendorViewModel = vendorFilamentsViewModel,
                onDismiss = { editingFilament = null },
                onSave = { updated ->
                    viewModel.saveLastUsedManualFilament(updated.brand, updated.type ?: "", updated.colorName ?: "", updated.colorRgb)
                    scope.launch(Dispatchers.IO) { viewModel.update(updated) }
                },
                onDelete = { fill ->
                    scope.launch(Dispatchers.IO) { viewModel.delete(fill) }
                }
            )
        }
    }

    if (showLowStockPopup) {
        val lowFilamentThresholdG by viewModel.lowFilamentThresholdG.collectAsStateWithLifecycle()

        LowStockFilamentsPopup(
            lowStockFilaments = lowStock,
            threshold = lowFilamentThresholdG,
            onDismiss = { showLowStockPopup = false },
            onSave = { oosIds, notOosIds ->
                scope.launch {
                    val toUpdate = lowStock.filter { it.id in oosIds }
                    viewModel.markAllOutOfStock(toUpdate)
                    notOosIds.forEach { id ->
                        viewModel.removePotentialOutOfStock(id)
                    }
                    showLowStockPopup = false
                }
            },
            onMarkAll = {
                viewModel.markAllOutOfStock(lowStock)
                showLowStockPopup = false
            },
            onEdit = {
                editingFilament = it
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {


                var showFabMenu by remember { mutableStateOf(false) }
                FabAction.entries.filter { it != currentFabMode }.forEach { inactiveMode ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = inactiveMode.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.4f)
                        )
                    }
                }


                Box {
                    FloatingActionButton(
                        onClick = { },
                        containerColor = if (hasFirstSyncFinished) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        contentColor = if (hasFirstSyncFinished) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        // TOUR: Add new spools manually or via NFC (Linked to inv_fab in MainActivity)
                        modifier = Modifier.tourTarget("inv_fab", tourTargets)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                            Box(
                                modifier = Modifier
                                    .combinedClickable(
                                        onClick = {
                                            if (!hasFirstSyncFinished && !ignoreSyncWarning) {
                                                showSyncWarning = true
                                            } else {
                                                if (isLibraryEmpty) {
                                                    // If library is empty, we still allow manual add but it's less ideal
                                                    clickedAdd = true
                                                    vendorFilamentsViewModel.setSelectedBrand(null)
                                                    vendorFilamentsViewModel.setSelectedType(null)
                                                } else {
                                                    when (currentFabMode) {
                                                        FabAction.ADD -> {
                                                            clickedAdd = true
                                                            vendorFilamentsViewModel.setSelectedBrand(null)
                                                            vendorFilamentsViewModel.setSelectedType(null)
                                                        }
                                                        FabAction.SCAN_RF_ID -> {
                                                            viewModel.setShowScanSheet(true)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        onLongClick = { showFabMenu = true }
                                    )
                                    .fillMaxSize()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(currentFabMode.icon, contentDescription = currentFabMode.label)
                            }
                            if (!hasFirstSyncFinished) {
                                WarningIconOverlay()
                            }
                        }
                    }

                    if (showSyncWarning) {
                        SyncWarningDialog(
                            onDismiss = { showSyncWarning = false },
                            onConfirm = { doNotShowAgain ->
                                scope.launch {
                                    if (doNotShowAgain) {
                                        userPrefs.setIgnoreSyncWarning(true)
                                    }
                                    showSyncWarning = false
                                    // Always allow both Scan and Add regardless of sync state if confirmed/ignored
                                    if (isLibraryEmpty) {
                                        clickedAdd = true
                                        vendorFilamentsViewModel.setSelectedBrand(null)
                                        vendorFilamentsViewModel.setSelectedType(null)
                                    } else {
                                        when (currentFabMode) {
                                            FabAction.ADD -> {
                                                clickedAdd = true
                                                vendorFilamentsViewModel.setSelectedBrand(null)
                                                vendorFilamentsViewModel.setSelectedType(null)
                                            }
                                            FabAction.SCAN_RF_ID -> {
                                                viewModel.setShowScanSheet(true)
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }

                    DropdownMenu(
                        expanded = showFabMenu,
                        onDismissRequest = { showFabMenu = false }
                    ) {
                        FabAction.entries.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label) },
                                leadingIcon = { Icon(action.icon, null) },
                                onClick = {
                                    viewModel.setFabMode(action)
                                    showFabMenu = false
                                }
                            )
                        }
                    }
                }

                val unmappedSpools by viewModel.unmappedFilaments.collectAsStateWithLifecycle()
                if (unmappedSpools.isNotEmpty() || debugForceUnmapped) {
                    FloatingActionButton(
                        onClick = {
                            if (hasFirstSyncFinished) {
                                viewModel.resolveNextUnmapped()
                            } else {
                                Toast.makeText(context, "Complete Full sync First to assign color names.", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.size(48.dp).tourTarget("inv_resolve_unmapped", tourTargets),
                        containerColor = Color(0xFFFFB300),
                        contentColor = Color.White
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Resolve Unmapped",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                val lowStock by viewModel.lowStockFilaments.collectAsStateWithLifecycle()
                if (lowStock.isNotEmpty() || debugForceOos) {
                    val hasPotentialRunout = lowStock.any { it.availabilityStatus == AvailabilityStatus.IN_AMS && (it.usedPercent ?: 1.0f) <= 0.01f }

                    FloatingActionButton(
                        onClick = { showLowStockPopup = true },
                        containerColor = if (hasPotentialRunout) Color(0xFFF44336) else MaterialTheme.colorScheme.errorContainer,
                        contentColor = if (hasPotentialRunout) Color.White else MaterialTheme.colorScheme.onErrorContainer,
                        // TOUR: Quickly manage filaments below the weight threshold (Linked to inv_low_stock in MainActivity)
                        modifier = Modifier.size(48.dp).tourTarget("inv_low_stock", tourTargets)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Low Stock Spools"
                        )
                    }
                }
            }
        },
        topBar = {
            Surface(
                tonalElevation = 4.dp
            ) {
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Inventory", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                        val annotatedSummary = buildAnnotatedString {
                            withStyle(style = SpanStyle(color = colorResource(R.color.status_oos), fontWeight = FontWeight.Bold)) { append("${summary.oosCount}") }
                            append("/")
                            withStyle(style = SpanStyle(color = colorResource(R.color.status_in_ams), fontWeight = FontWeight.Bold)) { append("${summary.amsCount}") }
                            append("/")
                            withStyle(style = SpanStyle(color = colorResource(R.color.status_open), fontWeight = FontWeight.Bold)) { append("${summary.openCount}") }
                            append("/")
                            withStyle(style = SpanStyle(color = colorResource(R.color.status_in_use), fontWeight = FontWeight.Bold)) { append("${summary.inUseCount}") }
                            append("/")
                            withStyle(style = SpanStyle(color = colorResource(R.color.status_new), fontWeight = FontWeight.Bold)) { append("${summary.newCount}") }
                            append("/")
                            withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)) { append("${summary.totalSpools}") }
                            append(" | Total: ")
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append(formatMass(summary.totalWeightKg * 1000.0)) }
                        }

                        Box {
                            Text(
                                text = annotatedSummary,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // TOUR: A quick overview of your inventory (Linked to inv_summary in MainActivity)
                                modifier = Modifier
                                    .tourTarget("inv_summary", tourTargets)
                                    .clickable { showSummaryDropdown = true }
                            )
                            DropdownMenu(
                                expanded = showSummaryDropdown,
                                onDismissRequest = { showSummaryDropdown = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Out of stock: ${summary.oosCount}", color = colorResource(R.color.status_oos), fontWeight = FontWeight.Bold) },
                                    onClick = { showSummaryDropdown = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("In AMS: ${summary.amsCount}", color = colorResource(R.color.status_in_ams), fontWeight = FontWeight.Bold) },
                                    onClick = { showSummaryDropdown = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Open: ${summary.openCount}", color = colorResource(R.color.status_open), fontWeight = FontWeight.Bold) },
                                    onClick = { showSummaryDropdown = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("In use: ${summary.inUseCount}", color = colorResource(R.color.status_in_use), fontWeight = FontWeight.Bold) },
                                    onClick = { showSummaryDropdown = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("New: ${summary.newCount}", color = colorResource(R.color.status_new), fontWeight = FontWeight.Bold) },
                                    onClick = { showSummaryDropdown = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("Total: ${summary.totalSpools}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
                                    onClick = { showSummaryDropdown = false }
                                )
                            }
                        }
                    }

                    // TOUR: Toggle the visibility of empty spools (Linked to inv_eye in MainActivity)
                    IconButton(onClick = { viewModel.toggleShowEmptySpools() }, modifier = Modifier.tourTarget("inv_eye", tourTargets)) {
                        Icon(
                            imageVector = if (showEmptySpools) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Empty Spools"
                        )
                    }

                    Box {
                        // TOUR: Import/Export and Limit Configuration (Linked to inv_more in MainActivity)
                        IconButton(onClick = { showMenuMoreVert = true }, modifier = Modifier.tourTarget("inv_more", tourTargets)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More Menu")
                        }
                        DropdownMenu(
                            expanded = showMenuMoreVert,
                            onDismissRequest = {
                                showMenuMoreVert = false
                            },
                            content = {
                                DropdownMenuItem(
                                    text = { Text("Export Filaments (CSV)") },
                                    onClick = {
                                        showMenuMoreVert = false
                                        exportFilamentsLauncher.launch("filaments_backup_${System.currentTimeMillis()}.csv")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Import Filaments (CSV)") },
                                    onClick = {
                                        showMenuMoreVert = false
                                        if (hasFirstSyncFinished) {
                                            importFilamentsLauncher.launch(arrayOf("text/comma-separated-values", "text/csv", "application/octet-stream"))
                                        }
                                    },
                                    enabled = hasFirstSyncFinished
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Configure Limits") },
                                    onClick = {
                                        showMenuMoreVert = false
                                        viewModel.navigateToLimits()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) }
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->

        if (showScanSheet) {
            NfcScanSheet(
                status = nfcStatus,
                spoolData = spoolData,
                onDismiss = {
                    viewModel.setShowScanSheet(false)
                    // Reset status to Idle so the next scan triggers a fresh "Ready" state
                    if (nfcStatus is FilamentInventoryViewModel.NfcStatus.Error) {
                        viewModel.retryScan()
                    }
                },
                onAddClick = { data, colorName, colorRgb ->
                    scope.launch(Dispatchers.IO) {
                        val result = viewModel.insertScannedFilamentBambuLab(data, colorName, colorRgb)
                        val filamentLiveData = result.first
                        val isNewSpool = result.second

                        withContext(Dispatchers.Main) {
                            filamentLiveData.observeForever(object : androidx.lifecycle.Observer<FilamentInventory> {
                                override fun onChanged(value: FilamentInventory) {
                                    scanResultPair = Pair(filamentLiveData, isNewSpool)
                                    // Only show the post-scan menu if we didn't already resolve the color in the sheet
                                    if (colorName == null) {
                                        showSpoolAddMenu = true
                                        val needsColor = isNewSpool && value.colorName.isNullOrBlank()
                                        if (needsColor) {
                                            isFetchingColors = true
                                            vendorFilamentsViewModel.setSelectedBrand(value.brand)
                                            vendorFilamentsViewModel.setSelectedType(value.type)
                                        }
                                    } else {
                                        viewModel.setShowScanSheet(false)
                                    }
                                    filamentLiveData.removeObserver(this)
                                }
                            })
                        }
                    }
                },
                viewModel = viewModel,
                vendorFilamentsViewModel = vendorFilamentsViewModel
            )
        }
        if (showSpoolAddMenu && scanResultPair != null) {
            val filamentRecord by scanResultPair!!.first.observeAsState()
            val isNewSpool = scanResultPair!!.second

            filamentRecord?.let { filament ->
                val colorSuggestions by vendorFilamentsViewModel.uniqueColors.observeAsState()
                if (colorSuggestions != null) {
                    isFetchingColors = false
                }
                if (isFetchingColors) {
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("Loading Colors…") },
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator()
                                Spacer(Modifier.width(16.dp))
                                Text("Please wait")
                            }
                        },
                        confirmButton = {}
                    )
                } else {
                    ScannedFilamentAcknowledgeDialog(
                        filament = filament,
                        isExisting = !isNewSpool,
                        viewModelVendor = vendorFilamentsViewModel,
                        onConfirm = { colorName, colorRgb ->
                            val variantId = filament.materialVariantID
                            if (variantId != null) {
                                scope.launch(Dispatchers.IO) {
                                    viewModel.insertMapping(
                                        ColorCrossRef(
                                            brand = filament.brand,
                                            type = filament.type ?: "Unknown",
                                            materialVariantID = variantId,
                                            colorName = colorName,
                                            colorInt = colorRgb,
                                            tagColorRgb = filament.tagColorRgb
                                        )
                                    )
                                    viewModel.update(filament.copy(
                                        colorName = colorName,
                                        colorRgb = colorRgb ?: filament.colorRgb
                                    ))
                                }
                            }
                            showSpoolAddMenu = false
                            viewModel.setShowScanSheet(false)
                        },
                        onAction = { action ->
                            val newStatus = when (action) {
                                InventoryAction.MOVE_TO_IN_USE -> AvailabilityStatus.IN_USE
                                InventoryAction.MOVE_TO_OUT_OF_STOCK -> AvailabilityStatus.OUT_OF_STOCK
                                InventoryAction.MOVE_TO_NEW -> AvailabilityStatus.NEW
                                InventoryAction.MOVE_TO_OPEN -> AvailabilityStatus.OPEN
                                InventoryAction.MOVE_TO_AMS -> AvailabilityStatus.IN_AMS
                            }
                            scope.launch(Dispatchers.IO) {
                                val isBecomingOos = newStatus == AvailabilityStatus.OUT_OF_STOCK && filament.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK
                                viewModel.update(filament.copy(
                                    availabilityStatus = newStatus,
                                    usedPercent = if (newStatus == AvailabilityStatus.OUT_OF_STOCK) 0f else filament.usedPercent,
                                    oosTimestamp = if (isBecomingOos) System.currentTimeMillis() else if (newStatus != AvailabilityStatus.OUT_OF_STOCK) null else filament.oosTimestamp
                                ))
                            }
                            showSpoolAddMenu = false
                            viewModel.setShowScanSheet(false)
                        },
                        onDismiss = { showSpoolAddMenu = false }
                    )
                }
            }
        }

        val resolvingFilament by viewModel.resolvingUnmappedFilament.collectAsStateWithLifecycle()
        val hasMoreUnmapped by viewModel.hasMoreUnmapped.collectAsStateWithLifecycle()
        resolvingFilament?.let { filament ->
            ManualColorResolutionDialog(
                filament = filament,
                hasMore = hasMoreUnmapped,
                viewModelVendor = vendorFilamentsViewModel,
                onConfirm = { colorName, colorRgb ->
                    viewModel.resolveUnmappedBatch(filament, colorName, colorRgb)

                },
                onDismiss = { viewModel.setResolvingUnmapped(null) }
            )
        }

        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .displayCutoutPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                GroupedFilamentList(
                    viewModel,
                    //vendorFilamentsViewModel,
                    onFilamentClick = { editingFilament = it },
                    tourTargets = tourTargets,
                    lazyListState = lazyListState,
                    expandedGroups = expandedGroups
                )
            }
        }
    }
}
/*
@Composable
fun PotentialOosBanner(
    filaments: List<FilamentInventory>,
    onDismiss: (FilamentInventory) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Potential Out of Stock",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))
            filaments.forEach { filament ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(0xFF000000 or (filament.colorRgb?.toLong() ?: 0L)), CircleShape)
                                .border(1.dp, Color.LightGray, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${filament.colorName} ${filament.type}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = { onDismiss(filament) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Dismiss", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
*/
/**
 * Renders the list of filaments, grouped first by Vendor (Header) and then by
 * Material Type (Expandable Cards).
 */
@Composable
fun GroupedFilamentList(
    viewModel: FilamentInventoryViewModel,
    //vendorFilamentsViewModel: VendorFilamentsViewModel,
    onFilamentClick: (FilamentInventory) -> Unit,
    tourTargets: SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() },
    lazyListState: LazyListState = rememberLazyListState(),
    expandedGroups: SnapshotStateMap<String, Boolean>
) {
    val groupedData by viewModel.groupedFilaments.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp),
        state = lazyListState
    ) {
        groupedData.forEachIndexed { vendorIndex, vendorGroup ->
            item(key = vendorGroup.vendor) {
                VendorSectionHeader(vendorGroup)
            }
            items(vendorGroup.groups, key = { "${vendorGroup.vendor}_${it.type}" }) { filamentGroup ->
                val isFirstGroup = vendorIndex == 0 && vendorGroup.groups.indexOf(filamentGroup) == 0
                FilamentTypeCard(
                    vendor = vendorGroup.vendor,
                    group = filamentGroup,
                    onFilamentClick = onFilamentClick,
                    tourTargets = tourTargets,
                    isTourTarget = isFirstGroup,
                    expandedGroups = expandedGroups
                )
            }
        }
    }
}

@Composable
fun VendorSectionHeader(vendorGroup: VendorGroup) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(vendorGroup.vendor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vendorGroup.hasUnmapped) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Unmapped",
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFFFB300)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    "${vendorGroup.activeSpools} spools | ${formatMass(vendorGroup.totalMassG)}",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
fun FilamentTypeCard(
    vendor: String,
    group: FilamentGroup,
    onFilamentClick: (FilamentInventory) -> Unit,
    tourTargets: SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() },
    isTourTarget: Boolean = false,
    expandedGroups: SnapshotStateMap<String, Boolean>
) {
    val key = "${vendor}_${group.type}"
    val expanded = expandedGroups[key] ?: false

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedGroups[key] = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.type, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (group.hasUnmapped) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Unmapped",
                                modifier = Modifier.size(14.dp),
                                tint = Color(0xFFFFB300)
                            )
                        }
                    }
                    Text(
                        "${group.activeSpools} spools | ${formatMass(group.totalMassG)} total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    // TOUR: Expand group to see individual spools (Linked to inv_card_expand in MainActivity)
                    modifier = if (isTourTarget) Modifier.tourTarget("inv_card_expand", tourTargets) else Modifier
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    group.filaments.forEachIndexed { index, filament ->
                        FilamentRow(
                            filament,
                            onClick = { onFilamentClick(filament) },
                            tourTargets = tourTargets,
                            isTourTarget = isTourTarget && index == 0,
                            isUnmapped = filament.id in group.unmappedFilamentIds
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FilamentRow(
    filament: FilamentInventory,
    onClick: () -> Unit,
    tourTargets: SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() },
    isTourTarget: Boolean = false,
    isUnmapped: Boolean = false
) {
    val filamentColor = Color(0xFF000000 or (filament.colorRgb?.toLong() ?: 0L))
    val weightStr = filament.weight?.replace(Regex("[^0-9]"), "") ?: "1000"
    val totalWeight = weightStr.toFloatOrNull() ?: 1000f
    val currentWeight = totalWeight * (filament.usedPercent ?: 1.0f)
    val progress = if (totalWeight > 0) currentWeight / totalWeight else 0f

    val statusIcon = when (filament.availabilityStatus) {
        AvailabilityStatus.NEW -> Icons.Default.Inventory
        AvailabilityStatus.OPEN -> Icons.Default.Adjust
        AvailabilityStatus.IN_USE -> Icons.Default.PlayArrow
        AvailabilityStatus.IN_AMS -> Icons.Default.CheckCircle
        AvailabilityStatus.OUT_OF_STOCK -> Icons.Default.Error
        else -> null
    }

    val statusColor = when (filament.availabilityStatus) {
        AvailabilityStatus.NEW -> colorResource(R.color.status_new)
        AvailabilityStatus.OPEN -> colorResource(R.color.status_open)
        AvailabilityStatus.IN_USE -> colorResource(R.color.status_in_use)
        AvailabilityStatus.IN_AMS -> colorResource(R.color.status_in_ams)
        AvailabilityStatus.OUT_OF_STOCK -> colorResource(R.color.status_oos)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // TOUR: Edit filament details or status (Linked to inv_spool_edit in MainActivity)
            .then(if (isTourTarget) Modifier.tourTarget("inv_spool_edit", tourTargets) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // Icon for RFID or Manual at far left with ID below
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (!filament.trayUID.isNullOrBlank()) Icons.Default.Nfc else Icons.Default.Edit,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
            Text(
                text = "#${filament.id}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(8.dp))

        // Color Circle
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(filamentColor, CircleShape)
                .border(1.dp, Color.LightGray, CircleShape)
        )
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    filament.colorName ?: "Unknown Color",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (isUnmapped) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Unmapped",
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFFFFB300)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    formatMass(currentWeight.toDouble()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (statusIcon != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(statusIcon, contentDescription = null, modifier = Modifier.size(14.dp), tint = statusColor)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannedFilamentEditDialog(
    filament: FilamentInventory,
    colorOptions: List<ColorInfo>,
    onDismiss: () -> Unit,
    onSave: (FilamentInventory) -> Unit,
    onDelete: (FilamentInventory) -> Unit
) {
    var selectedStatus by remember { mutableIntStateOf(filament.availabilityStatus) }
    var selectedColorInfo by remember {
        mutableStateOf<ColorInfo?>(ColorInfo(filament.colorName, filament.colorRgb))
    }
    var isColorValid by remember { mutableStateOf(true) }
    var percentInput by remember { mutableFloatStateOf((filament.usedPercent ?: 1.0f) * 100f) }
    val statusMap = mapOf(
        AvailabilityStatus.NEW to "New",
        AvailabilityStatus.OPEN to "Open",
        AvailabilityStatus.IN_USE to "In Use",
        AvailabilityStatus.IN_AMS to "Ams",
        AvailabilityStatus.OUT_OF_STOCK to "Out of Stock",
    )
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val hasChanges = remember(selectedStatus, selectedColorInfo, percentInput) {
        val statusChanged = selectedStatus != filament.availabilityStatus
        val colorChanged = selectedColorInfo != null &&
                (selectedColorInfo?.colorName != filament.colorName || selectedColorInfo?.colorRgb != filament.colorRgb)
        val originalPercent = (filament.usedPercent ?: 1.0f) * 100f
        val percentChanged = kotlin.math.abs(percentInput - originalPercent) > 0.1f
        statusChanged || colorChanged || percentChanged
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Filament") },
            text = { Text("Are you sure you want to remove this spool from your inventory? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(filament)
                        showDeleteConfirm = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val updated = filament.copy(
                        availabilityStatus = selectedStatus,
                        colorName = selectedColorInfo?.colorName ?: filament.colorName,
                        colorRgb = selectedColorInfo?.colorRgb ?: filament.colorRgb,
                        usedPercent = if (selectedStatus == AvailabilityStatus.OUT_OF_STOCK) 0f else percentInput / 100f,
                        oosTimestamp = if (selectedStatus == AvailabilityStatus.OUT_OF_STOCK && filament.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK) System.currentTimeMillis() else if (selectedStatus != AvailabilityStatus.OUT_OF_STOCK) null else filament.oosTimestamp
                    )
                    onSave(updated)
                    onDismiss()
                },
                enabled = isColorValid && hasChanges
            ) { Text("Save Changes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,verticalAlignment = Alignment.CenterVertically){
                Column {
                    Text("Edit Scanned Filament", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "UID: ${filament.trayUID}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "ID: #${filament.id}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = Color.Red
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Vendor", style = MaterialTheme.typography.labelMedium)
                        Text(filament.brand, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Type", style = MaterialTheme.typography.labelMedium)
                        Text(filament.type ?: "N/A", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider()

                if (colorOptions.isNotEmpty()) {
                    Text("Re-map Color", style = MaterialTheme.typography.labelMedium)
                    SearchableColorDropdown(
                        label = "Search Color Library",
                        options = colorOptions,
                        selectedOption = selectedColorInfo,
                        targetColorInt = filament.colorRgb,
                        onOptionSelected = { selectedColorInfo = it },
                        onValidationChanged = { isColorValid = it }
                    )
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Full sync required to link colors from the Bambu Lab database.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Remaining: ${percentInput.toInt()}%", style = MaterialTheme.typography.labelMedium)
                        val totalWeight = filament.weight?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 1000
                        val estGrams = (totalWeight * (percentInput / 100f)).toInt()
                        Text("${formatMass(estGrams.toDouble())} est.", style = MaterialTheme.typography.labelSmall)
                    }

                    if (selectedStatus == AvailabilityStatus.OUT_OF_STOCK) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Mass will be set to 0 on save",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Slider(
                            value = percentInput,
                            onValueChange = { percentInput = it },
                            valueRange = 0f..100f,
                            steps = 100
                        )
                    }
                }

                Text("Status", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy((-6).dp)
                ) {
                    statusMap.forEach { (code, label) ->
                        FilterChip(
                            selected = selectedStatus == code,
                            onClick = {
                                selectedStatus = code
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableColorDropdown(
    label: String,
    options: List<ColorInfo>,
    selectedOption: ColorInfo?,
    targetColorInt: Int?,
    onOptionSelected: (ColorInfo?) -> Unit,
    onValidationChanged: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var searchText by remember(selectedOption) { mutableStateOf(selectedOption?.colorName ?: "") }

    LaunchedEffect(options, targetColorInt) {
        if (targetColorInt != null && selectedOption == null && options.isNotEmpty()) {
            val closest = findClosestColor(targetColorInt, options)
            if (closest != null) {
                onOptionSelected(closest)
                searchText = closest.colorName ?: ""
            }
        }
    }

    LaunchedEffect(selectedOption) {
        if (selectedOption?.colorName != searchText) {
            searchText = selectedOption?.colorName ?: ""
        }
    }

    val filteredOptions = remember(searchText, options) {
        if (searchText.isEmpty() || options.any { it.colorName == searchText }) {
            options
        } else {
            options.filter { it.colorName?.contains(searchText, ignoreCase = true) == true }
        }
    }

    val isValid = remember(searchText, options) {
        options.any { it.colorName == searchText }
    }

    LaunchedEffect(isValid) { onValidationChanged(isValid) }

    val tintColor = if (selectedOption != null) {
        Color(0xFF000000 or (selectedOption.colorRgb?.toLong() ?: 0L))
    } else {
        MaterialTheme.colorScheme.surface
    }

    val outlineTextStyle = MaterialTheme.typography.bodyMedium.copy(
        color = Color.White,
        shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 4f)
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { newValue ->
                searchText = newValue
                expanded = true
                val match = options.find { it.colorName == newValue }
                if (match != null || newValue.isEmpty()) {
                    onOptionSelected(match)
                }
            },
            label = {
                Text(
                    text = label,
                    style = if (selectedOption != null) outlineTextStyle else MaterialTheme.typography.bodySmall
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .onFocusChanged { if (it.isFocused) expanded = true },
            textStyle = if (selectedOption != null) outlineTextStyle else MaterialTheme.typography.bodyMedium,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            isError = !isValid && searchText.isNotEmpty(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    expanded = false
                    focusManager.clearFocus()
                }
            ),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = tintColor,
                unfocusedContainerColor = tintColor,
                errorContainerColor = tintColor,
                focusedBorderColor = if (selectedOption != null) Color.White else MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = if (selectedOption != null) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
            )
        )

        ExposedDropdownMenu(
            expanded = expanded && filteredOptions.isNotEmpty(),
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 250.dp)
        ) {
            filteredOptions.forEach { option ->
                val itemBg = Color(0xFF000000 or (option.colorRgb?.toLong() ?: 0L))
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.colorName ?: "",
                            style = outlineTextStyle,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        searchText = option.colorName ?: ""
                        onOptionSelected(option)
                        expanded = false
                        focusManager.clearFocus()
                    },
                    modifier = Modifier.background(itemBg),
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableTextDropdown(
    label: String,
    options: List<String>,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    val filteredOptions = remember(value, options) {
        if (value.isEmpty()) options else options.filter { it.contains(value, ignoreCase = true) }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { newValue ->
                onValueChange(newValue)
                expanded = true
            },
            label = { Text(label) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true)
                .onFocusChanged { if (it.isFocused) expanded = true },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    expanded = false
                    focusManager.clearFocus()
                }
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize()
        ) {
            if (filteredOptions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No matches found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) },
                    onClick = { },
                    enabled = false
                )
            } else {
                filteredOptions.forEach { selection ->
                    DropdownMenuItem(
                        text = { Text(selection) },
                        onClick = {
                            onValueChange(selection)
                            expanded = false
                            focusManager.clearFocus()
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableColorInfoDropdown(
    label: String,
    options: List<ColorInfo>,
    value: String,
    onValueChange: (String) -> Unit,
    onColorSelected: (ColorInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    val filteredOptions = remember(value, options) {
        if (value.isEmpty()) options else options.filter { it.colorName?.contains(value, ignoreCase = true) == true }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { newValue ->
                onValueChange(newValue)
                expanded = true
            },
            label = { Text(label) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true)
                .onFocusChanged { if (it.isFocused) expanded = true },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    expanded = false
                    focusManager.clearFocus()
                }
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize()
        ) {
            if (filteredOptions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No matches found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) },
                    onClick = { },
                    enabled = false
                )
            } else {
                filteredOptions.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(Color(option.colorRgb ?: 0xFFFFFFFF.toInt()), CircleShape)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(option.colorName ?: "")
                            }
                        },
                        onClick = {
                            onColorSelected(option)
                            expanded = false
                            focusManager.clearFocus()
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}

fun findClosestColor(targetInt: Int, options: List<ColorInfo>): ColorInfo? {
    if (options.isEmpty()) return null
    val targetColor = Color(targetInt)

    return options.minByOrNull { option ->
        val optionRgb = option.colorRgb ?: 0xFFFFFFFF.toInt()
        val optionColor = Color(optionRgb)
        val rDiff = (targetColor.red - optionColor.red).toDouble().pow(2.0)
        val gDiff = (targetColor.green - optionColor.green).toDouble().pow(2.0)
        val bDiff = (targetColor.blue - optionColor.blue).toDouble().pow(2.0)
        sqrt(rDiff + gDiff + bDiff)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualFilamentEditDialog(
    filament: FilamentInventory,
    inventoryViewModel: FilamentInventoryViewModel,
    vendorViewModel: VendorFilamentsViewModel,
    onDismiss: () -> Unit,
    onSave: (FilamentInventory) -> Unit,
    onDelete: (FilamentInventory) -> Unit
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var brand by remember { mutableStateOf(filament.brand) }
    var type by remember { mutableStateOf(filament.type ?: "") }
    var weight by remember { mutableStateOf(filament.weight?.replace(Regex("[^0-9]"), "") ?: "1000") }
    var selectedStatus by remember { mutableIntStateOf(filament.availabilityStatus) }
    var colorName by remember { mutableStateOf(filament.colorName ?: "Custom Color") }
    var percentInput by remember { mutableFloatStateOf((filament.usedPercent ?: 1.0f) * 100f) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val controller = rememberColorPickerController()
    var selectedColorRgb by remember { mutableIntStateOf(filament.colorRgb ?: 0xFFFFFFFF.toInt()) }

    val inventoryBrands by inventoryViewModel.inventoryBrands.collectAsStateWithLifecycle()
    val vendorTypes by vendorViewModel.getTypesByBrand(if(brand.isEmpty()) null else brand).observeAsState(emptyList())
    val vendorColors by vendorViewModel.getColorsByTypeAndBrand(if(type.isEmpty()) null else type, if(brand.isEmpty()) null else brand).observeAsState(emptyList())

    var lastSyncedBrand by remember { mutableStateOf(brand) }
    var lastSyncedType by remember { mutableStateOf(type) }

    // Update type when brand changes if the current type isn't in the new list
    LaunchedEffect(vendorTypes, brand) {
        if (vendorTypes.isNotEmpty() && brand != lastSyncedBrand) {
            if (!vendorTypes.contains(type)) {
                type = vendorTypes.first()
            }
        }
    }

    // Auto-select first color when type or brand changes if current selection is invalid
    LaunchedEffect(vendorColors, brand, type) {
        if (vendorColors.isNotEmpty() && (brand != lastSyncedBrand || type != lastSyncedType)) {
            val currentMatch = vendorColors.find { it.colorName == colorName && it.colorRgb == selectedColorRgb }
            if (currentMatch == null) {
                // Try to find by name first if RGB is different (some brands use same name for slightly different RGBs)
                val nameMatch = vendorColors.find { it.colorName == colorName }
                if (nameMatch != null) {
                    nameMatch.colorRgb?.let { rgb ->
                        selectedColorRgb = rgb
                        controller.selectByColor(Color(rgb), fromUser = false)
                    }
                } else {
                    val firstColor = vendorColors.first()
                    colorName = firstColor.colorName ?: "Custom Color"
                    firstColor.colorRgb?.let { rgb ->
                        selectedColorRgb = rgb
                        controller.selectByColor(Color(rgb), fromUser = false)
                    }
                }
            }
            lastSyncedBrand = brand
            lastSyncedType = type
        } else if (brand != lastSyncedBrand || type != lastSyncedType) {
            // Ensure synchronization state is updated even if colors are currently empty
            lastSyncedBrand = brand
            lastSyncedType = type
        }
    }

    // Sync color name when picker moves to a known color for this brand/type
    LaunchedEffect(selectedColorRgb, vendorColors) {
        val match = vendorColors.find { it.colorRgb == selectedColorRgb }
        if (match != null) {
            if (colorName != match.colorName) {
                colorName = match.colorName ?: "Custom Color"
            }
        } else {
            // If it was a known color name but we changed the RGB, it's now custom
            if (vendorColors.any { it.colorName == colorName }) {
                colorName = "Custom Color"
            }
        }
    }

    LaunchedEffect(Unit) {
        filament.colorRgb?.let { savedColor ->
            controller.selectByColor(Color(savedColor), fromUser = false)
        }
    }

    val statusMap = mapOf(
        AvailabilityStatus.NEW to "New",
        AvailabilityStatus.OPEN to "Open",
        AvailabilityStatus.IN_USE to "In Use",
        AvailabilityStatus.IN_AMS to "Ams",
        AvailabilityStatus.OUT_OF_STOCK to "Out of Stock",
    )

    val hasChanges = remember(brand, type, weight, selectedStatus, colorName, selectedColorRgb, percentInput) {
        val originalWeight = filament.weight?.replace(Regex("[^0-9]"), "") ?: "1000"
        val originalPercent = (filament.usedPercent ?: 1.0f) * 100f
        brand != filament.brand || type != (filament.type ?: "") || weight != originalWeight || selectedStatus != filament.availabilityStatus || colorName != (filament.colorName ?: "Custom Color") || selectedColorRgb != (filament.colorRgb ?: 0xFFFFFFFF.toInt()) || kotlin.math.abs(percentInput - originalPercent) > 0.1f
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Filament") },
            text = { Text("Are you sure you want to remove this spool from your inventory? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(filament)
                        showDeleteConfirm = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val updated = filament.copy(
                        brand = brand,
                        type = type,
                        weight = "$weight g",
                        availabilityStatus = selectedStatus,
                        colorName = colorName,
                        colorRgb = selectedColorRgb,
                        usedPercent = if (selectedStatus == AvailabilityStatus.OUT_OF_STOCK) 0f else percentInput / 100f,
                        oosTimestamp = if (selectedStatus == AvailabilityStatus.OUT_OF_STOCK && filament.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK) System.currentTimeMillis() else if (selectedStatus != AvailabilityStatus.OUT_OF_STOCK) null else filament.oosTimestamp
                    )
                    onSave(updated)
                    onDismiss()
                },
                enabled = if (filament.id == 0) {
                    brand.isNotBlank() && type.isNotBlank() && weight.isNotBlank()
                } else {
                    brand.isNotBlank() && type.isNotBlank() && weight.isNotBlank() && hasChanges
                }
            ) { Text("Save Spool") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(if (filament.id == 0) "Add Manual Spool" else "Edit Manual Spool", style = MaterialTheme.typography.headlineSmall)
                    if (filament.id != 0) {
                        Text(
                            "ID: #${filament.id}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SearchableTextDropdown(
                    label = "Vendor / Brand",
                    options = inventoryBrands,
                    value = brand,
                    onValueChange = { brand = it }
                )
                SearchableTextDropdown(
                    label = "Material Type (PLA, PETG...)",
                    options = vendorTypes,
                    value = type,
                    onValueChange = { type = it }
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Select Filament Color", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    HsvColorPicker(
                        modifier = Modifier
                            .size(200.dp)
                            .padding(10.dp),
                        controller = controller,
                        onColorChanged = { colorEnvelope ->
                            selectedColorRgb = colorEnvelope.color.toArgb()
                        }
                    )
                    BrightnessSlider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .height(35.dp),
                        controller = controller,
                        borderRadius = 6.dp,
                        wheelRadius = 10.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(selectedColorRgb), CircleShape)
                                .border(2.dp, Color.White, CircleShape)
                        )
                        SearchableColorInfoDropdown(
                            label = "Color Name",
                            options = vendorColors,
                            value = colorName,
                            onValueChange = { colorName = it },
                            onColorSelected = { info ->
                                colorName = info.colorName ?: ""
                                info.colorRgb?.let { rgb ->
                                    selectedColorRgb = rgb
                                    controller.selectByColor(Color(rgb), fromUser = false)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                OutlinedTextField(
                    value = weight,
                    onValueChange = { if (it.all { char -> char.isDigit() }) weight = it },
                    label = { Text("Total Weight (g)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Number),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                        }
                    )
                )
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Remaining: ${percentInput.toInt()}%", style = MaterialTheme.typography.labelMedium)
                        val totalWeight = if(weight==""){0}else{weight.toInt()}
                        val estGrams = (totalWeight * (percentInput / 100f)).toInt()
                        Text("${formatMass(estGrams.toDouble())} est.", style = MaterialTheme.typography.labelSmall)
                    }

                    if (selectedStatus == AvailabilityStatus.OUT_OF_STOCK) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (filament.id == 0) "Mass will be set to 0 on creation" else "Mass will be set to 0 on save",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Slider(
                            value = percentInput,
                            onValueChange = { percentInput = it },
                            valueRange = 0f..100f,
                            steps = 100
                        )
                    }
                }
                Text("Status", style = MaterialTheme.typography.labelMedium)
                FlowRow(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy((-6).dp)) {
                    statusMap.forEach { (code, label) ->
                        FilterChip(
                            selected = selectedStatus == code,
                            onClick = {
                                selectedStatus = code
                            },
                            label = { Text(label, fontSize = 12.sp) },
                        )
                    }
                }
            }
        }
    )
}

enum class InventoryAction {
    MOVE_TO_IN_USE,
    MOVE_TO_OUT_OF_STOCK,
    MOVE_TO_NEW,
    MOVE_TO_OPEN,
    MOVE_TO_AMS
}

@Composable
fun ManualColorResolutionDialog(
    filament: FilamentInventory,
    hasMore: Boolean,
    viewModelVendor: VendorFilamentsViewModel,
    onConfirm: (colorName: String, colorRgb: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedColor by remember { mutableStateOf<ColorInfo?>(null) }
    var isColorValid by remember { mutableStateOf(false) }

    val suggestions by viewModelVendor.uniqueColors.observeAsState(emptyList())
    var isInitialLoad by remember { mutableStateOf(true) }

    LaunchedEffect(filament) {
        isInitialLoad = true
        viewModelVendor.setSelectedBrand(filament.brand)
        viewModelVendor.setSelectedType(filament.type)
        selectedColor = null
        // Small delay to allow uniqueColors to potentially start updating if needed
        delay(400)
        isInitialLoad = false
    }

    if (isInitialLoad || (suggestions.isEmpty() && !isInitialLoad)) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (suggestions.isEmpty() && !isInitialLoad) "Catalog Search" else "Preparing Suggestions...") },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    if (isInitialLoad) {
                        CircularProgressIndicator()
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No matching colors found in catalog for:", textAlign = TextAlign.Center)
                            Text("${filament.brand} ${filament.type}", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("\nTry running a 'Full Sync' in the Availability menu.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }
                    }
                }
            },
            confirmButton = {
                if (suggestions.isEmpty() && !isInitialLoad) {
                    Button(onClick = {
                        viewModelVendor.clearFilters()
                    }) {
                        Text("Show All Colors")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(if (suggestions.isEmpty() && !isInitialLoad) "Close" else "Cancel") }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Link Unmapped Color") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Link this spool to a known color for tracking:")
                        Text(
                            text = "${filament.brand} ${filament.type}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        filament.colorRgb?.let { tagColorInt ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Tag Color",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Box(
                                    modifier = Modifier
                                        .size(height = 40.dp, width = 120.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(tagColorInt or (0xFF shl 24)))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                    SearchableColorDropdown(
                        label = "Color",
                        options = suggestions,
                        selectedOption = selectedColor,
                        onOptionSelected = { selectedColor = it },
                        targetColorInt = filament.colorRgb,
                        onValidationChanged = { isColorValid = it }
                    )

                    val context = LocalContext.current
                    Text(
                        text = "Color not in list?",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                Toast.makeText(context, "Please run a 'Full Sync' from the Availability menu to ensure your color library is up to date.", Toast.LENGTH_LONG).show()
                            }
                            .padding(vertical = 4.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedColor?.let {
                            onConfirm(it.colorName ?: "Unknown", it.colorRgb)
                        }
                    },
                    enabled = isColorValid && selectedColor != null
                ) {
                    Text(if (hasMore) "Link & Next" else "Finish")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannedFilamentAcknowledgeDialog(
    filament: FilamentInventory,
    isExisting: Boolean,
    viewModelVendor: VendorFilamentsViewModel,
    onConfirm: (updatedColorName: String, updatedColorRgb: Int?) -> Unit,
    onAction: (InventoryAction) -> Unit,
    onDismiss: () -> Unit
) {
    val needsColor = !isExisting && filament.colorName.isNullOrBlank()
    var currentStatus by remember { mutableIntStateOf(filament.availabilityStatus) }
    var selectedColor by remember { mutableStateOf<ColorInfo?>(null) }
    var isColorValid by remember { mutableStateOf(false) }

    LaunchedEffect(filament) {
        selectedColor = null
        viewModelVendor.setSelectedBrand(filament.brand)
        viewModelVendor.setSelectedType(filament.type)
    }

    val suggestions by viewModelVendor.uniqueColors.observeAsState(emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isExisting) "Spool Status Update" else if (needsColor) "Link Label Color" else "Spool Added")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isExisting) {
                    val statusText = when(currentStatus) {
                        AvailabilityStatus.NEW -> "New"
                        AvailabilityStatus.OPEN -> "Open"
                        AvailabilityStatus.IN_USE -> "In Use"
                        AvailabilityStatus.OUT_OF_STOCK -> "Out of Stock"
                        else -> "Unknown"
                    }
                    Text("Current Status: $statusText\nChoose an action to update this spool or link it to a different color.")
                } else if (needsColor) {
                    Text("This is a new variant. Link it to a known color to enable stock tracking:")
                    SearchableColorDropdown(
                        label = "Color",
                        options = suggestions,
                        selectedOption = selectedColor,
                        onOptionSelected = { selectedColor = it },
                        targetColorInt = filament.colorRgb,
                        onValidationChanged = { isColorValid = it }
                    )

                    val context = LocalContext.current
                    Text(
                        text = "Color not in list?",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                Toast.makeText(context, "Please run a 'Full Sync' from the Availability menu to ensure your color library is up to date.", Toast.LENGTH_LONG).show()
                            }
                            .padding(vertical = 4.dp)
                    )

                    filament.colorRgb?.let { tagColorInt ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Tag Color",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(tagColorInt or (0xFF shl 24)))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                            )
                        }
                    }
                } else {
                    Text("Successfully added ${filament.colorName} ${filament.type} to your inventory.")
                }

                if (currentStatus == AvailabilityStatus.OUT_OF_STOCK) {
                    Text(
                        "Mass will be set to 0 on save",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            if (isExisting) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (currentStatus !=AvailabilityStatus.NEW) {
                        Button(
                            onClick = {
                                currentStatus = 1
                                onAction(InventoryAction.MOVE_TO_NEW)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = colorResource(R.color.status_new))
                        ) {
                            Icon(Icons.Default.Inventory, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Move to New")
                        }
                    }
                    if (currentStatus != AvailabilityStatus.OPEN) {
                        Button(
                            onClick = {
                                currentStatus = 4
                                onAction(InventoryAction.MOVE_TO_OPEN)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = colorResource(R.color.status_open))
                        ) {
                            Icon(Icons.Default.Adjust, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Move to Open")
                        }
                    }
                    if (currentStatus != AvailabilityStatus.IN_USE) {
                        Button(
                            onClick = {
                                currentStatus = 3
                                onAction(InventoryAction.MOVE_TO_IN_USE)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = colorResource(R.color.status_in_use), contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Move to In Use")
                        }
                    }
                    if (currentStatus != AvailabilityStatus.OUT_OF_STOCK) {
                        Button(
                            onClick = {
                                currentStatus = 4
                                onAction(InventoryAction.MOVE_TO_OUT_OF_STOCK)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = colorResource(R.color.status_oos))
                        ) {
                            Icon(Icons.Default.Error, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Move to Out of Stock")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            } else {
                Button(
                    onClick = {
                        val finalName = if (needsColor) selectedColor?.colorName.orEmpty() else filament.colorName.orEmpty()
                        onConfirm(finalName,if(needsColor){selectedColor?.colorRgb}else{filament.colorRgb})
                    },
                    enabled = !needsColor || (isColorValid && selectedColor != null)
                ) {
                    Text(if (needsColor) "Save & Close" else "Finish")
                }
            }
        },
        dismissButton = null
    )
}

/**
 * A bottom sheet that facilitates the NFC scanning process.
 *
 * It handles three main states:
 * 1. [NfcStatus.Scanning]: Prompting the user to hold a tag.
 * 2. [NfcStatus.Success]: Displaying parsed Bambu RFID data and offering to add/update the spool.
 * 3. [NfcStatus.Error]: Showing feedback if a tag could not be read or parsed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcScanSheet(
    status: FilamentInventoryViewModel.NfcStatus,
    spoolData: BambuSpoolData?,
    onDismiss: () -> Unit,
    onAddClick: (data: BambuSpoolData, colorName: String?, colorRgb: Int?) -> Unit,
    viewModel: FilamentInventoryViewModel,
    vendorFilamentsViewModel: VendorFilamentsViewModel
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val existingFilament by viewModel.scannedExistingFilament.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectedColor by remember { mutableStateOf<ColorInfo?>(null) }
    var isColorValid by remember { mutableStateOf(false) }
    val suggestions by vendorFilamentsViewModel.uniqueColors.observeAsState(emptyList())
    var isInitialLoad by remember { mutableStateOf(true) }
    var detailsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(spoolData) {
        if (spoolData != null && existingFilament == null) {
            isInitialLoad = true
            vendorFilamentsViewModel.setSelectedBrand("Bambu Lab")
            vendorFilamentsViewModel.setSelectedType(spoolData.detailedType)
            selectedColor = null
            delay(400)
            isInitialLoad = false
        }
    }

    LaunchedEffect(status) {
        if (status is FilamentInventoryViewModel.NfcStatus.Success) {
            sheetState.expand()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 32.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = when (status) {
                    is FilamentInventoryViewModel.NfcStatus.Scanning -> "Ready to Scan"
                    is FilamentInventoryViewModel.NfcStatus.Success -> if (existingFilament != null) "Spool Recognized" else "New Spool Detected"
                    is FilamentInventoryViewModel.NfcStatus.Error -> "Scan Error"
                    else -> ""
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            if (status is FilamentInventoryViewModel.NfcStatus.Scanning) {
                Icon(
                    imageVector = Icons.Default.Nfc,
                    contentDescription = null,
                    modifier = Modifier.size(100.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Hold your tag near the back of your phone")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            } else if (status is FilamentInventoryViewModel.NfcStatus.Error) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(100.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(text = status.message, color = MaterialTheme.colorScheme.error)
                Text(
                    text = "Scan failed. Auto-retrying...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
 else if (status is FilamentInventoryViewModel.NfcStatus.Success && spoolData != null) {
                // NICE CARD FOR THE SPOOL
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val displayColor = safeParseColor(spoolData.colorHex) ?: Color.Gray
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(displayColor, CircleShape)
                                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Bambu Lab", // Vendor
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = spoolData.detailedType, // Type
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        if (existingFilament == null) {
                            if (isInitialLoad || (suggestions.isEmpty() && !isInitialLoad)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isInitialLoad) {
                                        CircularProgressIndicator()
                                    } else {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("No matching colors found.", textAlign = TextAlign.Center)
                                            TextButton(onClick = { vendorFilamentsViewModel.clearFilters() }) {
                                                Text("Show All Colors")
                                            }
                                        }
                                    }
                                }
                            } else {
                                SearchableColorDropdown(
                                    label = "Select Color",
                                    options = suggestions,
                                    selectedOption = selectedColor,
                                    targetColorInt = (safeParseColor(spoolData.colorHex) ?: Color.Gray).toArgb(),
                                    onOptionSelected = { selectedColor = it },
                                    onValidationChanged = { isColorValid = it }
                                )
                            }
                        } else {
                            val colorName by produceState<String>(initialValue = "...", existingFilament) {
                                value = viewModel.getColorCrossRefBytagColorRgb(
                                    existingFilament?.brand ?: "Unknown",
                                    existingFilament?.type,
                                    existingFilament?.tagColorRgb
                                )?.colorName ?: existingFilament?.colorName ?: "Unknown"
                            }
                            Column {
                                Text("Color", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    colorName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text("Package", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    if (spoolData.detailedType.contains("Refill", ignoreCase = true)) "Refill" else "With Spool",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Tag ID", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    text = spoolData.uid,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { detailsExpanded = !detailsExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                if (detailsExpanded) "Show Less" else "More Info",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (detailsExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        AnimatedVisibility(visible = detailsExpanded) {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                DetailRow("Diameter", "${spoolData.diameterMm} mm")
                                DetailRow("Weight", "${spoolData.totalWeightG} g")
                                DetailRow("Length", "~${spoolData.filamentLengthM} m")
                                DetailRow("Production", spoolData.productionDate)
                                DetailRow("Hotend Temp", "${spoolData.minHotendTempC}-${spoolData.maxHotendTempC} °C")
                                DetailRow("Bed Temp", "${spoolData.bedTempC} °C")
                            }
                        }
                    }
                }

                if (existingFilament != null) {
                    val filament = existingFilament!!
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Update Status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        StatusActionRow(
                            currentStatus = filament.availabilityStatus,
                            onAction = { action ->
                                val newStatus = when (action) {
                                    InventoryAction.MOVE_TO_NEW -> AvailabilityStatus.NEW
                                    InventoryAction.MOVE_TO_OPEN -> AvailabilityStatus.OPEN
                                    InventoryAction.MOVE_TO_IN_USE -> AvailabilityStatus.IN_USE
                                    InventoryAction.MOVE_TO_AMS -> AvailabilityStatus.IN_AMS
                                    InventoryAction.MOVE_TO_OUT_OF_STOCK -> AvailabilityStatus.OUT_OF_STOCK
                                }
                                scope.launch(Dispatchers.IO) {
                                    val isBecomingOos = newStatus == AvailabilityStatus.OUT_OF_STOCK && filament.availabilityStatus != AvailabilityStatus.OUT_OF_STOCK
                                    viewModel.update(filament.copy(
                                        availabilityStatus = newStatus,
                                        usedPercent = if (newStatus == AvailabilityStatus.OUT_OF_STOCK) 0f else filament.usedPercent,
                                        oosTimestamp = if (isBecomingOos) System.currentTimeMillis() else if (newStatus != AvailabilityStatus.OUT_OF_STOCK) null else filament.oosTimestamp
                                    ))
                                }
                                onDismiss()
                            }
                        )
                    }
                } else {
                    Button(
                        onClick = { onAddClick(spoolData, selectedColor?.colorName, selectedColor?.colorRgb) },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = isColorValid || selectedColor != null
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add to Inventory")
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusActionRow(currentStatus: Int, onAction: (InventoryAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusButton(
                label = "New",
                icon = Icons.Default.Inventory,
                color = colorResource(R.color.status_new),
                isSelected = currentStatus == AvailabilityStatus.NEW,
                onClick = { onAction(InventoryAction.MOVE_TO_NEW) },
                modifier = Modifier.weight(1f)
            )
            StatusButton(
                label = "Open",
                icon = Icons.Default.Adjust,
                color = colorResource(R.color.status_open),
                isSelected = currentStatus == AvailabilityStatus.OPEN,
                onClick = { onAction(InventoryAction.MOVE_TO_OPEN) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusButton(
                label = "In Use",
                icon = Icons.Default.PlayArrow,
                color = colorResource(R.color.status_in_use),
                textColor = Color.Black,
                isSelected = currentStatus == AvailabilityStatus.IN_USE,
                onClick = { onAction(InventoryAction.MOVE_TO_IN_USE) },
                modifier = Modifier.weight(1f)
            )
            StatusButton(
                label = "In AMS",
                icon = Icons.Default.CheckCircle,
                color = colorResource(R.color.status_in_ams),
                isSelected = currentStatus == AvailabilityStatus.IN_AMS,
                onClick = { onAction(InventoryAction.MOVE_TO_AMS) },
                modifier = Modifier.weight(1f)
            )
        }
        StatusButton(
            label = "Out of Stock",
            icon = Icons.Default.Error,
            color = colorResource(R.color.status_oos),
            isSelected = currentStatus == AvailabilityStatus.OUT_OF_STOCK,
            onClick = { onAction(InventoryAction.MOVE_TO_OUT_OF_STOCK) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun StatusButton(
    label: String,
    icon: ImageVector,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) color else color.copy(alpha = 0.2f),
            contentColor = if (isSelected) textColor else color
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = if (isSelected) ButtonDefaults.buttonElevation(defaultElevation = 4.dp) else null,
        border = if (!isSelected) BorderStroke(1.dp, color.copy(alpha = 0.5f)) else null
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

// Helper for parsing color in UI
private fun safeParseColor(hex: String?): Color? {
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
        Color("#$argbHex".toColorInt())
    } catch (e: Exception) {
        null
    }
}

@Composable
fun LowStockFilamentsPopup(
    lowStockFilaments: List<FilamentInventory>,
    threshold: Int,
    onDismiss: () -> Unit,
    onSave: (List<Int>, List<Int>) -> Unit,
    onMarkAll: () -> Unit,
    onEdit: (FilamentInventory) -> Unit
) {
    var toMarkOos by remember { mutableStateOf(setOf<Int>()) }
    var toNotOos by remember { mutableStateOf(setOf<Int>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onMarkAll,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Set All Out of Stock")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onSave(toMarkOos.toList(), toNotOos.toList()) },
                        modifier = Modifier.weight(1f),
                        enabled = lowStockFilaments.isNotEmpty()
                    ) {
                        Text("Save")
                    }
                }
            }
        },
        title = { Text("Potentially Out of Stock Filaments") },
        text = {
            Column {
                if (lowStockFilaments.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "None filaments are marked as potentially OOS",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text(
                        "The following filaments have been marked as potentially out of stock (OOS means Out of Stock).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(lowStockFilaments) { filament ->
                            val isChecked = toMarkOos.contains(filament.id)
                            val isNotOosChecked = toNotOos.contains(filament.id)

                            // Use original status styles
                            val originalIcon = when (filament.availabilityStatus) {
                                AvailabilityStatus.NEW -> Icons.Default.Inventory
                                AvailabilityStatus.OPEN -> Icons.Default.Adjust
                                AvailabilityStatus.IN_USE -> Icons.Default.PlayArrow
                                AvailabilityStatus.IN_AMS -> Icons.Default.CheckCircle
                                else -> Icons.Default.Error
                            }
                            val originalColor = when (filament.availabilityStatus) {
                                AvailabilityStatus.NEW -> colorResource(R.color.status_new)
                                AvailabilityStatus.OPEN -> colorResource(R.color.status_open)
                                AvailabilityStatus.IN_USE -> colorResource(R.color.status_in_use)
                                AvailabilityStatus.IN_AMS -> colorResource(R.color.status_in_ams)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }

                            val totalWeightG = filament.weight?.replace(Regex("[^0-9]"), "")?.toDoubleOrNull() ?: 1000.0
                            val currentMassG = totalWeightG * (filament.usedPercent ?: 1.0f)
                            val isAboveThreshold = currentMassG >= threshold.toDouble()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onEdit(filament) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .background(Color(0xFF000000 or (filament.colorRgb?.toLong() ?: 0L)), CircleShape)
                                            .border(1.dp, Color.LightGray, CircleShape)
                                    )
                                    Text(
                                        text = "#${filament.id}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${filament.colorName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${filament.type} | ${filament.brand}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Text(
                                        text = "Remaining: ${formatMass(currentMassG)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    OutlinedButton(
                                        onClick = {
                                            if (isChecked) {
                                                toMarkOos = toMarkOos - filament.id
                                            } else {
                                                toMarkOos = toMarkOos + filament.id
                                                toNotOos = toNotOos - filament.id
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(32.dp),
                                        border = BorderStroke(1.dp, if (isChecked) colorResource(R.color.status_oos) else originalColor.copy(alpha = 0.5f)),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = if (isChecked) Color.Black else originalColor,
                                            containerColor = if (isChecked) colorResource(R.color.status_oos) else Color.Transparent
                                        )
                                    ) {
                                        Icon(
                                            imageVector = if (isChecked) Icons.Default.Error else originalIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Set OOS", style = MaterialTheme.typography.labelSmall)
                                    }

                                    if (isAboveThreshold) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        OutlinedButton(
                                            onClick = {
                                                if (isNotOosChecked) {
                                                    toNotOos = toNotOos - filament.id
                                                } else {
                                                    toNotOos = toNotOos + filament.id
                                                    toMarkOos = toMarkOos - filament.id
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            modifier = Modifier.height(32.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = if (isNotOosChecked) Color.Black else MaterialTheme.colorScheme.primary,
                                                containerColor = if (isNotOosChecked) MaterialTheme.colorScheme.primary else Color.Transparent
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Not OOS", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 28.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    )
}
