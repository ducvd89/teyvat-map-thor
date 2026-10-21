package com.teyvatmap.thor.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.teyvatmap.thor.data.update.InstallUiState

/**
 * Opens the system file picker (ACTION_OPEN_DOCUMENT, application/zip) and returns a launcher for it. It must live in
 * the Activity's UI: the bottom-screen overlay window cannot host pickers.
 */
@Composable
fun rememberZipPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onPicked(uri)
        }
    }
    return { launcher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
}

/** The "Install maps from zip" button with its progress and last result. */
@Composable
fun InstallZipPanel(state: InstallUiState, onPick: () -> Unit, onCancel: () -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Install maps from a zip made on a PC (the pack folder, zipped). New maps are added, existing ones updated; " +
                "your marks stay.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.running) {
            Text(state.stage + if (state.total > 0) "  ${state.done} / ${state.total}" else "")
            if (state.total > 0) LinearProgressIndicator(progress = { state.fraction }, Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.summary?.takeIf { !state.running }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.error?.takeIf { !state.running }?.let { Text("Install failed: $it", color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPick, enabled = enabled && !state.running) { Text("Install maps from zip") }
            if (state.running) OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
