package com.napps.filamentmanager

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.napps.filamentmanager.database.UserPreferencesRepository
import com.napps.filamentmanager.util.tourTarget
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.core.net.toUri

/**
 * The main screen for the NApps section of the application.
 * Displays information about the app, versioning, and links to the GitHub repository.
 *
 * @param userPrefs Repository for accessing and managing user preferences.
 * @param tourTargets A map of UI element coordinates used for the feature tour overlay.
 * @param onSettingsClick Callback triggered when the settings icon is clicked.
 * @param scrollState The scroll state for the vertical column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NAppsScreen(
    userPrefs: UserPreferencesRepository,
    tourTargets: SnapshotStateMap<String, Rect>,
    onSettingsClick: () -> Unit,
    scrollState: ScrollState = rememberScrollState()
) {
    val context = LocalContext.current
    val githubUrl = "https://github.com/NApps-Studio/Filament-Manager"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                actions = {
                    IconButton(
                        onClick = onSettingsClick,
                        // TOUR: Settings icon to access app configuration (Linked to settings in MainActivity)
                        modifier = Modifier.tourTarget("settings", tourTargets)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = R.drawable.napps_logo),
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Thank you for using my app!",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            val packageInfo = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0)
                } catch (_: Exception) {
                    null
                }
            }
            val versionName = packageInfo?.versionName ?: "1.0"

            Text(
                text = "Version $versionName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "This project is open source. You can view the code, report bugs, or support the project's development on GitHub.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Main GitHub Button - Opens the repository in a browser
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, githubUrl.toUri())
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(painter = painterResource(id = R.drawable.github_invertocat_white), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("View on GitHub", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}





