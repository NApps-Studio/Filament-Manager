package com.napps.filamentmanager

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.napps.filamentmanager.database.BambuViewModel
import com.napps.filamentmanager.database.UserPreferencesRepository
import com.napps.filamentmanager.webscraper.SyncWorker
import kotlinx.coroutines.launch

/**
 * A comprehensive settings screen for configuring application behavior.
 * Includes options for default startup page, update intervals, automation, and debug tools.
 *
 * @param userPrefs Repository for managing user preference data.
 * @param bambuViewModel ViewModel for printer-related logic, used here to re-evaluate warnings.
 * @param onBack Callback triggered to navigate back from settings.
 * @param scrollState The scroll state for the vertical column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    userPrefs: UserPreferencesRepository,
    bambuViewModel: BambuViewModel? = null,
    onBack: () -> Unit,
    scrollState: ScrollState = rememberScrollState()
) {
    val context = LocalContext.current
    val printerIntervalFlow = userPrefs.printerUpdateIntervalFlow.collectAsState(initial = 15)
    val stockIntervalFlow = userPrefs.stockUpdateIntervalFlow.collectAsState(initial = 30)
    val defaultPageFlow = userPrefs.defaultFirstPageFlow.collectAsState(initial = "AVAILABILITY")
    val autoDetectRunoutFlow = userPrefs.autoDetectRunoutFlow.collectAsState(initial = true)
    val ignoreSyncWarningFlow = userPrefs.ignoreSyncWarningFlow.collectAsState(initial = false)
    val debugModeFlow = userPrefs.debugModeFlow.collectAsState(initial = false)
    val debugForceOosFlow = userPrefs.debugForceOosFlow.collectAsState(initial = false)
    val debugForceUnmappedFlow = userPrefs.debugForceUnmappedFlow.collectAsState(initial = false)
    val debugForceTokenExpiredFlow = userPrefs.debugForceTokenExpiredFlow.collectAsState(initial = false)

    val savedPrinterInterval by printerIntervalFlow
    val savedStockInterval by stockIntervalFlow
    val savedDefaultPage by defaultPageFlow
    val savedAutoDetectRunout by autoDetectRunoutFlow
    val savedIgnoreSyncWarning by ignoreSyncWarningFlow
    val savedDebugMode by debugModeFlow
    val savedDebugForceOos by debugForceOosFlow
    val savedDebugForceUnmapped by debugForceUnmappedFlow
    val savedDebugForceTokenExpired by debugForceTokenExpiredFlow
    
    val scope = rememberCoroutineScope()

    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)

    BackHandler(onBack = onBack)

    val primaryColor = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
        ) {
            // 1. General Settings
            Text("General", style = MaterialTheme.typography.titleSmall, color = primaryColor)
            
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.padding(top = 4.dp)) {
                ListItem(
                    headlineContent = { Text("Default Startup Page") },
                    supportingContent = { 
                        val selectedDest = AppDestinations.entries.find { it.name == savedDefaultPage } ?: AppDestinations.AVAILABILITY
                        Text(selectedDest.label) 
                    },
                    leadingContent = {
                        val selectedDest = AppDestinations.entries.find { it.name == savedDefaultPage } ?: AppDestinations.AVAILABILITY
                        if (selectedDest.icon != null) Icon(selectedDest.icon, null)
                        else if (selectedDest.iconResId != null) Icon(painterResource(selectedDest.iconResId), null, modifier = Modifier.size(24.dp))
                    },
                    trailingContent = { Icon(Icons.Default.ArrowDropDown, null) },
                    modifier = Modifier.clickable { expanded = true }
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AppDestinations.entries.forEach { dest ->
                        DropdownMenuItem(
                            text = { Text(dest.label) },
                            onClick = {
                                scope.launch { userPrefs.saveDefaultFirstPage(dest.name) }
                                expanded = false
                            },
                            leadingIcon = {
                                if (dest.icon != null) Icon(dest.icon, null)
                                else if (dest.iconResId != null) Icon(painterResource(dest.iconResId), null, modifier = Modifier.size(24.dp))
                            }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 2. Update Intervals
            Row(verticalAlignment = Alignment.CenterVertically){
                Text("Update Intervals", style = MaterialTheme.typography.titleSmall, color = primaryColor)
                Text("*Effects battery life",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }

            fun formatInterval(minutes: Int): String {
                return if (minutes >= 60) {
                    val hours = minutes / 60
                    val mins = minutes % 60
                    if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
                } else {
                    "$minutes min"
                }
            }

            Column(modifier = Modifier.padding(top = 4.dp).padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Printer Sync", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(formatInterval(savedPrinterInterval), style = MaterialTheme.typography.labelLarge, color = primaryColor)
                }
                Slider(
                    value = savedPrinterInterval.toFloat(),
                    onValueChange = { 
                        val newInterval = (it / 15).toInt() * 15
                        scope.launch { 
                            userPrefs.savePrinterUpdateInterval(newInterval)
                            (context.applicationContext as? FilamentManagerApplication)?.scheduleBambuUpdates(newInterval)
                        }
                    },
                    valueRange = 15f..240f,
                    steps = 14,
                    modifier = Modifier.height(28.dp)
                )

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Stock Monitoring", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(formatInterval(savedStockInterval), style = MaterialTheme.typography.labelLarge, color = primaryColor)
                }
                Slider(
                    value = savedStockInterval.toFloat(),
                    onValueChange = { 
                        val newInterval = (it / 15).toInt() * 15
                        scope.launch {
                            userPrefs.saveStockUpdateInterval(newInterval)
                            SyncWorker.enqueue(context, immediate = false, intervalMinutes = newInterval)
                        }
                    },
                    valueRange = 15f..240f,
                    steps = 14,
                    modifier = Modifier.height(28.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 3. Automation
            Text("Automation", style = MaterialTheme.typography.titleSmall, color = primaryColor)
            ListItem(
                headlineContent = { Text("Auto-Detect Printer Runout") },
                supportingContent = { Text("Mark spools as 'Out of Stock' on printer runout errors.") },
                trailingContent = {
                    Switch(
                        checked = savedAutoDetectRunout,
                        onCheckedChange = { scope.launch { userPrefs.setAutoDetectRunout(it) } },
                        modifier = Modifier.scale(0.8f)
                    )
                }
            )

            val savedThreshold by userPrefs.lowFilamentThresholdGFlow.collectAsState(initial = 25)
            
            Column(modifier = Modifier.padding(vertical = 4.dp).padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Low Filament Threshold", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${savedThreshold}g", style = MaterialTheme.typography.labelLarge, color = primaryColor)
                }
                Slider(
                    value = savedThreshold.toFloat(),
                    onValueChange = { 
                        val newThreshold = (it / 10).toInt() * 10
                        scope.launch { 
                            userPrefs.saveLowFilamentThresholdG(newThreshold)
                            bambuViewModel?.reEvaluateLowStockWarnings()
                        }
                    },
                    valueRange = 0f..250f,
                    steps = 24,
                    modifier = Modifier.height(28.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 4. System Status
            Text("System", style = MaterialTheme.typography.titleSmall, color = primaryColor)
            ListItem(
                headlineContent = { Text("Battery Optimization") },
                supportingContent = { 
                    Text(if (isIgnoringBattery) "Unrestricted (Recommended)" else "Optimized (May delay updates)") 
                },
                trailingContent = {
                    if (!isIgnoringBattery) {
                        TextButton(onClick = {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            context.startActivity(intent)
                        }) {
                            Text("FIX")
                        }
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 5. App Tours
            val tourFlags by userPrefs.tourFlagsFlow.collectAsState(initial = emptyMap())
            var toursExpanded by remember { mutableStateOf(false) }

            Column {
                ListItem(
                    headlineContent = { Text("Feature Tours") },
                    supportingContent = { Text("Manage step-by-step guides.") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { toursExpanded = !toursExpanded }) {
                                Icon(
                                    if (toursExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable { toursExpanded = !toursExpanded }
                )

                if (toursExpanded) {
                    Column(modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
                        val childTours = mapOf(
                            "NAPPS_SCREEN" to listOf("NAPPS_SCREEN" to "NApps Page"),
                            "AVAILABILITY" to listOf(
                                "AVAILABILITY" to "Availability Page",
                                "AVAILABILITY_CARD" to "Tracker Card",
                                "SYNC_REPORTS" to "Sync Reports Guide"
                            ),
                            "INVENTORY" to listOf(
                                "INVENTORY" to "Inventory Page",
                                "INVENTORY_CARD" to "Filament Card",
                                "LOW_STOCK" to "Low Stock Guide",
                                "UNMAPPED" to "Unmapped Colors",
                                "LIMITS" to "Inventory Limits",
                                "LIMITS_CARD" to "Limit Card"
                            ),
                            "BAMBU_ACCOUNT" to listOf(
                                "BAMBU_ACCOUNT_AFTER_LOGIN" to "After Login Guide",
                                "BAMBU_ACCOUNT_BEFORE_LOGIN" to "Before Login Guide",
                                "BAMBU_ACCOUNT_CARD" to "Printer Card",
                                "BAMBU_ACCOUNT_TOKEN_EXPIRED" to "Token Expired Guide"
                            )
                        )

                        val orderedKeys = listOf("NAPPS_SCREEN", "AVAILABILITY", "INVENTORY", "BAMBU_ACCOUNT")

                        orderedKeys.forEach { key ->
                            val children = childTours[key] ?: emptyList()
                            
                            // Parent is ON only if all its children are ON
                            val enabled = children.all { tourFlags[it.first] ?: false }

                            val label = when (key) {
                                "NAPPS_SCREEN" -> "NApps Screen"
                                "AVAILABILITY" -> "Availability Trackers"
                                "INVENTORY" -> "Filament Inventory"
                                "BAMBU_ACCOUNT" -> "Bambu Account"
                                else -> key
                            }

                            var itemExpanded by remember { mutableStateOf(false) }

                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .clickable { 
                                            if (children.isNotEmpty()) itemExpanded = !itemExpanded
                                            else scope.launch { userPrefs.updateTourFlag(key, !enabled) }
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (children.isNotEmpty()) {
                                            Icon(
                                                if (itemExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp).padding(end = 4.dp)
                                            )
                                        }
                                        Text(label, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = { isChecked ->
                                            scope.launch {
                                                children.forEach { (childKey, _) ->
                                                    userPrefs.updateTourFlag(childKey, isChecked)
                                                }
                                            }
                                        },
                                        modifier = Modifier.scale(0.7f)
                                    )
                                }

                                if (itemExpanded) {
                                    children.forEach { (childKey, childLabel) ->
                                        val childEnabled = tourFlags[childKey] ?: false
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 28.dp)
                                                .height(40.dp)
                                                .clickable { scope.launch { userPrefs.updateTourFlag(childKey, !childEnabled) } },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(childLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Switch(
                                                checked = childEnabled,
                                                onCheckedChange = { scope.launch { userPrefs.updateTourFlag(childKey, it) } },
                                                modifier = Modifier.scale(0.6f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }


            }
            ListItem(
                headlineContent = { Text("Ignore Sync Warning") },
                supportingContent = { Text("Do not warn about adding filaments before first sync completes.") },
                trailingContent = {
                    Switch(
                        checked = savedIgnoreSyncWarning,
                        onCheckedChange = { scope.launch { userPrefs.setIgnoreSyncWarning(it) } },
                        modifier = Modifier.scale(0.8f)
                    )
                }
            )

            val showAddToCartTrackers by userPrefs.showAddToCartTrackersFlow.collectAsState(initial = false)
            ListItem(
                headlineContent = { Text("Show 'Add to Cart' on Trackers") },
                supportingContent = { Text("Enable experimental button to add items directly to your Bambu cart. (Work in progress)") },
                trailingContent = {
                    Switch(
                        checked = showAddToCartTrackers,
                        onCheckedChange = { scope.launch { userPrefs.setShowAddToCartTrackers(it) } },
                        modifier = Modifier.scale(0.8f)
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 6. Development Tools
            Text("Development", style = MaterialTheme.typography.titleSmall, color = primaryColor)
            ListItem(
                headlineContent = { Text("Debug Mode") },
                supportingContent = { Text("Enable developer tools and debug warnings.") },
                trailingContent = {
                    Switch(
                        checked = savedDebugMode,
                        onCheckedChange = { scope.launch { userPrefs.setDebugMode(it) } },
                        modifier = Modifier.scale(0.8f)
                    )
                }
            )

            if (savedDebugMode) {
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text("Debug Warnings", style = MaterialTheme.typography.labelMedium, color = primaryColor, modifier = Modifier.padding(top = 8.dp))
                    
                    ListItem(
                        headlineContent = { Text("Force OOS Warning") },
                        trailingContent = {
                            Switch(
                                checked = savedDebugForceOos,
                                onCheckedChange = { scope.launch { userPrefs.setDebugForceOos(it) } },
                                modifier = Modifier.scale(0.7f)
                            )
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Force Unmapped Warning") },
                        trailingContent = {
                            Switch(
                                checked = savedDebugForceUnmapped,
                                onCheckedChange = { scope.launch { userPrefs.setDebugForceUnmapped(it) } },
                                modifier = Modifier.scale(0.7f)
                            )
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Force Token Expired") },
                        trailingContent = {
                            Switch(
                                checked = savedDebugForceTokenExpired,
                                onCheckedChange = { scope.launch { userPrefs.setDebugForceTokenExpired(it) } },
                                modifier = Modifier.scale(0.7f)
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
