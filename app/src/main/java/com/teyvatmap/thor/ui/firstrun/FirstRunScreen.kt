package com.teyvatmap.thor.ui.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teyvatmap.thor.data.update.DownloadState
import com.teyvatmap.thor.ui.AppViewModel
import com.teyvatmap.thor.ui.ImportState
import com.teyvatmap.thor.ui.ScreenPage
import com.teyvatmap.thor.ui.SectionCard

/** Shown while no map data exists (or while the blocking part of the first download/import is running). */
@Composable
fun FirstRunScreen(vm: AppViewModel, onOpenDiagnostics: () -> Unit) {
    val download by vm.downloadState.collectAsStateWithLifecycle()
    val import by vm.importState.collectAsStateWithLifecycle()
    val packOnDisk by vm.packOnDisk.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var withDetails by remember { mutableStateOf(settings.downloadDetails) }
    var withMedia by remember { mutableStateOf(settings.downloadMedia) }
    val install by vm.installState.collectAsStateWithLifecycle()
    val pickZip = com.teyvatmap.thor.ui.settings.rememberZipPicker { vm.installZip(it) }
    val busy = download is DownloadState.Running || import is ImportState.Running

    ScreenPage(title = "Teyvat Second Screen", onBack = null) {
        Text(
            "No map data on this device yet. Everything is stored locally, so the map works in airplane mode afterwards.",
            style = MaterialTheme.typography.bodyLarge,
        )

        (download as? DownloadState.Running)?.let { d ->
            SectionCard("Downloading") {
                Text(d.stage + if (d.total > 0) "  ${d.done} / ${d.total}" else "")
                if (d.total > 0) LinearProgressIndicator(progress = { d.fraction }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = vm::cancelDownload) { Text("Cancel (progress is kept)") }
            }
        }
        (import as? ImportState.Running)?.let { r ->
            SectionCard("Importing") {
                Text(r.progress.stage)
                LinearProgressIndicator(progress = { r.progress.fraction }, Modifier.fillMaxWidth())
            }
        }
        (download as? DownloadState.Failed)?.let { Text("Download failed: ${it.message}", color = MaterialTheme.colorScheme.error) }
        (import as? ImportState.Failed)?.let { Text("Import failed: ${it.message}", color = MaterialTheme.colorScheme.error) }

        SectionCard("Download all map data") {
            Text(
                "Fetches the 4 maps (Teyvat, Enkanomiya, The Chasm, Ancient Sacred Mountain): tiles, category icons, " +
                    "points, regions and underground layers directly from HoYoLAB. Resumable, runs as a background " +
                    "service. Wi-Fi recommended (hundreds of MB).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = withDetails, onCheckedChange = { withDetails = it }, enabled = !busy)
                Text(
                    "Also download point descriptions (~50 MB, ~5 requests/s, waits for Wi-Fi + charging)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = withMedia, onCheckedChange = { withMedia = it }, enabled = !busy)
                Text(
                    "Also download point images (downscaled to 1920 px WebP, ~3 GB, waits for Wi-Fi + charging)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Button(onClick = { vm.startDownload(withDetails, withMedia) }, enabled = !busy) { Text("Download all map data") }
        }

        SectionCard("Import pack from storage") {
            if (packOnDisk) {
                Text("A pack was found in the app folder:", style = MaterialTheme.typography.bodyMedium)
                SelectionContainer { Text(vm.paths.root.absolutePath, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = vm::importPack, enabled = !busy) { Text("Import pack") }
            } else {
                Text(
                    "Build a pack on a PC with tools/fetch_pack.py and copy it to the device:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer {
                    Text(
                        "cd data/pack && find . -type d -exec adb shell mkdir -p ${vm.paths.root.absolutePath}/{} \\;\n" +
                            "for e in *; do adb push \"\$e\" ${vm.paths.root.absolutePath}/; done",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "(On Android 13 adb push fails with \"secure_mkdirs failed\" unless the sub-folders exist first.)",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = vm::refreshPackOnDisk, enabled = !busy) { Text("Scan folder again") }
            }
        }

        SectionCard("Install maps from zip") {
            com.teyvatmap.thor.ui.settings.InstallZipPanel(install, onPick = pickZip, onCancel = vm::cancelInstall, enabled = !busy)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenDiagnostics) { Text("Diagnostics") }
        }
    }
}
