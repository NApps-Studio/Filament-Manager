package com.napps.filamentmanager

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import com.napps.filamentmanager.util.tourTarget
import com.napps.filamentmanager.util.TourOverlay
import com.napps.filamentmanager.util.TourStep
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.res.painterResource
import com.napps.filamentmanager.database.*
import com.napps.filamentmanager.ui.theme.FilamentManagerTheme
import com.napps.filamentmanager.util.BambuTagReader
import com.napps.filamentmanager.database.SyncRegion
import com.napps.filamentmanager.webscraper.FullSyncWorker
import com.napps.filamentmanager.webscraper.StartPagesOfVendors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import androidx.compose.ui.unit.dp
import com.napps.filamentmanager.ui.InventoryLimitsScreen
import com.napps.filamentmanager.ui.SyncReportsScreen

/**
 * Main entry point for the Filament Manager application.
 *
 * This activity manages the primary navigation structure using a [NavigationSuiteScaffold]
 * and coordinates global app states such as NFC scanning, MQTT live updates, and 
 * initial setup sequences (permissions, region selection, and data scraping).
 *
 * Key Responsibilities:
 * - Lifecycle-aware MQTT connection management.
 * - NFC Foreground Dispatch for Bambu RFID tag scanning.
 * - Initial app configuration flow (Notifications, Region, First Sync).
 * - Navigation between main features: Inventory, Availability, and Account.
 */
class MainActivity : androidx.fragment.app.FragmentActivity() {
    private lateinit var executor: Executor
    private val intentState = mutableStateOf<Intent?>(null)

    private val viewModelAvailability: VendorFilamentsViewModel by viewModels {
        val userPrefs = UserPreferencesRepository(applicationContext)
        VendorFilamentsViewModelFactory((application as FilamentManagerApplication).vendorFilamentRepository, userPrefs)
    }

    private val viewModelInventory: FilamentInventoryViewModel by viewModels {
        val userPrefs = UserPreferencesRepository(applicationContext)
        val repo = (application as FilamentManagerApplication).filamentInventoryRepository
        FilamentInventoryViewModelFactory(repo, userPrefs)
    }

    private val viewModelBambu: BambuViewModel by viewModels {
        BambuViewModelFactory(application, (application as FilamentManagerApplication).bambuRepository)
    }

    private val viewModelLimits: InventoryLimitViewModel by viewModels {
        InventoryLimitViewModelFactory((application as FilamentManagerApplication).inventoryLimitRepository)
    }

    private val viewModelSyncReports: SyncReportViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean -> }

    /**
     * Initializes the activity, sets up view models, and handles the initial UI composition.
     * It also initiates the MQTT connection and checks for required permissions.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intentState.value = intent
        enableEdgeToEdge()
        executor = ContextCompat.getMainExecutor(this)

        // Observe lifecycle to start/stop live MQTT connection
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModelBambu.startLiveUpdates()
                }
                Lifecycle.Event.ON_STOP -> {
                    viewModelBambu.stopLiveUpdates()
                }
                else -> {}
            }
        })

        // Ensure trackers are synced with existing limits on startup.
        // Data changes will now trigger the InventoryLimitWorker automatically.
        viewModelLimits.syncLimitsWithTrackers()

        setContent {
            FilamentManagerTheme {
                val isScanSheetVisible by viewModelInventory.isScanSheetVisible.collectAsState()
                val nfcStatus by viewModelInventory.nfcScanningState.collectAsState()

                LaunchedEffect(isScanSheetVisible, nfcStatus) {
                    if (isScanSheetVisible && nfcStatus == FilamentInventoryViewModel.NfcStatus.Scanning) {
                        enableNfcForegroundDispatch()
                    } else {
                        disableNfcForegroundDispatch()
                    }
                }

                // --- PERMISSION FLOW LOGIC ---
                var showNotificationDialog by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val hasNotifyPerm = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        if (!hasNotifyPerm) {
                            showNotificationDialog = true
                        }
                    }
                }

                if (showNotificationDialog) {
                    PermissionExplanationDialog(
                        title = "Enable Notifications",
                        description = "We need permission to alert you when filament is back in stock and to receive important app updates.",
                        onConfirm = {
                            showNotificationDialog = false
                            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onDismiss = { showNotificationDialog = false }
                    )
                }

                // --- REGION SETUP POPUP ---
                val scope = rememberCoroutineScope()
                val userPrefs = remember { UserPreferencesRepository(applicationContext) }
                val hasSetRegion by userPrefs.hasSetRegionFlow.collectAsState(initial = false)
                val mainAuthData by viewModelBambu.authData.collectAsState()

                if (!hasSetRegion && !showNotificationDialog) {
                    SetupRegionDialog { region, isChina ->
                        scope.launch {
                            userPrefs.saveRegion(region, isChina)
                            mainAuthData?.let {
                                viewModelBambu.updateAuthMetadata(region, isChina)
                            }
                        }
                    }
                }

                // --- FULL SYNC POPUP ---
                val isSyncCompletedState = userPrefs.hasFirstSyncFinishedFlow.collectAsState(initial = true)
                val isSyncCompleted = isSyncCompletedState.value

                var showInitialSyncDialog by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(isSyncCompleted, showNotificationDialog, hasSetRegion) {
                    if (!isSyncCompleted && !showNotificationDialog && hasSetRegion) {
                        delay(200)
                        showInitialSyncDialog = true
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
                                FullSyncWorker.enqueue(applicationContext, force = true)
                                showRestartSyncDialog = false
                            }) { Text("Restart") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRestartSyncDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                if (showInitialSyncDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showInitialSyncDialog = false
                            // User dismissed initial setup dialog - don't force completion
                        },
                        title = { Text("Full Data Sync") },
                        text = { Text("To enable stock monitoring, ensure correct color mapping for filaments, and link Bambu spools to their store counterparts, a full data sync is required. This ensures that filaments added via NFC scan or from the catalog match the live store data.\n\nPlease note that this process will use your internet connection. This can take some time. You can see the status of the sync on the 'Availability' page. The sync will continue in the background and you will receive a notification upon its completion.\n\nNote: This process may occasionally get stuck due to store loading times. If it doesn't progress for several minutes, you may need to run it again from the 'Availability' page. It is highly recommended to wait for this to finish before adding any filaments or printers.") },
                        confirmButton = {
                            Button(onClick = {
                                showInitialSyncDialog = false
                                scope.launch {
                                    if (FullSyncWorker.isWorkRunning(applicationContext)) {
                                        showRestartSyncDialog = true
                                    } else {
                                        FullSyncWorker.enqueue(applicationContext)
                                    }
                                }
                            }) {
                                Text("Run Full Sync Now")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showInitialSyncDialog = false
                                // Sync later does not mean it's finished
                            }) {
                                Text("Sync Later")
                            }
                        }
                    )
                }

                // --- BATTERY OPTIMIZATION POPUP ---
                val hasShownBatteryDialog by userPrefs.hasShownBatteryOptimizationFlow.collectAsState(initial = false)
                var showBatteryDialog by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(hasShownBatteryDialog, showNotificationDialog, hasSetRegion, showInitialSyncDialog) {
                    if (!hasShownBatteryDialog && !showNotificationDialog && hasSetRegion && !showInitialSyncDialog) {
                        // Wait slightly longer than the Full Sync delay to ensure they appear in sequence
                        delay(200)
                        if (!showInitialSyncDialog) {
                            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                                showBatteryDialog = true
                            } else {
                                userPrefs.setBatteryOptimizationShown(true)
                            }
                        }
                    }
                }

                if (showBatteryDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showBatteryDialog = false
                            scope.launch { userPrefs.setBatteryOptimizationShown(true) }
                        },
                        title = { Text("Reliable Background Sync") },
                        text = { Text("To ensure printer status updates and shop stock monitoring work reliably in the background, please consider removing battery optimizations for this app.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showBatteryDialog = false
                                scope.launch { userPrefs.setBatteryOptimizationShown(true) }
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                startActivity(intent)
                            }) {
                                Text("Open Settings")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showBatteryDialog = false
                                scope.launch { userPrefs.setBatteryOptimizationShown(true) }
                            }) {
                                Text("Later")
                            }
                        }
                    )
                }

                // --- APP TOUR DECISION POPUP ---
                val hasShownTourDecision by userPrefs.hasShownTourDecisionFlow.collectAsState(initial = false)
                var showTourDecisionDialog by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(hasShownTourDecision, showBatteryDialog, showInitialSyncDialog, hasSetRegion, showNotificationDialog) {
                    if (!hasShownTourDecision && !showBatteryDialog && !showInitialSyncDialog && hasSetRegion && !showNotificationDialog) {
                        showTourDecisionDialog = true
                    }
                }

                if (showTourDecisionDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showTourDecisionDialog = false
                            scope.launch { userPrefs.setTourDecisionShown(true) }
                        },
                        title = { Text("Take a Tour?") },
                        text = { Text("Would you like to take a quick tour of the app's features? You can also enable or disable tours later in Settings.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showTourDecisionDialog = false
                                scope.launch { 
                                    userPrefs.setAllToursEnabled(true)
                                    userPrefs.setTourDecisionShown(true)
                                }
                            }) {
                                Text("Yes, Show Me")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showTourDecisionDialog = false
                                scope.launch { 
                                    userPrefs.setAllToursEnabled(false)
                                    userPrefs.setTourDecisionShown(true)
                                }
                            }) {
                                Text("No, Skip All")
                            }
                        }
                    )
                }

                LaunchedEffect(Unit) {
                    val currentA = viewModelAvailability.getMenuTextStatic("TopMenuRow1")
                    if (currentA == null) {
                        viewModelAvailability.insert(AvailabilityMenuText(id = 1, name = "TopMenuRow1", text = ""))
                    }
                }

                FilamentManagerApp(
                    viewModelVendor = viewModelAvailability,
                    viewModelInventory = viewModelInventory,
                    viewModelBambu = viewModelBambu,
                    viewModelLimits = viewModelLimits,
                    viewModelSyncReports = viewModelSyncReports,
                    userPrefs = userPrefs,
                    intent = intentState.value
                )
            }
        }
    }

    /**
     * Composable dialog that prompts the user to select their store region (e.g., Global, China).
     * This choice affects both the web scraping targets and the MQTT broker used.
     *
     * @param onSelected Callback triggered when a region is selected.
     */
    @Composable
    fun SetupRegionDialog(onSelected: (SyncRegion, Boolean) -> Unit) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Initial Setup") },
            text = {
                Column {
                    Text("Please select your region for the Bambu Lab store and MQTT server.")
                    Spacer(Modifier.height(16.dp))
                    SyncRegion.entries.forEach { region ->
                        Button(
                            onClick = { onSelected(region, region == SyncRegion.ASIA) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(region.name)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    override fun onResume() {
        super.onResume()
        if (viewModelInventory.isScanSheetVisible.value) {
            enableNfcForegroundDispatch()
        }
    }

    override fun onPause() {
        super.onPause()
        disableNfcForegroundDispatch()
    }

    /**
     * Responds to new intents while the activity is in the foreground.
     * Specifically used to capture and process NFC tags discovered via Foreground Dispatch.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentState.value = intent
        
        // Handle NFC Tag discovery while the app is in the foreground.
        // The app uses Foreground Dispatch to capture tags before other apps.
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {

            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            tag?.let {
                lifecycleScope.launch(Dispatchers.IO) {
                    // Bambu tags are MifareClassic based but encrypted/encoded with a proprietary scheme.
                    val result = BambuTagReader.readAndParseBambuTag(it)
                    withContext(Dispatchers.Main) {
                        viewModelInventory.processNfcData(result.first, result.second)
                    }
                }
            }
        }
    }

    /**
     * Enables NFC Foreground Dispatch.
     * This ensures that when the "Add Filament" sheet is open, the app 
     * intercepts NFC tags immediately rather than letting the system show the "New tag scanned" UI.
     */
    private fun enableNfcForegroundDispatch() {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
        val techList = arrayOf(arrayOf(MifareClassic::class.java.name))
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, filters, techList)
    }

    /**
     * Disables NFC Foreground Dispatch to return control to the system.
     */
    private fun disableNfcForegroundDispatch() {
        NfcAdapter.getDefaultAdapter(this)?.disableForegroundDispatch(this)
    }
}

/**
 * A reusable dialog for explaining why a specific permission (like notifications) is required.
 *
 * @param title The dialog title.
 * @param description The explanation text.
 * @param onConfirm Callback for the "Grant" button.
 * @param onDismiss Callback for the "Later" button.
 * @param confirmButtonText Text for the confirm button.
 */
@Composable
fun PermissionExplanationDialog(
    title: String,
    description: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmButtonText: String = "Grant Permission"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = { Text(text = description) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmButtonText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}

/**
 * The root Composable for the application's UI after initial setup.
 * Manages the navigation between main screens and handles the display of various app tours.
 *
 * @param viewModelVendor ViewModel for store filament availability.
 * @param viewModelInventory ViewModel for personal filament inventory.
 * @param viewModelBambu ViewModel for Bambu account and printer data.
 * @param viewModelLimits ViewModel for managing inventory stock limits.
 * @param userPrefs Repository for user settings.
 * @param intent The starting or new intent, used to navigate to specific screens (e.g., from notifications).
 */
@Composable
fun FilamentManagerApp(
    viewModelVendor: VendorFilamentsViewModel,
    viewModelInventory: FilamentInventoryViewModel,
    viewModelBambu: BambuViewModel,
    viewModelLimits: InventoryLimitViewModel,
    viewModelSyncReports: SyncReportViewModel,
    userPrefs: UserPreferencesRepository,
    intent: Intent?
) {
    val savedDefaultPage by userPrefs.defaultFirstPageFlow.collectAsState(initial = "NAPPS")
    var currentDestination by rememberSaveable { mutableStateOf<AppDestinations?>(null) }

    val effectiveDestination = currentDestination ?: AppDestinations.entries.find { it.name == savedDefaultPage } ?: AppDestinations.NAPPS

    val showLimits by viewModelInventory.showLimitsScreen.collectAsState()

    val context = LocalContext.current
    fun Context.findActivity(): Activity? {
        var currentContext = this
        while (currentContext is ContextWrapper) {
            if (currentContext is Activity) return currentContext
            currentContext = currentContext.baseContext
        }
        return null
    }

    val activity = context.findActivity() as? MainActivity

    // Hoist scroll states to share with TourOverlay
    val nappsScrollState = rememberScrollState()
    val availabilityScrollState = rememberScrollState()
    val inventoryLazyListState = rememberLazyListState()
    val limitsLazyListState = rememberLazyListState()
    val bambuLazyListState = rememberLazyListState()
    val settingsScrollState = rememberScrollState()
    val syncReportsScrollState = rememberScrollState()

    val inventoryExpandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val availabilityExpandedTrackers = remember { mutableStateMapOf<Int, Boolean>() }
    val limitsExpanded = remember { mutableStateMapOf<Int, Boolean>() }

    val limits by viewModelLimits.allLimitsWithFilaments.observeAsState(emptyList())

    // TOUR: Limits Screen Tour (Targets set in InventoryLimitsScreen)
    val limitsStepsPage = remember {
        listOf(
            TourStep("lim_more", "Import or export your limits as JSON for backup or sharing."),
            TourStep("lim_fab", "Create a new inventory limit to monitor specific filaments.")
        )
    }

    // TOUR: Limit Card Tour (Targets set in InventoryLimitsScreen)
    val limitsStepsCard = remember(limits) {
        val steps = mutableListOf<TourStep>()
        if (limits.isNotEmpty()) {
            val firstLimit = limits.first()
            steps.add(TourStep("lim_card", "The limit card shows your target and current stock level. Click it to edit its details"))
            steps.add(TourStep("lim_card_expand", "Expand it to see detailed spool information.") {
                limitsExpanded[firstLimit.limit.id] = true
            })
            steps.add(TourStep("lim_copy", "Duplicate this limit to quickly create a similar tracker."))
            steps.add(TourStep("lim_delete", "Permanently remove this limit tracker."))
            steps.add(TourStep("lim_toggle", "Enable or disable this limit. Disabled limits won't trigger notifications or show warnings."))
        }
        steps
    }

    LaunchedEffect(intent) {
        intent?.let {
            if (it.getBooleanExtra("HIGHLIGHT_FULL_SYNC", false) || it.getBooleanExtra("OPEN_AVAILABILITY", false)) {
                currentDestination = AppDestinations.AVAILABILITY
                if (it.getBooleanExtra("HIGHLIGHT_FULL_SYNC", false)) {
                    viewModelInventory.setHighlightFullSync(true)
                }
                it.removeExtra("HIGHLIGHT_FULL_SYNC")
                it.removeExtra("OPEN_AVAILABILITY")
            }

            it.getStringExtra("TARGET_SCREEN")?.let { target ->
                when (target) {
                    "BAMBU_ACCOUNT" -> currentDestination = AppDestinations.BAMBU_ACCOUNT
                    "INVENTORY" -> currentDestination = AppDestinations.INVENTORY
                    "AVAILABILITY" -> {
                        currentDestination = AppDestinations.AVAILABILITY
                        if (it.getBooleanExtra("EXPAND_READY", false)) {
                            viewModelVendor.setExpandReady(true)
                            it.removeExtra("EXPAND_READY")
                        }
                    }
                }
                it.removeExtra("TARGET_SCREEN")
            }
        }
    }

    if (showLimits) {
        val tourTargets = remember { mutableStateMapOf<String, Rect>() }
        val tourFlags by userPrefs.tourFlagsFlow.collectAsState(initial = emptyMap())
        var currentTourStep by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        
        BackHandler {
            viewModelInventory.navigateBackFromLimits()
        }
        Box(modifier = Modifier.fillMaxSize()) {
            val tourFlags by userPrefs.tourFlagsFlow.collectAsState(initial = emptyMap())
            val scope = rememberCoroutineScope()

            InventoryLimitsScreen(
                viewModel = viewModelLimits,
                inventoryViewModel = viewModelInventory,
                vendorViewModel = viewModelVendor,
                tourTargets = tourTargets,
                lazyListState = limitsLazyListState,
                expandedLimits = limitsExpanded,
                onBack = { viewModelInventory.navigateBackFromLimits() }
            )

            val isPageTourActive = (tourFlags["LIMITS"] ?: false)
            val isCardTourActive = (tourFlags["LIMITS_CARD"] ?: false) && limits.isNotEmpty()

            if (isPageTourActive) {
                TourOverlay(
                    steps = limitsStepsPage,
                    currentStepIndex = currentTourStep,
                    targets = tourTargets,
                    lazyListState = limitsLazyListState,
                    onNext = {
                        if (currentTourStep < limitsStepsPage.size - 1) {
                            currentTourStep++
                        } else {
                            scope.launch {
                                userPrefs.updateTourFlag("LIMITS", false)
                                currentTourStep = 0
                            }
                        }
                    }
                )
            } else if (isCardTourActive && limitsStepsCard.isNotEmpty()) {
                TourOverlay(
                    steps = limitsStepsCard,
                    currentStepIndex = currentTourStep,
                    targets = tourTargets,
                    lazyListState = limitsLazyListState,
                    onNext = {
                        if (currentTourStep < limitsStepsCard.size - 1) {
                            currentTourStep++
                        } else {
                            scope.launch {
                                userPrefs.updateTourFlag("LIMITS_CARD", false)
                                currentTourStep = 0
                            }
                        }
                    }
                )
            }
        }
    } else {
        var showExitPrompt by remember { mutableStateOf(false) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showSyncReports by rememberSaveable { mutableStateOf(false) }
        var currentTourStep by remember { mutableIntStateOf(0) }

        BackHandler {
            if (showSettings) {
                showSettings = false
            } else if (showSyncReports) {
                showSyncReports = false
                currentTourStep = 0
            } else if (showExitPrompt) {
                activity?.finish()
            } else {
                showExitPrompt = true
                Toast.makeText(context, "Click again to exit", Toast.LENGTH_SHORT).show()
            }
        }

        LaunchedEffect(showExitPrompt) {
            if (showExitPrompt) {
                delay(2000)
                showExitPrompt = false
            }
        }

        if (showSettings) {
            val scope = rememberCoroutineScope()

            Box(modifier = Modifier.fillMaxSize()) {
                SettingsScreen(
                    userPrefs = userPrefs,
                    bambuViewModel = viewModelBambu,
                    onBack = { showSettings = false },
                    scrollState = settingsScrollState
                )
            }
        } else if (showSyncReports) {
            val tourTargets = remember { mutableStateMapOf<String, Rect>() }
            val tourFlags by userPrefs.tourFlagsFlow.collectAsState(initial = emptyMap())
            val scope = rememberCoroutineScope()

            val syncReportsSteps = listOf(
                TourStep("", "Sync Reports provide a detailed log of sync. If a sync fails, check here to see which specific filaments were affected.")
            )

            Box(modifier = Modifier.fillMaxSize()) {
                SyncReportsScreen(
                    viewModel = viewModelSyncReports,
                    onBack = {
                        showSyncReports = false
                        currentTourStep = 0
                    },
                    tourTargets = tourTargets,
                    isTourActive = tourFlags["SYNC_REPORTS"] ?: false
                )

                if (tourFlags["SYNC_REPORTS"] ?: false) {
                    TourOverlay(
                        steps = syncReportsSteps,
                        currentStepIndex = currentTourStep,
                        targets = tourTargets,
                        onNext = {
                            if (currentTourStep < syncReportsSteps.size - 1) {
                                currentTourStep++
                            } else {
                                scope.launch {
                                    userPrefs.updateTourFlag("SYNC_REPORTS", false)
                                    currentTourStep = 0
                                }
                            }
                        }
                    )
                }
            }
        } else {
            val tourTargets = remember { mutableStateMapOf<String, Rect>() }
            val tourFlags by userPrefs.tourFlagsFlow.collectAsState(initial = emptyMap())
            val authData by viewModelBambu.authData.collectAsState()
            val scope = rememberCoroutineScope()

            // TOUR: Main App Tour (Targets set in NavigationSuite and NAppsScreen)
            val nappsSteps = listOf(
                TourStep("settings", "Configure app behavior, update intervals, and more here."),
                TourStep("nav_napps", "This is the NApps screen. Note that other screens also have their own tours to help you get started!"),
                TourStep("nav_availability", "Setup Bambu Lab stock tracking for filaments and get notified when they are available"),
                TourStep("nav_inventory", "Manage your personal filament stock and setup stock limits"),
                TourStep("nav_account", "Sync your printers to inventory and view their status.")
            )

            val groupedFilaments by viewModelInventory.groupedFilaments.collectAsStateWithLifecycle()
            val summary by viewModelInventory.inventorySummary.collectAsStateWithLifecycle()
            val lowStock by viewModelInventory.lowStockFilaments.collectAsStateWithLifecycle()
            val trackerList by viewModelVendor.allTrackersWithFilaments.observeAsState(emptyList())

            // TOUR: Inventory Screen Tour (Targets set in InventoryScreen)
            val inventoryStepsPage = remember {
                listOf(
                    TourStep("inv_summary", "A quick overview of your inventory: Out-of-Stock / In-AMS / Open / In-Use / New / Total spools and total weight. you can click this to expand."),
                    TourStep("inv_eye", "Toggle the visibility of empty (Out of Stock) spools to keep your list clean."),
                    TourStep("inv_more", "Import or export your inventory as CSV, or configure low-stock limits. Limits create availability trackers when filaments fall below the set threshold"),
                    TourStep("inv_fab", "Scan a Bambu Lab NFC tag to add spools if holding the button you can swith to manuly adding a filament (not recommended). Note: Only filaments added via NFC tag or AMS sync can be used for automatic stock limits and availability tracking and weight tracking, as they contain the necessary identifiers.")
                )
            }

            // TOUR: Low Stock Tour (Targets set in InventoryScreen)
            val lowStockSteps = remember {
                listOf(
                    TourStep("inv_low_stock", "This button appears when you have filaments marked as potentially out of stock. Click it to quickly view and manage spools that are below the set threshold or they ran out while in AMS.")
                )
            }

            // TOUR: Unmapped Resolution Tour
            val unmappedSteps = remember {
                listOf(
                    TourStep("inv_resolve_unmapped", "This button appears when filaments with unknown color names are added. Use it to assign the correct names to the colors.")
                )
            }

            // TOUR: Filament Card Tour (Targets set in InventoryActivity)
            val inventoryStepsCard = remember(groupedFilaments) {
                val steps = mutableListOf<TourStep>()
                if (groupedFilaments.isNotEmpty()) {
                    val firstGroup = groupedFilaments.first()
                    val firstType = firstGroup.groups.firstOrNull()
                    if (firstType != null) {
                        val groupKey = "${firstGroup.vendor}_${firstType.type}"
                        steps.add(TourStep("inv_card_expand", "Filaments are grouped by brand and type. Expand a group to see individual spools.") {
                            inventoryExpandedGroups[groupKey] = true
                        })
                        
                        if (firstType.filaments.isNotEmpty()) {
                            steps.add(TourStep("inv_spool_edit", "Click a filament to edit its details, adjust remaining weight, or change its status."))
                        }
                    }
                }
                steps
            }

            // TOUR: Availability Screen Tour (Targets set in AvailabilityActivity)
            val availabilityStepsPage = listOf(
                TourStep("avail_status", "The status of the Availability tracker (when it last ran and its current status)"),
                TourStep("avail_eye", "Hides or shows trackers created automatically from your inventory limits."),
                TourStep("avail_more", "Import/Export trackers, set your store region,trigger a manual update/full sync or  see the sync reports."),
                TourStep("avail_fab", "Create your own custom trackers by selecting filaments from the catalog.")
            )

            // TOUR: Availability Tracker Card Tour (Targets set in AvailabilityActivity)
            val availabilityStepsCard = remember(trackerList) {
                val steps = mutableListOf<TourStep>()
                val editableTracker = trackerList.find { it.tracker.isEditable }
                if (editableTracker != null) {
                    steps.add(TourStep("avail_card","Click the tracker card to edit its details."))
                    steps.add(TourStep("avail_card_expand", "Expand the tracker card to see all the specific filaments it is monitoring.") {
                        availabilityExpandedTrackers[editableTracker.tracker.id] = true
                    })
                    steps.add(TourStep("avail_card_copy", "Quickly create a copy of this tracker."))
                    steps.add(TourStep("avail_card_cart", "Add all filaments in this tracker directly to your Bambu Lab shopping cart."))
                    steps.add(TourStep("avail_card_delete", "Remove this tracker from your list."))
                    steps.add(TourStep("avail_card_bell", "Toggle whether this tracker is active and sends notifications when stock is available."))
                }
                steps
            }

            val printerList by viewModelBambu.allPrinters.observeAsState(emptyList())

            // TOUR: Bambu Account Screen Tour (Targets set in BambuAccountActivity)
            val bambuAccountStepsBeforeLogin =
                    listOf(
                        TourStep("acc_login", "Login to Bambu account: Enable real-time printer and AMS monitoring. This app securely uses your account UID and an Access Token to connect. No passwords are ever stored.")
                    )
            val bambuAccountStepsAfterLogin =
                    listOf(
                        TourStep("acc_more", "Switch between Global and China MQTT brokers for real-time printer updates."),
                        TourStep("acc_refresh", "Manually trigger a refresh of all printer statuses and AMS tray data."),
                        TourStep("acc_quick_relogin","Relogin into your account when your tokens expire."),
                        TourStep("acc_add", "Add a new printer by entering its serial number."),
                        TourStep("acc_delete", "Disconnect your account and clear all synced printer data. Use this if you want to switch accounts or stop monitoring.")
                    )

            // TOUR: Printer Card Tour (Targets set in BambuAccountActivity)
            val bambuAccountStepsCard = remember(printerList) {
                val steps = mutableListOf<TourStep>()
                if (printerList.isNotEmpty()) {
                    steps.add(TourStep("acc_printer_card", "This card shows the real-time status of your printer. Click anywhere on the card to edit its info or friendly name."))
                    steps.add(TourStep("acc_printer_delete", "Remove this printer from your account monitoring."))
                    
                   /* val hasAms = viewModelBambu.printerStates.value.values.any { it.amsUnits.isNotEmpty() }
                    if (hasAms) {
                        steps.add(TourStep("acc_ams", "The AMS section visualizes your filament trays, showing color, brand, and remaining amount."))
                    }*///not needed
                }
                steps
            }

            val tokenExpiredSteps = remember {
                listOf(
                    TourStep("acc_token_expired", "Your Bambu Lab access token has expired. You need to log in again to restore real-time printer updates and AMS monitoring.")
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                NavigationSuiteScaffold(
                    navigationSuiteItems = {
                        AppDestinations.entries.filter { it.showInNavBar }.forEach {
                            item(
                                icon = {
                                    if (it.icon != null) {
                                        Icon(it.icon, contentDescription = it.label)
                                    } else if (it.iconResId != null) {
                                        Icon(
                                            painter = painterResource(id = it.iconResId),
                                            contentDescription = it.label,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                },
                                label = { Text(it.label, maxLines = 1, softWrap = false) },
                                selected = it == effectiveDestination,
                                onClick = { 
                                    currentDestination = it
                                    currentTourStep = 0 // Reset tour step when switching
                                },
                                modifier = when (it) {
                                    // TOUR: Link to availability tracking (Linked to nav_availability in MainActivity)
                                    AppDestinations.AVAILABILITY -> Modifier.tourTarget("nav_availability", tourTargets)
                                    // TOUR: Link to inventory management (Linked to nav_inventory in MainActivity)
                                    AppDestinations.INVENTORY -> Modifier.tourTarget("nav_inventory", tourTargets)
                                    // TOUR: Link to Bambu account and printer status (Linked to nav_account in MainActivity)
                                    AppDestinations.BAMBU_ACCOUNT -> Modifier.tourTarget("nav_account", tourTargets)
                                    // TOUR: Link to NApps screen (Linked to nav_napps in MainActivity)
                                    AppDestinations.NAPPS -> Modifier.tourTarget("nav_napps", tourTargets)
                                    else -> Modifier
                                }
                            )
                        }
                    }
                ) {
                    Box(modifier = Modifier.fillMaxSize().displayCutoutPadding()) {
                        when (effectiveDestination) {
                            AppDestinations.INVENTORY -> {
                                InventoryScreen(
                                    viewModel = viewModelInventory,
                                    vendorFilamentsViewModel = viewModelVendor,
                                    limitsViewModel = viewModelLimits,
                                    tourTargets = tourTargets,
                                    isTourActive = tourFlags["INVENTORY"] ?: false,
                                    lazyListState = inventoryLazyListState,
                                    expandedGroups = inventoryExpandedGroups
                                )
                            }
                            AppDestinations.AVAILABILITY -> {
                                AvailabilityScreen(
                                    viewModel = viewModelVendor,
                                    inventoryViewModel = viewModelInventory,
                                    bambuViewModel = viewModelBambu,
                                    syncReportViewModel = viewModelSyncReports,
                                    userPrefs = userPrefs,
                                    onShowSyncReports = { 
                                        showSyncReports = true 
                                        currentTourStep = 0
                                    },
                                    tourTargets = tourTargets,
                                    isTourActive = tourFlags["AVAILABILITY"] ?: false,
                                    scrollState = availabilityScrollState,
                                    expandedTrackers = availabilityExpandedTrackers
                                )
                            }
                            AppDestinations.BAMBU_ACCOUNT -> {
                                BambuAccountScreen(
                                    viewModel = viewModelBambu,
                                    userPrefs = userPrefs,
                                    tourTargets = tourTargets,
                                    isTourActive = tourFlags["BAMBU_ACCOUNT"] ?: false,
                                    lazyListState = bambuLazyListState
                                )
                            }
                            AppDestinations.NAPPS -> {
                                NAppsScreen(userPrefs, tourTargets, onSettingsClick = { showSettings = true }, scrollState = nappsScrollState)
                            }
                            else -> {}
                        }
                    }
                }

                if (effectiveDestination == AppDestinations.NAPPS && (tourFlags["NAPPS_SCREEN"] ?: false)) {
                    TourOverlay(
                        steps = nappsSteps,
                        currentStepIndex = currentTourStep,
                        targets = tourTargets,
                        scrollState = nappsScrollState,
                        onNext = {
                            if (currentTourStep < nappsSteps.size - 1) {
                                currentTourStep++
                            } else {
                                scope.launch { 
                                    userPrefs.updateTourFlag("NAPPS_SCREEN", false)
                                    currentTourStep = 0
                                }
                            }
                        }
                    )
                }


                if (effectiveDestination == AppDestinations.AVAILABILITY) {
                    val isPageTourActive = (tourFlags["AVAILABILITY"] ?: false)
                    val isCardTourActive = (tourFlags["AVAILABILITY_CARD"] ?: false) && availabilityStepsCard.isNotEmpty()

                    if (isPageTourActive) {
                        TourOverlay(
                            steps = availabilityStepsPage,
                            currentStepIndex = currentTourStep,
                            targets = tourTargets,
                            scrollState = availabilityScrollState,
                            onNext = {
                                if (currentTourStep < availabilityStepsPage.size - 1) {
                                    currentTourStep++
                                } else {
                                    scope.launch { 
                                        userPrefs.updateTourFlag("AVAILABILITY", false)
                                        currentTourStep = 0
                                    }
                                }
                            }
                        )
                    } else if (isCardTourActive && availabilityStepsCard.isNotEmpty()) {
                        TourOverlay(
                            steps = availabilityStepsCard,
                            currentStepIndex = currentTourStep,
                            targets = tourTargets,
                            scrollState = availabilityScrollState,
                            onNext = {
                                if (currentTourStep < availabilityStepsCard.size - 1) {
                                    currentTourStep++
                                } else {
                                    scope.launch { 
                                        userPrefs.updateTourFlag("AVAILABILITY_CARD", false)
                                        currentTourStep = 0
                                    }
                                }
                            }
                        )
                    }
                }

                if (effectiveDestination == AppDestinations.INVENTORY) {
                    val isPageTourActive = (tourFlags["INVENTORY"] ?: false)
                    val isCardTourActive = (tourFlags["INVENTORY_CARD"] ?: false)
                    val isLowStockTourActive = (tourFlags["LOW_STOCK"] ?: false) && (lowStock.isNotEmpty() || (userPrefs.debugForceOosFlow.collectAsState(initial = false).value))
                    val unmappedList by viewModelInventory.unmappedFilaments.collectAsStateWithLifecycle()
                    val isUnmappedTourActive = (tourFlags["UNMAPPED"] ?: false) && (unmappedList.isNotEmpty() || (userPrefs.debugForceUnmappedFlow.collectAsState(initial = false).value))

                    if (isUnmappedTourActive) {
                        TourOverlay(
                            steps = unmappedSteps,
                            currentStepIndex = currentTourStep,
                            targets = tourTargets,
                            lazyListState = inventoryLazyListState,
                            onNext = {
                                if (currentTourStep < unmappedSteps.size - 1) {
                                    currentTourStep++
                                } else {
                                    scope.launch { 
                                        userPrefs.updateTourFlag("UNMAPPED", false)
                                        currentTourStep = 0
                                    }
                                }
                            }
                        )
                    } else if (isLowStockTourActive) {
                        TourOverlay(
                            steps = lowStockSteps,
                            currentStepIndex = currentTourStep,
                            targets = tourTargets,
                            lazyListState = inventoryLazyListState,
                            onNext = {
                                if (currentTourStep < lowStockSteps.size - 1) {
                                    currentTourStep++
                                } else {
                                    scope.launch { 
                                        userPrefs.updateTourFlag("LOW_STOCK", false)
                                        currentTourStep = 0
                                    }
                                }
                            }
                        )
                    } else if (isPageTourActive) {
                        TourOverlay(
                            steps = inventoryStepsPage,
                            currentStepIndex = currentTourStep,
                            targets = tourTargets,
                            lazyListState = inventoryLazyListState,
                            onNext = {
                                if (currentTourStep < inventoryStepsPage.size - 1) {
                                    currentTourStep++
                                } else {
                                    scope.launch { 
                                        userPrefs.updateTourFlag("INVENTORY", false)
                                        currentTourStep = 0
                                    }
                                }
                            }
                        )
                    } else if (isCardTourActive && inventoryStepsCard.isNotEmpty()) {
                        TourOverlay(
                            steps = inventoryStepsCard,
                            currentStepIndex = currentTourStep,
                            targets = tourTargets,
                            lazyListState = inventoryLazyListState,
                            onNext = {
                                if (currentTourStep < inventoryStepsCard.size - 1) {
                                    currentTourStep++
                                } else {
                                    scope.launch { 
                                        userPrefs.updateTourFlag("INVENTORY_CARD", false)
                                        currentTourStep = 0
                                    }
                                }
                            }
                        )
                    }
                }

                if (effectiveDestination == AppDestinations.BAMBU_ACCOUNT) {
                    val isTokenExpired by viewModelBambu.isTokenExpired.collectAsState()
                    val debugForceTokenExpired by userPrefs.debugForceTokenExpiredFlow.collectAsState(initial = false)
                    val isTokenTourActive = (tourFlags["BAMBU_ACCOUNT_TOKEN_EXPIRED"] ?: false) && (isTokenExpired || debugForceTokenExpired)

                    if (isTokenTourActive) {
                        TourOverlay(
                            steps = tokenExpiredSteps,
                            currentStepIndex = currentTourStep,
                            targets = tourTargets,
                            lazyListState = bambuLazyListState,
                            onNext = {
                                if (currentTourStep < tokenExpiredSteps.size - 1) {
                                    currentTourStep++
                                } else {
                                    scope.launch {
                                        userPrefs.updateTourFlag("BAMBU_ACCOUNT_TOKEN_EXPIRED", false)
                                        currentTourStep = 0
                                    }
                                }
                            }
                        )
                    } else {
                        val steps = if (authData == null) bambuAccountStepsBeforeLogin else bambuAccountStepsAfterLogin
                        val tourKey = if (authData == null) "BAMBU_ACCOUNT_BEFORE_LOGIN" else "BAMBU_ACCOUNT_AFTER_LOGIN"
                        val isPageTourActive = (tourFlags[tourKey] ?: false)
                        val isCardTourActive = (tourFlags["BAMBU_ACCOUNT_CARD"] ?: false)

                        if (isPageTourActive) {
                            TourOverlay(
                                steps = steps,
                                currentStepIndex = currentTourStep,
                                targets = tourTargets,
                                lazyListState = bambuLazyListState,
                                onNext = {
                                    if (currentTourStep < steps.size - 1) {
                                        currentTourStep++
                                    } else {
                                        scope.launch {
                                            userPrefs.updateTourFlag(tourKey, false)
                                            currentTourStep = 0
                                        }
                                    }
                                }
                            )
                        } else if (isCardTourActive && bambuAccountStepsCard.isNotEmpty()) {
                            TourOverlay(
                                steps = bambuAccountStepsCard,
                                currentStepIndex = currentTourStep,
                                targets = tourTargets,
                                lazyListState = bambuLazyListState,
                                onNext = {
                                    if (currentTourStep < bambuAccountStepsCard.size - 1) {
                                        currentTourStep++
                                    } else {
                                        scope.launch { 
                                            userPrefs.updateTourFlag("BAMBU_ACCOUNT_CARD", false)
                                            currentTourStep = 0
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Defines the main navigation destinations available in the bottom/side navigation suite.
 */
enum class AppDestinations(
    val label: String,
    val icon: ImageVector? = null,
    val iconResId: Int? = null,
    val showInNavBar: Boolean = true
) {
    BAMBU_ACCOUNT("Bambu Acc.", icon = Icons.Default.AccountBox),
    INVENTORY("Inventory", icon = Icons.Default.Inventory),
    AVAILABILITY("Availability", icon = Icons.Default.TrackChanges),
    NAPPS("NApps", iconResId = R.drawable.napps_logo)
}
