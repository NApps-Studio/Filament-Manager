package com.napps.filamentmanager

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toColorLong
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.napps.filamentmanager.database.*
import com.napps.filamentmanager.util.DynamicCsvHelper
import com.napps.filamentmanager.util.SecuritySession
import com.napps.filamentmanager.util.tourTarget
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.napps.filamentmanager.ui.SyncRequiredDialog
import com.napps.filamentmanager.ui.SyncWarningDialog
import com.napps.filamentmanager.ui.WarningIconOverlay
import com.napps.filamentmanager.ui.SyncReportsScreen
import com.napps.filamentmanager.webscraper.FullSyncWorker
import com.napps.filamentmanager.webscraper.SyncWorker
import com.napps.filamentmanager.webscraper.StartPagesOfVendors
import com.napps.filamentmanager.webscraper.WebCartDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen for tracking filament availability across different vendors (primarily Bambu Lab).
 * 
 * Features:
 * - Tracker Management: Create and edit "Trackers" which are groups of specific filaments.
 * - Background Syncing: Monitors stock levels and sends notifications when enabled.
 * - Initial Data Sync: Handles the "Full Sync" required to populate the local filament library.
 * - Store Integration: Allows adding tracked filaments directly to the web shopping cart.
 */
@Composable
fun AvailabilityScreen(
    viewModel: VendorFilamentsViewModel,
    inventoryViewModel: FilamentInventoryViewModel,
    bambuViewModel: BambuViewModel,
    syncReportViewModel: SyncReportViewModel,
    userPrefs: UserPreferencesRepository,
    tourTargets: SnapshotStateMap<String, Rect> = remember { mutableStateOf(mutableMapOf<String, Rect>()) }.value as SnapshotStateMap<String, Rect>,
    isTourActive: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    expandedTrackers: SnapshotStateMap<Int, Boolean> = remember { mutableStateOf(mutableMapOf<Int, Boolean>()) }.value as SnapshotStateMap<Int, Boolean>
) {
    val showSyncReports by syncReportViewModel.isShowingReports.collectAsStateWithLifecycle()

    if (showSyncReports) {
        SyncReportsScreen(
            viewModel = syncReportViewModel,
            onBack = { syncReportViewModel.setShowingReports(false) },
            tourTargets = tourTargets,
            isTourActive = isTourActive
        )
        return
    }

    var showAddTrackerDialog by remember { mutableStateOf(false) }
    var showSyncRequiredPopup by remember { mutableStateOf(false) }
    val availabilityTopBarTitleRow1: AvailabilityMenuText? by viewModel.getMenuText("TopMenuRow1").observeAsState()
    val hasFirstSyncFinished by viewModel.hasFirstSyncFinished.collectAsStateWithLifecycle(initialValue = true)

    var showMenu by remember { mutableStateOf(false) }
    val authData by bambuViewModel.authData.collectAsState()

    val trackerList by viewModel.allTrackersWithFilaments.observeAsState(emptyList())
    var selectedTrackerForEdit by remember { mutableStateOf<TrackerWithFilaments?>(null) }
    var trackerToDelete by remember { mutableStateOf<AvailabilityTracker?>(null) }

    val ignoreSyncWarning by inventoryViewModel.ignoreSyncWarning.collectAsStateWithLifecycle()
    
    var showSyncWebView by remember { mutableStateOf(false) }
    var syncedFilaments by remember { mutableStateOf<List<VendorFilament>?>(null) }

    var showWebCart by remember { mutableStateOf(false) }
    var cartFilaments by remember { mutableStateOf<List<VendorFilament>>(emptyList()) }

    var showImportDialog by remember { mutableStateOf(false) }
    var pendingImportList by remember { mutableStateOf<List<Pair<Map<String, String>, List<Map<String, String>>>>>(emptyList()) }

    val trackersWithNotifications by viewModel.TrackersWithNotifications.observeAsState(null)
    val shouldExpandReady by viewModel.shouldExpandReady.observeAsState(false)
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    val storeRegion by userPrefs.storeRegionFlow.collectAsState(initial = SyncRegion.EU)

    var isInitialized by rememberSaveable { mutableStateOf(false) }
    var showNonEditable by rememberSaveable { mutableStateOf(false) }

    val unreadReportsCount by syncReportViewModel.unreadErrorCount.collectAsState(initial = 0)
    
    val lifecycleOwner = LocalLifecycleOwner.current

    val exportTrackersLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val allTrackers = viewModel.getAllAvailabilityTrackersStatic()
                val jsonString = DynamicCsvHelper.exportTrackersToRobustJson(allTrackers)
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(jsonString.toByteArray())
                }
            }
        }
    }

    val importTrackersLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { it.readText() }
                content?.let { json ->
                    val importedTrackers = DynamicCsvHelper.importTrackersFromRobustJson(json)
                    if (importedTrackers.isNotEmpty()) {
                        pendingImportList = importedTrackers
                        showImportDialog = true
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAllTrackers()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(trackersWithNotifications) {
        val list = trackersWithNotifications ?: return@LaunchedEffect
        val enabled = list.isNotEmpty()
        if (enabled) {
            if (!isInitialized) {
                scope.launch(Dispatchers.IO) {
                    val current = viewModel.getMenuTextStatic("TopMenuRow1")
                    if (current != null) {
                        // Protect "Full Sync" status
                        if (current.text != "Enabled" && !current.text.contains("Full sync")) {
                            viewModel.update(current.copy(text = "Enabled"))
                        }
                    } else {
                        viewModel.insert(AvailabilityMenuText(id = 1, name = "TopMenuRow1", text = "Enabled"))
                    }
                }
                SyncWorker.enqueue(context)
                isInitialized = true
            }
        } else {
            scope.launch(Dispatchers.IO) {
                val current = viewModel.getMenuTextStatic("TopMenuRow1")
                if (current != null) {
                    // Protect "Full Sync" status
                    if (current.text != "Disabled" && !current.text.contains("Full sync")) {
                        viewModel.update(current.copy(text = "Disabled"))
                    }
                } else {
                    viewModel.insert(AvailabilityMenuText(id = 1, name = "TopMenuRow1", text = "Disabled"))
                }
            }
            SyncWorker.cancel(context)
            isInitialized = false
        }
    }

    var showRestartSyncDialog by remember { mutableStateOf(false) }
    if (showRestartSyncDialog) {
        AlertDialog(
            onDismissRequest = { showRestartSyncDialog = false },
            title = { Text("Sync Already Running") },
            text = { Text("A full data sync is already in progress. Would you like to restart it?") },
            confirmButton = {
                TextButton(onClick = {
                    FullSyncWorker.enqueue(appContext)
                    showRestartSyncDialog = false
                }) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = { showRestartSyncDialog = false }) { Text("Cancel") }
            }
        )
    }



    if (showWebCart) {
        WebCartDialog(
            cartFilaments = cartFilaments,
            region = storeRegion,
            onDismiss = { showWebCart = false }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Conflicts") },
            text = { Text("How should the app handle trackers that already exist (matched by name)?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importRobustTrackers(pendingImportList, replaceDuplicates = true) { count ->
                        Toast.makeText(context, "Updated/Added $count trackers", Toast.LENGTH_SHORT).show()
                    }
                    showImportDialog = false
                }) { Text("Replace Existing") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.importRobustTrackers(pendingImportList, replaceDuplicates = false) { count ->
                        Toast.makeText(context, "Added $count new trackers", Toast.LENGTH_SHORT).show()
                    }
                    showImportDialog = false
                }) { Text("Ignore/Skip") }
            }
        )
    }

    if (trackerToDelete != null) {
        AlertDialog(
            onDismissRequest = { trackerToDelete = null },
            title = { Text("Delete Tracker") },
            text = { Text("Are you sure you want to delete tracker '${trackerToDelete?.name}'?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        trackerToDelete?.let { viewModel.delete(it) }
                        trackerToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { trackerToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    
    val highlightFullSync by inventoryViewModel.highlightFullSync.collectAsStateWithLifecycle()
    LaunchedEffect(highlightFullSync) {
        if (highlightFullSync) {
            showMenu = true
            delay(2000)
            inventoryViewModel.setHighlightFullSync(false)
        }
    }

    Scaffold(
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FloatingActionButton(
                    onClick = {
                        if (hasFirstSyncFinished) {
                            showAddTrackerDialog = true
                        } else {
                            showSyncRequiredPopup = true
                        }
                    },
                containerColor = if (hasFirstSyncFinished)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                contentColor = if (hasFirstSyncFinished)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                elevation = if (hasFirstSyncFinished) FloatingActionButtonDefaults.elevation() else FloatingActionButtonDefaults.loweredElevation(),
                modifier = Modifier.tourTarget("avail_fab", tourTargets)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                    if (!hasFirstSyncFinished) {
                        WarningIconOverlay()
                    }
                }
            }
        }
        },
        topBar = {
            Surface(
                tonalElevation = 4.dp
            ) {
                Row(modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Availability", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        val statusText = buildString {
                            if (!hasFirstSyncFinished && (availabilityTopBarTitleRow1?.text == null || !availabilityTopBarTitleRow1!!.text.contains("sync", ignoreCase = true))) {
                                append("Please complete Full Sync first")
                            } else {
                                append(availabilityTopBarTitleRow1?.text ?: "")
                                availabilityTopBarTitleRow1?.lastUpdated?.let {
                                    append(" | Last check: ")
                                    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                                    append(sdf.format(Date(it)))
                                }
                            }
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (hasFirstSyncFinished != true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.tourTarget("avail_status", tourTargets)
                        )
                    }

                    IconButton(onClick = { showNonEditable = !showNonEditable }) {
                        Icon(
                            imageVector = if (showNonEditable) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle System Trackers",
                            modifier = Modifier.tourTarget("avail_eye", tourTargets)
                        )
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.MoreVert, 
                                    contentDescription = "More Menu",
                                    modifier = Modifier.tourTarget("avail_more", tourTargets)
                                )
                                if (unreadReportsCount > 0) {
                                    Icon(
                                        Icons.Default.PriorityHigh,
                                        contentDescription = "New Sync Reports",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .align(Alignment.TopEnd)
                                            .offset(x = 4.dp, y = (-4).dp)
                                    )
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { 
                                showMenu = false
                            }
                        ) {
                                DropdownMenuItem(
                                    text = { Text("Export Trackers (JSON)") },
                                    onClick = {
                                        showMenu = false
                                        exportTrackersLauncher.launch("trackers_backup_${System.currentTimeMillis()}.json")
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Import Trackers (JSON)") },
                                    onClick = {
                                        showMenu = false
                                        if (hasFirstSyncFinished == true) {
                                            importTrackersLauncher.launch(arrayOf("application/json"))
                                        }
                                    },
                                    enabled = hasFirstSyncFinished == true
                                )
                                HorizontalDivider()
                                
                                // REGION SELECTION
                                var showRegionSubMenu by remember { mutableStateOf(false) }
                                DropdownMenuItem(
                                    text = { Text("Bambu Store Region") },
                                    trailingIcon = { Icon(if (showRegionSubMenu) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, null) },
                                    onClick = { showRegionSubMenu = !showRegionSubMenu }
                                )
                                if (showRegionSubMenu) {
                                    val currentRegion = storeRegion
                                    SyncRegion.entries.forEach { region ->
                                        DropdownMenuItem(
                                            text = { 
                                                Text(
                                                    text = region.name, 
                                                    fontWeight = if (currentRegion == region) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (currentRegion == region) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                ) 
                                            },
                                            onClick = {
                                                bambuViewModel.updateAuthMetadata(region, region == SyncRegion.ASIA)
                                            }
                                        )
                                    }
                                }

                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Full Sync") },
                                    leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        scope.launch {
                                            val isRunning = FullSyncWorker.isWorkRunning(appContext)
                                            if (isRunning) {
                                                showRestartSyncDialog = true
                                            } else {
                                                FullSyncWorker.enqueue(appContext)
                                            }
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Update Availability") },
                                    leadingIcon = { Icon(Icons.Default.CloudSync, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        SyncWorker.enqueue(appContext, immediate = true)
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Sync Reports")
                                            if (unreadReportsCount > 0) {
                                                Spacer(Modifier.width(8.dp))
                                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                                    Text(unreadReportsCount.toString())
                                                }
                                            }
                                        }
                                    },
                                    leadingIcon = { 
                                        Icon(
                                            if (unreadReportsCount > 0) Icons.Default.PriorityHigh else Icons.Default.Description,
                                            contentDescription = null,
                                            tint = if (unreadReportsCount > 0) MaterialTheme.colorScheme.error else LocalContentColor.current
                                        ) 
                                    },
                                    onClick = {
                                        showMenu = false
                                        syncReportViewModel.setShowingReports(true)
                                    }
                                )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .displayCutoutPadding()
        ) {
            if (showAddTrackerDialog) {
                AddTrackerDialog(
                    onDismiss = { showAddTrackerDialog = false
                                selectedTrackerForEdit = null
                    },
                    trueAddOrFalseEdit = selectedTrackerForEdit == null,
                    onTrackerAdded = { tracker: AvailabilityTracker, filamentIds: List<Int> ->
                        if (selectedTrackerForEdit != null){
                            viewModel.updateTrackerWithFilaments(selectedTrackerForEdit!!.copy(tracker = tracker), filamentIds)
                        } else {
                            viewModel.insertTrackerWithFilaments(tracker, filamentIds)
                        }
                        showAddTrackerDialog = false
                    },
                    tracker = selectedTrackerForEdit ?: TrackerWithFilaments(
                        AvailabilityTracker(name = "", notificationEnabled = false),
                        emptyList()
                    ),
                    viewModel = viewModel
                )
            }
            
            if (showSyncRequiredPopup) {
                SyncRequiredDialog(
                    onDismiss = { showSyncRequiredPopup = false }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                val visibleTrackers = trackerList.filter { it.tracker.isEditable || it.tracker.notificationEnabled || showNonEditable }
                visibleTrackers.forEachIndexed { index, tracker ->
                    TrackerCard(
                        tracker = tracker,
                        shouldExpandInitially = shouldExpandReady && tracker.filaments.isNotEmpty() && tracker.filaments.all { it.isAvailable },
                        onDelete = { trackerToDelete = tracker.tracker },
                        onToggleNotification = {
                            viewModel.update(tracker.tracker.copy(notificationEnabled = it))
                        },
                        onClickCard = {
                            selectedTrackerForEdit = tracker
                            showAddTrackerDialog = true
                        },
                        onAddToCart = {
                            cartFilaments = tracker.filaments
                            showWebCart = true
                        },
                        onDuplicate = {
                            viewModel.insertTrackerWithFilaments(
                                tracker.tracker.copy(name = tracker.tracker.name + "(Copy)",id=0,isEditable = true,isDeletable = true),
                                tracker.filaments.map { it.id })
                        },
                        tourTargets = tourTargets,
                        isTourTarget = index == 0,
                        expandedTrackers = expandedTrackers
                    )
                }
                if (shouldExpandReady && trackerList.isNotEmpty()) {
                    LaunchedEffect(shouldExpandReady) {
                        delay(500) // Brief delay to ensure TrackerCards handle the state
                        viewModel.setExpandReady(false)
                    }
                }
            }
        }
    }
}

/**
 * Represents a single tracking group in the UI.
 * 
 * Displays the tracker name, notification status, and a list of associated filaments.
 * Out-of-date stock data is visually indicated with a yellow warning triangle.
 */
@Composable
fun TrackerCard(
    tracker: TrackerWithFilaments,
    shouldExpandInitially: Boolean = false,
    onDelete: () -> Unit,
    onToggleNotification: (Boolean) -> Unit,
    onClickCard: () -> Unit,
    onAddToCart: () -> Unit,
    onDuplicate: () -> Unit,
    tourTargets: SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() },
    isTourTarget: Boolean = false,
    expandedTrackers: SnapshotStateMap<Int, Boolean>
) {
    val expanded = expandedTrackers[tracker.tracker.id] ?: shouldExpandInitially

    LaunchedEffect(shouldExpandInitially) {
        if (shouldExpandInitially) expandedTrackers[tracker.tracker.id] = true
    }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .wrapContentHeight()
            // TOUR: Highlight the entire Tracker Card (Linked to "avail_card" step in MainActivity)
            .let { if (isTourTarget) it.tourTarget("avail_card", tourTargets) else it }
            .clickable(
                enabled = tracker.tracker.isEditable,
                interactionSource = null,
                indication = null,
                onClick = { onClickCard() }
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { expandedTrackers[tracker.tracker.id] = !expanded },
                modifier = Modifier.size(32.dp).padding(start = 4.dp).let {
                    // TOUR: Target for expanding/collapsing the card
                    if (isTourTarget) it.tourTarget("avail_card_expand", tourTargets) else it
                }
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            Text(
                tracker.tracker.name,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                color = if (tracker.tracker.isEditable) MaterialTheme.colorScheme.onSurface else Color.Gray
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy((-12).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDuplicate, modifier = Modifier.let {
                    // TOUR: Target for the Copy button
                    if (isTourTarget) it.tourTarget("avail_card_copy", tourTargets) else it
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate Tracker", tint = Color.White)
                }
                IconButton(onClick = onAddToCart, modifier = Modifier.let {
                    // TOUR: Target for the Cart button
                    if (isTourTarget) it.tourTarget("avail_card_cart", tourTargets) else it
                }) {
                    Icon(Icons.Outlined.ShoppingCart, contentDescription = "Add all to cart", tint = Color.White)
                }
                if (tracker.tracker.isDeletable) {
                    IconButton(onClick = onDelete, modifier = Modifier.let {
                        // TOUR: Target for the Delete button
                        if (isTourTarget) it.tourTarget("avail_card_delete", tourTargets) else it
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                }

                IconToggleButton(
                    checked = tracker.tracker.notificationEnabled,
                    enabled = tracker.tracker.isEditable,
                    onCheckedChange = { onToggleNotification(it) },
                    modifier = Modifier.let {
                        // TOUR: Target for the Notification bell
                        if (isTourTarget) it.tourTarget("avail_card_bell", tourTargets) else it
                    }
                ) {
                    val icon = if (tracker.tracker.notificationEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone
                    val tint = if (tracker.tracker.notificationEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(imageVector = icon, contentDescription = "Toggle Notifications", tint = if(tracker.tracker.isEditable) tint else Color.Gray.copy(alpha = 0.5f))
                }
            }
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                tracker.filaments.forEach { filament ->
                    TrackerFilamentRow(filament)
                }
            }
        }
    }
}

/**
 * Visual representation of a filament variant within an [AvailabilityTracker].
 * 
 * Logic:
 * 1. Stale Data Check: If [VendorFilament.timestamp] is more than 30 minutes old, 
 *    a yellow "stale" indicator is drawn.
 * 2. Visual Style: Uses the variant's [colorRgb] for text styling with a shadow 
 *    to ensure readability on the status-colored background.
 * 3. Status Display: Clearly differentiates "In stock" (green) and "Out of stock" (red).
 */
@Composable
fun TrackerFilamentRow(filament: VendorFilament) {
    var isOutOfDate by remember(filament.timestamp) {
        mutableStateOf(System.currentTimeMillis() - (filament.timestamp ?: 0) > (30 * 60 * 1000))
    }
    LaunchedEffect(key1 = filament.timestamp) {
        if (!isOutOfDate) {
            val thirtyMinutesInMillis = 30 * 60 * 1000L
            val timestamp = filament.timestamp ?: 0L
            val timeUntilOutOfDate = (timestamp + thirtyMinutesInMillis) - System.currentTimeMillis()
            if (timeUntilOutOfDate > 0) {
                delay(timeUntilOutOfDate)
                isOutOfDate = true
            }
        }
    }

    val backgroundColor = if (filament.isAvailable) Color(0xFF5A7C5D) else Color.Transparent
    val outlineTextStyle = MaterialTheme.typography.bodyMedium.copy(
        color = filament.colorRgb?.let { Color(0xFF000000 or it.toLong()) } ?: MaterialTheme.colorScheme.onSurface,
        shadow = Shadow(color = Color.Black, offset = Offset(0f, 0f), blurRadius = 1f)
    )

    Box(modifier = Modifier.fillMaxWidth().background(backgroundColor)) {
        if (isOutOfDate) {
            Canvas(modifier = Modifier.size(30.dp)) {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path = path, color = Color(0xFFBB9700))
            }
        }

        Column(modifier = Modifier.padding(4.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = filament.brand ?: "Unknown Brand",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = filament.type ?: "", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = filament.colorName ?: "", style = outlineTextStyle)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = filament.packageType ?: "", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = (filament.weight ?: ""), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically){
                Text(
                    text = if (filament.isAvailable) "In stock" else "Out of stock",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(shadow = Shadow(color = Color.Black, offset = Offset(0f, 0f), blurRadius = 1f)),
                    color = if (filament.isAvailable) Color(0xFF2E7D32) else Color.Red,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = formatTimestamp(filament.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/**
 * Formats a Unix timestamp into a human-readable string.
 *
 * @param timestamp The timestamp in milliseconds, or null if never updated.
 * @return A formatted string showing the last check time or "Never updated".
 */
fun formatTimestamp(timestamp: Long?): String {
    if (timestamp == null) return "Never updated"
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return "Last checked: ${sdf.format(Date(timestamp))}"
}

/**
 * Dialog for creating or editing an [AvailabilityTracker].
 *
 * Allows the user to name the tracker and manage the list of associated [VendorFilament]s.
 *
 * @param onDismiss Callback when the dialog is dismissed.
 * @param trueAddOrFalseEdit True if creating a new tracker, false if editing an existing one.
 * @param tracker The current tracker data (used as initial state for editing).
 * @param onTrackerAdded Callback when the user confirms the tracker creation/update.
 * @param viewModel The [VendorFilamentsViewModel] for accessing filament data.
 */
@Composable
fun AddTrackerDialog(
    onDismiss: () -> Unit,
    trueAddOrFalseEdit: Boolean = true,
    tracker: TrackerWithFilaments,
    onTrackerAdded: (AvailabilityTracker, List<Int>) -> Unit,
    viewModel: VendorFilamentsViewModel
) {
    val newFilaments = remember { mutableStateListOf<VendorFilament>().apply { addAll(tracker.filaments) } }
    var newAvailabilityTrackerTracker by remember { mutableStateOf(tracker.tracker) }
    var showAddFilamentDialog by remember { mutableStateOf(false) }

    val view = LocalView.current
    (view.parent as? DialogWindowProvider)?.window?.let { window ->
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(8.dp)
                .imePadding(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (trueAddOrFalseEdit) "Add Tracker" else "Update Tracker")
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = newAvailabilityTrackerTracker.name,
                    onValueChange = { newAvailabilityTrackerTracker = newAvailabilityTrackerTracker.copy(name = it) },
                    placeholder = { Text("Tracker Name", style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.height(48.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Column(Modifier.verticalScroll(rememberScrollState()).weight(1f)) {
                    newFilaments.forEach { filament ->
                        val outlineTextStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = filament.colorRgb?.let { Color(0xFF000000 or it.toLong()) } ?: MaterialTheme.colorScheme.onSurface,
                            shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 4f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = filament.brand ?: "", fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = filament.type ?: "", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = filament.colorName ?: "", style = outlineTextStyle)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = filament.packageType ?: "", style = MaterialTheme.typography.bodySmall)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = (filament.weight ?: ""), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            IconButton(onClick = { newFilaments.remove(filament) }) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete filament")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = { showAddFilamentDialog = true }, modifier = Modifier.padding(end = 4.dp)) {
                        Text("+ Filament")
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        onClick = {
                            onTrackerAdded(newAvailabilityTrackerTracker, newFilaments.map { it.id })
                            onDismiss()
                        },
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(if (trueAddOrFalseEdit) "Add" else "Done")
                    }
                }
            }
            if (showAddFilamentDialog) {
                AddFilamentDialog(
                    onDismiss = { showAddFilamentDialog = false },
                    onAddFilaments = { pNewFilaments: List<VendorFilament> ->
                        newFilaments.clear()
                        newFilaments.addAll(pNewFilaments)
                        showAddFilamentDialog = false
                    },
                    filamentList = newFilaments,
                    viewModel = viewModel
                )
            }
        }
    }
}

/**
 * Dialog for selecting [VendorFilament]s to add to a tracker.
 *
 * Provides search and filter capabilities to find specific filaments from the local database.
 *
 * @param onDismiss Callback when the dialog is dismissed.
 * @param onAddFilaments Callback with the list of selected filaments.
 * @param filamentList The current list of selected filaments.
 * @param viewModel The [VendorFilamentsViewModel] for searching and filtering.
 */
@Composable
fun AddFilamentDialog(
    onDismiss: () -> Unit,
    onAddFilaments: (List<VendorFilament>) -> Unit,
    filamentList: List<VendorFilament>,
    viewModel: VendorFilamentsViewModel
) {
    val searchQuery by viewModel.searchText.observeAsState("")
    val brand by viewModel.selectedBrand.observeAsState(null)
    val type by viewModel.selectedType.observeAsState(null)
    val color by viewModel.selectedColorInfo.observeAsState(null)
    val packageType by viewModel.selectedPackageType.observeAsState(null)
    val weight by viewModel.selectedWeight.observeAsState(null)

    val OBrand by viewModel.uniqueBrands.observeAsState(emptyList())
    val OType by viewModel.uniqueTypes.observeAsState(emptyList())
    val OColor by viewModel.uniqueColors.observeAsState(emptyList())
    val OPackageType by viewModel.uniquePackageTypes.observeAsState(emptyList())
    val OWeight by viewModel.uniqueWeights.observeAsState(emptyList())

    val selectedFilaments = remember { mutableStateListOf<VendorFilament>().apply { addAll(filamentList) } }
    val lazyFilamentItems = viewModel.filteredFilamentsPaging.collectAsLazyPagingItems()
    
    val view = LocalView.current
    (view.parent as? DialogWindowProvider)?.window?.let { window ->
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Select Filaments", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp).fillMaxWidth()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchText(it) },
                        placeholder = { Text("Search...", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().verticalScroll(state = rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CompactFilterDropdown("Brand", OBrand, brand) { viewModel.setSelectedBrand(it) }
                    CompactFilterDropdown("Type", OType, type) { viewModel.setSelectedType(it) }
                    CompactFilterDropdown("Color", OColor, color) { viewModel.setSelectedColor(it) }
                    CompactFilterDropdown("Package", OPackageType, packageType) { viewModel.setSelectedPackageType(it) }
                    CompactFilterDropdown("Weight", OWeight, weight) { viewModel.setSelectedWeight(it) }
                }

                Spacer(modifier = Modifier.height(4.dp))

                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(count = lazyFilamentItems.itemCount, key = lazyFilamentItems.itemKey { it.id }) { index ->
                        val filament = lazyFilamentItems[index]
                        if (filament != null) {
                            val isSelected = selectedFilaments.any { it.id == filament.id }
                            val outlineTextStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = filament.colorRgb?.let { Color(0xFF000000 or it.toLong()) } ?: MaterialTheme.colorScheme.onSurface,
                                shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 4f)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                                    .border(width = 1.dp, color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, shape = RoundedCornerShape(4.dp))
                                    .selectable(selected = isSelected, onClick = { 
                                        if (isSelected) selectedFilaments.removeAll { it.id == filament.id } else selectedFilaments.add(filament) 
                                    })
                                    .padding(vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = filament.brand ?: "", fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = filament.type ?: "", style = MaterialTheme.typography.bodyMedium)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = filament.colorName ?: "Unknown Color", style = outlineTextStyle)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = filament.packageType ?: "", style = MaterialTheme.typography.bodySmall)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = (filament.weight ?: ""), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { onAddFilaments(selectedFilaments) }, enabled = selectedFilaments.isNotEmpty()) {
                        Text("Add")
                    }
                }
            }
        }
    }
}

/**
 * A compact dropdown menu for filtering filaments by string attributes (e.g., Brand, Type).
 *
 * @param label The label for the filter (e.g., "Brand").
 * @param options The list of available string options.
 * @param selectedOption The currently selected option, or null for "Any".
 * @param onOptionSelected Callback when an option is selected.
 */
@Composable
fun CompactFilterDropdown(
    label: String,
    options: List<String>,
    selectedOption: String?,
    onOptionSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(2.dp)) {
        Surface(
            modifier = Modifier.width(IntrinsicSize.Min).clickable { expanded = true },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = Color.Transparent
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Column {
                    Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                    Text(text = selectedOption ?: "Any", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Any") }, onClick = { onOptionSelected(null); expanded = false })
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onOptionSelected(option); expanded = false })
            }
        }
    }
}

/**
 * A compact dropdown menu for filtering filaments by [ColorInfo].
 *
 * Specializes in displaying color previews and names with high readability.
 *
 * @param label The label for the filter (e.g., "Color").
 * @param options The list of available [ColorInfo] options.
 * @param selectedOption The currently selected [ColorInfo], or null for "Any".
 * @param onOptionSelected Callback when a color is selected.
 */
@Composable
fun CompactFilterDropdown(
    label: String,
    options: List<ColorInfo>,
    selectedOption: ColorInfo?,
    onOptionSelected: (ColorInfo?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val bgColor = if (selectedOption != null) Color(selectedOption.colorRgb ?: Color.Transparent.toColorLong().toInt()) else Color.Transparent
    val outlineTextStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 4f))

    Box(modifier = Modifier.padding(2.dp)) {
        Surface(
            modifier = Modifier.width(IntrinsicSize.Min).clickable { expanded = true },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = bgColor
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Column {
                    Text(text = label, style = MaterialTheme.typography.labelSmall.copy(shadow = Shadow(Color.Black, blurRadius = 2f)), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                    Text(text = selectedOption?.colorName ?: "Any", style = outlineTextStyle, maxLines = 1)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Any") }, onClick = { onOptionSelected(null); expanded = false })
            options.forEach { option ->
                val itemBg = Color(option.colorRgb ?: (0xFF000000.toInt() and 0xFFFFFFFF.toInt()))
                DropdownMenuItem(text = { Text(text = option.colorName ?: "", style = outlineTextStyle) }, onClick = { onOptionSelected(option); expanded = false }, modifier = Modifier.background(itemBg))
            }
        }
    }
}
