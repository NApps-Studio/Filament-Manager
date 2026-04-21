package com.napps.filamentmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Dialog shown when a user attempts an action that requires a full data sync,
 * but the sync is currently in progress or hasn't started.
 *
 * Provides an option to proceed anyway and optionally ignore future warnings.
 *
 * @param onDismiss Callback when the user chooses to wait.
 * @param onConfirm Callback when the user chooses to proceed, providing the "do not show again" preference.
 * @param isAccountPage Customizes the warning text for the Account management context.
 */
@Composable
fun SyncWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    isAccountPage: Boolean = false
) {
    var ignoreSyncWarning by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFC107))
                Spacer(Modifier.width(8.dp))
                Text("Incomplete Full Sync")
            }
        },
        text = {
            Column {
                if (isAccountPage) {
                    Text(
                        "The full synchronization of the filament catalog has not finished yet. " +
                        "Adding a printer with an AMS now will add filaments to your inventory which will not have synced colors with the store. " +
                        "Any filaments added before full sync completes will have to have their colors chosen again once the sync finishes."
                    )
                } else {
                    Text(
                        "The full synchronization of the filament catalog has not finished yet. " +
                        "Adding or modifying items now may lead to incomplete color mapping or linking issues until the sync completes. " +
                        "Any filaments added before full sync completes will have to have their colors chosen again once the sync finishes."
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "This can take some time, but the sync will continue in the background and you will receive a notification upon its completion."
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = ignoreSyncWarning,
                        onCheckedChange = { ignoreSyncWarning = it }
                    )
                    Text(
                        "Do not show again",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(ignoreSyncWarning) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Add Anyway")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Wait for Sync")
            }
        }
    )
}

/**
 * Dialog shown when a required full data sync has not yet completed.
 * Unlike [SyncWarningDialog], this dialog doesn't provide an "Add Anyway" option
 * as the action strictly requires the sync data to be present.
 *
 * @param onDismiss Callback to close the dialog.
 */
@Composable
fun SyncRequiredDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFC107))
                Spacer(Modifier.width(8.dp))
                Text("Full Sync Required")
            }
        },
        text = {
            Column {
                Text(
                    "The full synchronization of the filament catalog must finish before you can add trackers. " +
                    "This ensures that all filament data and availability status are correctly mapped."
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This can take some time, but the sync will continue in the background and you will receive a notification upon its completion."
                )
            }
        },
        confirmButton = {
            Button(onClick = { onDismiss() }) {
                Text("OK")
            }
        }
    )
}

/**
 * A small yellow warning icon overlay used to indicate that a feature's data
 * is currently incomplete due to an ongoing background sync.
 */
@Composable
fun BoxScope.WarningIconOverlay() {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = (-8).dp, y = 8.dp)
            .size(20.dp)
            .background(Color(0xFFFFC107), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Sync Warning",
            modifier = Modifier.size(14.dp),
            tint = Color.Black
        )
    }
}
