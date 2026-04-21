package com.napps.filamentmanager

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.napps.filamentmanager.database.BambuPrinter
import com.napps.filamentmanager.database.BambuViewModel
import com.napps.filamentmanager.database.SyncRegion
import com.napps.filamentmanager.database.UserPreferencesRepository
import com.napps.filamentmanager.mqtt.AmsTrayDetail
import com.napps.filamentmanager.mqtt.BambuState
import com.napps.filamentmanager.ui.SyncWarningDialog
import com.napps.filamentmanager.ui.WarningIconOverlay
import com.napps.filamentmanager.util.SecuritySession
import com.napps.filamentmanager.util.tourTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.graphics.toColorInt
import androidx.compose.runtime.snapshots.SnapshotStateMap

val ShadowTextStyle = TextStyle(
    fontWeight = FontWeight.Bold,
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.5f),
        offset = Offset(2f, 2f),
        blurRadius = 4f
    )
)

/**
 * Screen for managing Bambu Lab account connection and printer status.
 * This file handles:
 * 1. Bambu Cloud OAuth login via WebView.
 * 2. Real-time printer status monitoring via MQTT.
 * 3. AMS (Automatic Material System) tray visualization.
 * 4. Region switching between Global and China servers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BambuAccountScreen(
    viewModel: BambuViewModel,
    userPrefs: UserPreferencesRepository,
    tourTargets: SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() },
    isTourActive: Boolean = false,
    lazyListState: LazyListState = rememberLazyListState()
) {
    val authData by viewModel.authData.collectAsState()
    val printerStates by viewModel.printerStates.collectAsState()
    val workInfos by viewModel.workInfos.observeAsState()
    val debugWorkInfo by viewModel.debugWorkInfo.observeAsState()
    val printers by viewModel.allPrinters.observeAsState(emptyList())
    val isTokenExpired by viewModel.isTokenExpired.collectAsState()
    val isDecryptionFailed by viewModel.isDecryptionFailed.collectAsState()
    val debugForceTokenExpired by userPrefs.debugForceTokenExpiredFlow.collectAsState(initial = false)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val ignoreSyncWarning by userPrefs.ignoreSyncWarningFlow.collectAsStateWithLifecycle(initialValue = false)
    val hasFirstSyncFinished by userPrefs.hasFirstSyncFinishedFlow.collectAsStateWithLifecycle(initialValue = true)
    var showSyncWarning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /**
     * Problem Fix: Reconnect MQTT when the screen becomes active.
     * This ensures that if the app was suspended, the connection is refreshed
     * as soon as the user navigates back to this screen.
     */
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (authData != null) {
                    viewModel.startLiveUpdates()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Displays the current status of the background synchronization worker.
    val workerStatus = remember(workInfos, debugWorkInfo) {
        val periodicInfo = workInfos?.firstOrNull()
        if (debugWorkInfo?.state == WorkInfo.State.RUNNING) "Manual Syncing..."
        else when (periodicInfo?.state) {
            WorkInfo.State.RUNNING -> "Syncing..."
            WorkInfo.State.ENQUEUED -> "Scheduled availability updates active"
            WorkInfo.State.BLOCKED -> "Pending Network"
            WorkInfo.State.FAILED -> "Sync Failed"
            else -> "Availability Sync Active"
        }
    }

    var showDialog by remember { mutableStateOf(false) }
    var newSerial by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showLoginWebView by remember { mutableStateOf(false) }
    var showExpirationDialog by remember { mutableStateOf(false) }
    var showDeleteAccountConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 4.dp) {
                Row(modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bambu Account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        val statusText = when {
                            authData == null -> "Not logged in"
                            isDecryptionFailed -> "Keystore keys mismatch - Please refresh token"
                            isTokenExpired || debugForceTokenExpired -> "Token expired - Please re-login"
                            else -> "Connected"
                        }
                        val statusColor = if (isDecryptionFailed || isTokenExpired || debugForceTokenExpired) 
                            MaterialTheme.colorScheme.error 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                        Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor)
                    }

                    // Region switching menu for Global vs China MQTT brokers.
                    if (authData != null) {
                        Box {
                            // TOUR: More Options (Linked to acc_more in MainActivity)
                            IconButton(onClick = { showMenu = true }, modifier = Modifier.tourTarget("acc_more", tourTargets)) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Options")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            text = "Global MQTT", 
                                            fontWeight = if (!SecuritySession.getIsInChina()) FontWeight.Bold else FontWeight.Normal,
                                            color = if (!SecuritySession.getIsInChina()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        ) 
                                    },
                                    onClick = {
                                        showMenu = false
                                        viewModel.updateAuthMetadata(SyncRegion.EU, false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            text = "China MQTT", 
                                            fontWeight = if (SecuritySession.getIsInChina()) FontWeight.Bold else FontWeight.Normal,
                                            color = if (SecuritySession.getIsInChina()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        ) 
                                    },
                                    onClick = {
                                        showMenu = false
                                        viewModel.updateAuthMetadata(SyncRegion.ASIA, true)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (authData != null) {
                    // TOUR: Refresh All (Linked to acc_refresh in MainActivity)
                    SmallFloatingActionButton(
                        onClick = {
                            Toast.makeText(context, "Requesting Updates...", Toast.LENGTH_SHORT).show()
                            viewModel.forceRefreshAll()
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.tourTarget("acc_refresh", tourTargets)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh All")
                    }

                    FloatingActionButton(
                        onClick = { showLoginWebView = true },
                        containerColor = if (isDecryptionFailed || isTokenExpired || debugForceTokenExpired) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (isDecryptionFailed || isTokenExpired || debugForceTokenExpired) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.tourTarget("acc_quick_relogin", tourTargets)
                    ) {
                        Box(contentAlignment = Alignment.Center) {

                            Icon(Icons.Default.Refresh,
                                contentDescription = null, modifier = Modifier.size(38.dp))
                            Icon(
                                Icons.Default.Person,
                                contentDescription = "Quick Relogin",
                                modifier = Modifier.size(16.dp)
                            )

                        }
                    }
                }

                // TOUR: Add Printer (Linked to acc_add in MainActivity)
                FloatingActionButton(
                    onClick = {
                        if (authData == null) {
                            showLoginWebView = true
                        } else if (!hasFirstSyncFinished && !ignoreSyncWarning) {
                            showSyncWarning = true
                        } else {
                            showDialog = true
                        }
                    },
                    containerColor = if (authData == null || hasFirstSyncFinished) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    contentColor = if (authData == null || hasFirstSyncFinished) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    elevation = if (authData == null || hasFirstSyncFinished) FloatingActionButtonDefaults.elevation() else FloatingActionButtonDefaults.loweredElevation(),
                    modifier = if (authData == null) Modifier.tourTarget("acc_login", tourTargets) else Modifier.tourTarget("acc_add", tourTargets)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                        if (authData == null) {
                            Icon(Icons.Default.PersonAdd, contentDescription = "Login")
                        } else {
                            Text("+", fontSize = 24.sp)
                            if (!hasFirstSyncFinished) {
                                WarningIconOverlay()
                            }
                        }
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
                            showDialog = true
                        }
                    },
                    isAccountPage = true
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showLoginWebView) {
                Box(modifier = Modifier.fillMaxSize()) {
                    BambuLoginWebView { uid, rawToken ->
                        viewModel.saveAuth(uid, rawToken, SecuritySession.getRegion(), SecuritySession.getIsInChina())
                        showLoginWebView = false
                    }
                    IconButton(
                        onClick = { showLoginWebView = false },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.Red)
                    }
                }
            } else if (authData == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No Bambu Account connected.\nLogin to sync your printers and filaments.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { showLoginWebView = true },
                        //modifier = Modifier.tourTarget("acc_login", tourTargets)
                    ) {
                        Text("Login to Bambu account")
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        state = lazyListState
                    ) {
                        itemsIndexed(printers, key = { _, printer: BambuPrinter -> printer.hashedSerial }) { index, printer ->
                            val state = printerStates[printer.hashedSerial] ?: BambuState(
                                serial = SecuritySession.getRawSerial(printer.hashedSerial) ?: "",
                                printerName = printer.name,
                                isConnected = false,
                                connectionStatus = "Waiting..."
                            )
                            PrinterRowItem(
                                state = state,
                                workerStatus = workerStatus,
                                printerNameFromDb = printer.name,
                                hashedSn = printer.hashedSerial,
                                onRemove = { hashedSn -> viewModel.removePrinter(hashedSn) },
                                onUpdate = { hashedSn, newSerial, newName -> 
                                    viewModel.updatePrinterInfo(hashedSn, newSerial, newName)
                                },
                                tourTargets = tourTargets,
                                isTourTarget = index == 0
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = { showDeleteAccountConfirm = true },
                        modifier = Modifier.tourTarget("acc_delete", tourTargets),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Delete account information", color = Color.Red)
                            val ageText = remember(authData?.lastUpdated) {
                                authData?.let {
                                    val diff = System.currentTimeMillis() - it.lastUpdated
                                    val days = diff / (24 * 60 * 60 * 1000)
                                    val hours = (diff / (60 * 60 * 1000)) % 24
                                    when {
                                        days > 0 -> "Token age: $days days"
                                        hours > 0 -> "Token age: $hours hours"
                                        else -> "Token age: < 1 hour"
                                    }
                                } ?: ""
                            }
                            if (ageText.isNotEmpty()) {
                                Text(
                                    text = ageText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteAccountConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountConfirm = false },
            title = { Text("Delete Account Information") },
            text = { Text("This will remove your login token and all synchronized printer data. You will need to log in again to see your printers. Are you sure?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearBambuAuth()
                        showDeleteAccountConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showExpirationDialog) {
        AlertDialog(
            onDismissRequest = { showExpirationDialog = false },
            title = { Text("Token Expired") },
            text = { Text("Your Bambu Cloud token has expired. To get a new token, please re-login to your Bambu account.") },
            confirmButton = {
                Button(onClick = {
                    showExpirationDialog = false
                    showLoginWebView = true
                }) { Text("Login") }
            },
            dismissButton = {
                TextButton(onClick = { showExpirationDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Add Printer Serial") },
            text = {
                OutlinedTextField(value = newSerial, onValueChange = { newSerial = it }, label = { Text("Serial Number") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    if (newSerial.isNotBlank()) {
                        viewModel.appendPrinterSerial(newSerial, userPreferences = userPrefs)
                        showDialog = false
                        newSerial = ""
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
        )
    }
}

/**
 * Component representing a single printer row.
 * Displays connection status, name, signal strength, and AMS tray details.
 */
@Composable
fun PrinterRowItem(
    state: BambuState,
    workerStatus: String,
    printerNameFromDb: String,
    hashedSn: String,
    onRemove: (String) -> Unit,
    onUpdate: (String, String, String) -> Unit,
    tourTargets: SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() },
    isTourTarget: Boolean = false
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var tempName by remember { mutableStateOf(printerNameFromDb) }
    var tempSerial by remember { mutableStateOf("") }

    val displayName = printerNameFromDb.ifEmpty { state.printerName.ifEmpty { "Printer" } }
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val activeColor = Color(0xFF32CD32) // Shared green for connectivity and active elements
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).let {
            // TOUR: Printer Card (Linked to acc_printer_card in MainActivity)
            if (isTourTarget) it.tourTarget("acc_printer_card", tourTargets) else it
        },
        border = BorderStroke(1.dp, if (state.isConnected) activeColor.copy(alpha = 0.3f) else Color.LightGray.copy(alpha = 0.5f))
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            // Main content column handles tapping to edit.
            Column(modifier = Modifier.fillMaxWidth().clickable { 
                tempName = displayName
                tempSerial = SecuritySession.getRawSerial(hashedSn) ?: ""
                showEditDialog = true 
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).background(if (state.isConnected) activeColor else Color.Gray, CircleShape).align(Alignment.CenterVertically))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = displayName, style = ShadowTextStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
                    Badge(containerColor = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.padding(start = 8.dp)) {
                        Text(state.wifiSignal, modifier = Modifier.padding(horizontal = 6.dp), fontSize = 10.sp)
                    }
                }

                // Sync status and timestamps.
                Column(modifier = Modifier.padding(start = 18.dp)) {
                    Text("• $workerStatus", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    state.lastWorkerSync?.let {
                        Text("  (Last update: ${sdf.format(Date(it))})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    }
                    if (state.errorMessage != null) {
                        Text("  (${state.errorMessage})", style = MaterialTheme.typography.labelSmall, color = Color.Red)
                    }
                    // Printer Hardware/HMS/Runout Errors
                    if (state.hmsList.isNotEmpty() || state.printError != 0L) {
                        val isRunout = com.napps.filamentmanager.mqtt.HMSCodes.isRunout(state.hmsList, state.printError)
                        val hmsError = if (state.hmsList.isNotEmpty()) {
                            if (isRunout) "Filament Runout!" 
                            else "Hardware Error: 0x${state.hmsList.first().toString(16).uppercase()}"
                        } else null
                        
                        val pError = if (state.printError != 0L && !isRunout) "Print Error: ${state.printError}" else null
                        val combinedError = listOfNotNull(hmsError, pError).joinToString(" | ")
                        
                        val errorColor = if (isRunout) activeColor else Color.Red
                        Text("  $combinedError", style = MaterialTheme.typography.labelSmall, color = errorColor, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(thickness = 1.dp, color = Color.LightGray.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                // Visualization of AMS trays if available.
                if (state.amsUnits.isNotEmpty()) {
                    val currentTrayIndex = state.trayNow.toIntOrNull() ?: -1
                    state.amsUnits.forEachIndexed { amsIndex, ams ->
                        Text("AMS $amsIndex", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = Color.LightGray)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .let {
                                    // TOUR: AMS Trays (Linked to acc_ams in MainActivity)
                                    if (isTourTarget) it.tourTarget("acc_ams", tourTargets) else it
                                },
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ams.trays.forEach { tray ->
                                val trayId = tray.trayIndex.toIntOrNull() ?: -1
                                val globalTrayId = if (trayId != -1) (amsIndex * 4) + trayId else -1
                                val isCurrent = globalTrayId != -1 && globalTrayId == currentTrayIndex
                                
                                Box(modifier = Modifier.weight(1f)) { 
                                    TrayVisualComponent(tray, isCurrent = isCurrent) 
                                } 
                            }
                        }
                    }
                }

                // Show External Spool text if active
                if (state.trayNow == "254") {
                    val type = state.vtTray?.trayType ?: ""
                    Text(
                        text = "External Spool $type loaded",
                        style = MaterialTheme.typography.labelLarge,
                        color = activeColor,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
                    Column(horizontalAlignment = Alignment.End) {
                        state.lastAmsUpdate?.let { ts ->
                            Text("Last AMS Sync: ${sdf.format(Date(ts))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), fontSize = 9.sp)
                        }
                    }
                }
            }

            IconButton(
                onClick = { showDeleteConfirmDialog = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .let {
                        // TOUR: Delete Printer (Linked to acc_printer_delete in MainActivity)
                        if (isTourTarget) it.tourTarget("acc_printer_delete", tourTargets) else it
                    }
            ) {
                Icon(Icons.Default.Delete, null, tint = Color.Red.copy(0.6f))
            }

            // --- Dialogs ---
            if (showDeleteConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmDialog = false },
                    title = { Text("Delete Printer") },
                    text = { Text("Are you sure you want to remove '$displayName'?") },
                    confirmButton = {
                        Button(
                            onClick = {
                                onRemove(hashedSn)
                                showDeleteConfirmDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("Cancel") }
                    }
                )
            }

            if (showEditDialog) {
                AlertDialog(
                    onDismissRequest = { showEditDialog = false },
                    title = { Text("Edit Printer") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = tempName, onValueChange = { tempName = it }, label = { Text("Friendly Name") })
                            OutlinedTextField(value = tempSerial, onValueChange = { tempSerial = it }, label = { Text("Serial Number") })
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            onUpdate(hashedSn, tempSerial, tempName)
                            showEditDialog = false
                        }) { Text("Save") }
                    }
                )
            }
        }
    }
}

/**
 * Visual representation of a single filament tray.
 * Displays color, fill percentage, and sub-brand.
 */
@Composable
fun TrayVisualComponent(tray: AmsTrayDetail, isCurrent: Boolean = false) {
    val filamentColor = try { Color("#${tray.colorHex.removePrefix("#")}".toColorInt()) } catch (e: Exception) { Color.Gray }
    val borderColor = if (isCurrent) Color(0xFF32CD32) else Color.LightGray
    val borderThickness = 1.dp
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .border(borderThickness, borderColor, RoundedCornerShape(4.dp))
                .background(Color.DarkGray.copy(alpha = 0.05f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(tray.remain.coerceIn(0, 100).toFloat() / 100f).background(filamentColor, RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)))
            Text(text = "${tray.remain}%", style = ShadowTextStyle.copy(fontSize = 10.sp, color = Color.White), modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(text = tray.subBrand, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * WebView component for logging into Bambu Lab account.
 * Uses a polling mechanism to detect login completion via cookies or localStorage.
 */
/**
 * WebView component for logging into Bambu Lab account.
 * Uses a polling mechanism to detect login completion via cookies or localStorage.
 *
 * @param onLoginSuccess Callback when login is successful, providing UID and access token.
 */
@Composable
fun BambuLoginWebView(onLoginSuccess: (String, String) -> Unit) {
    val isEu = remember { SyncRegion.EU == SecuritySession.getRegion() }
    val loginUrl = if (isEu) "https://bambulab.com/en-eu/sign-in" else "https://bambulab.com/en/sign-in"
    val cookieManager = remember { CookieManager.getInstance() }
    var webViewRef: WebView? by remember { mutableStateOf(null) }
    var isFinished by remember { mutableStateOf(false) }

    // Poll for the session token in cookies or localStorage.
    LaunchedEffect(Unit) {
        while (!isFinished) {
            val cookies = cookieManager.getCookie("https://bambulab.com") ?: ""
            val cookieToken = extractCookieValue(cookies, "accessToken") ?: extractCookieValue(cookies, "token")
            if (cookieToken != null) {
                isFinished = true
                fetchUidAndFinish(cookieToken) { uid, token -> onLoginSuccess(uid, token) }
                break
            }
            withContext(Dispatchers.Main) {
                webViewRef?.evaluateJavascript("(function() { return localStorage.getItem('token') || localStorage.getItem('accessToken') || localStorage.getItem('access_token'); })();") { result ->
                    val cleanToken = result?.trim()?.replace("\"", "")
                    if (!cleanToken.isNullOrEmpty() && cleanToken != "null" && !isFinished) {
                        isFinished = true
                        fetchUidAndFinish(cleanToken) { uid, token -> onLoginSuccess(uid, token) }
                    }
                }
            }
            delay(2000)
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef = this
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                
                // Enable Autofill support for Google Login.
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.allowContentAccess = true
                settings.allowFileAccess = true
                @Suppress("DEPRECATION")
                settings.saveFormData = true
                
                webChromeClient = android.webkit.WebChromeClient()
                requestFocus()
                
                // Use a modern Chrome user agent to avoid Google's "Insecure App" WebView block.
                val chromeUserAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                settings.userAgentString = chromeUserAgent
                
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                loadUrl(loginUrl)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Extracts a specific cookie value from a cookie string.
 *
 * @param cookies The full cookie string.
 * @param key The key to look for.
 * @return The value of the cookie, or null if not found.
 */
private fun extractCookieValue(cookies: String, key: String): String? {
    return cookies.split(";").find { it.trim().startsWith("$key=") }?.substringAfter("=")?.trim()
}

/**
 * Fetches the user's UID from Bambu Lab API and triggers the success callback.
 *
 * @param token The access token obtained from WebView.
 * @param onLoginSuccess Callback to notify completion.
 */
private fun fetchUidAndFinish(token: String, onLoginSuccess: (String, String) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val url = URL("https://api.bambulab.com/v1/design-user-service/my/profile")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val rawUid = json.optString("uid", "")
                if (rawUid.isNotEmpty()) {
                    withContext(Dispatchers.Main) { onLoginSuccess("u_$rawUid", token) }
                }
            }
        } catch (e: Exception) {
            // Log error
        }
    }
}
