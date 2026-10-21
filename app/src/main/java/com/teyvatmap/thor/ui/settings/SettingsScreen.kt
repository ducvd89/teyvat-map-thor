package com.teyvatmap.thor.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teyvatmap.thor.BuildConfig
import com.teyvatmap.thor.data.prefs.SUPPORTED_LANGUAGES
import com.teyvatmap.thor.data.update.DownloadState
import com.teyvatmap.thor.sync.SyncPhase
import com.teyvatmap.thor.ui.AppViewModel
import com.teyvatmap.thor.ui.ImportState
import com.teyvatmap.thor.ui.GroupHeader
import com.teyvatmap.thor.ui.TIcons
import com.teyvatmap.thor.ui.ScreenPage
import com.teyvatmap.thor.ui.SectionCard
import com.teyvatmap.thor.ui.ToggleRow
import com.teyvatmap.thor.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun formatTime(ms: Long): String =
    if (ms <= 0) "never" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

/** Everything the settings page shows, as plain data (so it can be rendered without a ViewModel). */
data class SettingsUi(
    val settings: com.teyvatmap.thor.data.prefs.Settings,
    val download: DownloadState,
    val import: ImportState,
    val points: Int?,
    val update: com.teyvatmap.thor.data.update.UpdateUiState,
    val sync: com.teyvatmap.thor.sync.SyncUiState,
    val syncMessage: String?,
    val cv: com.teyvatmap.thor.tracking.CvIndexUiState,
    val cvBytes: Long,
    val dataMessage: String?,
    val backupFiles: List<String>,
    val packBytes: Long,
    val packPath: String,
    val canMoveToOtherScreen: Boolean,
    val overlay: com.teyvatmap.thor.dualscreen.OverlayControls? = null,
    val install: com.teyvatmap.thor.data.update.InstallUiState = com.teyvatmap.thor.data.update.InstallUiState(),
)

class SettingsActions(
    val setAutoStartOverlay: (Boolean) -> Unit = {},
    val setAutoMove: (Boolean) -> Unit = {},
    val setNonFocusable: (Boolean) -> Unit = {},
    val setKeepScreenOn: (Boolean) -> Unit = {},
    val onMoveToOtherScreen: () -> Unit = {},
    val setLanguage: (String) -> Unit = {},
    val setGameLanguage: (String) -> Unit = {},
    val startDownload: (Boolean, Boolean) -> Unit = { _, _ -> },
    val cancelDownload: () -> Unit = {},
    val importPack: () -> Unit = {},
    val installZip: () -> Unit = {},
    val cancelInstall: () -> Unit = {},
    val updateNow: () -> Unit = {},
    val cancelUpdate: () -> Unit = {},
    val rebuildCvIndex: () -> Unit = {},
    val signIn: () -> Unit = {},
    val syncNow: () -> Unit = {},
    val signOut: () -> Unit = {},
    val backupNow: () -> Unit = {},
    val exportJson: () -> Unit = {},
    val importJson: () -> Unit = {},
    val onOpenRespawn: () -> Unit = {},
    val onOpenDiagnostics: () -> Unit = {},
    val onBack: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    canMoveToOtherScreen: Boolean,
    onMoveToOtherScreen: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenRespawn: () -> Unit,
    onBack: () -> Unit,
    overlay: com.teyvatmap.thor.dualscreen.OverlayControls? = null,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val download by vm.downloadState.collectAsStateWithLifecycle()
    val import by vm.importState.collectAsStateWithLifecycle()
    val points by vm.pointCount.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    val syncMessage by vm.syncMessage.collectAsStateWithLifecycle()
    val cv by vm.cvIndexState.collectAsStateWithLifecycle()
    val cvBytes by vm.cvIndexBytes.collectAsStateWithLifecycle()
    LaunchedEffect(cv.running) { vm.refreshCvIndexSize() }
    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onSignInResult(r.data)
    }
    val dataMessage by vm.dataMessage.collectAsStateWithLifecycle()
    val backupFiles by vm.backupFiles.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri)
    }
    val install by vm.installState.collectAsStateWithLifecycle()
    val pickZip = rememberZipPicker { vm.installZip(it) }
    var packBytes by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(download, import) {
        packBytes = withContext(Dispatchers.IO) { vm.paths.root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }
    SettingsContent(
        SettingsUi(
            settings, download, import, points, update, sync, syncMessage, cv, cvBytes, dataMessage, backupFiles,
            packBytes, vm.paths.root.absolutePath, canMoveToOtherScreen, overlay, install,
        ),
        SettingsActions(
            setAutoStartOverlay = vm::setAutoStartOverlay, setAutoMove = vm::setAutoMove, setNonFocusable = vm::setNonFocusable,
            setKeepScreenOn = vm::setKeepScreenOn, onMoveToOtherScreen = onMoveToOtherScreen,
            setLanguage = { vm.setLanguage(it) }, setGameLanguage = { vm.setGameLanguage(it) },
            startDownload = vm::startDownload, cancelDownload = vm::cancelDownload, importPack = vm::importPack,
            installZip = pickZip, cancelInstall = vm::cancelInstall,
            updateNow = vm::updateNow, cancelUpdate = vm::cancelUpdate, rebuildCvIndex = vm::rebuildCvIndex,
            signIn = { vm.signIn { pi -> signInLauncher.launch(IntentSenderRequest.Builder(pi).build()) } },
            syncNow = vm::syncNow, signOut = vm::signOut, backupNow = vm::backupNow,
            exportJson = { exportLauncher.launch("teyvat-marks.json") },
            importJson = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            onOpenRespawn = onOpenRespawn, onOpenDiagnostics = onOpenDiagnostics, onBack = onBack,
        ),
    )
}

@Composable
fun SettingsContent(ui: SettingsUi, act: SettingsActions) {
    val settings = ui.settings
    val download = ui.download
    val import = ui.import
    val points = ui.points
    val update = ui.update
    val sync = ui.sync
    val syncMessage = ui.syncMessage
    val cv = ui.cv
    val cvBytes = ui.cvBytes
    val dataMessage = ui.dataMessage
    val backupFiles = ui.backupFiles
    val packBytes = ui.packBytes
    val overlay = ui.overlay
    val canMoveToOtherScreen = ui.canMoveToOtherScreen
    val onBack = act.onBack
    var langOpen by remember { mutableStateOf(false) }
    var gameLangOpen by remember { mutableStateOf(false) }
    var withDetails by remember(settings.downloadDetails) { mutableStateOf(settings.downloadDetails) }
    var withMedia by remember(settings.downloadMedia) { mutableStateOf(settings.downloadMedia) }

    ScreenPage("Settings", onBack) {
        GroupHeader("Screen and controls", TIcons.GameMode)
        SectionCard("") {
            if (overlay != null) {
                Button(onClick = overlay.start, enabled = overlay.available, modifier = Modifier.fillMaxWidth()) {
                    Text("Start on bottom screen")
                }
                Text(
                    when {
                        !overlay.available -> "No second screen found."
                        !overlay.granted -> "Needs the \"Display over other apps\" permission (you will be asked)."
                        else -> "Shows the map as an overlay on the bottom screen; the controller stays with the game."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ToggleRow(
                    "Start on the bottom screen at launch",
                    "Opening the app goes straight to the overlay (use \"Open app\" on the overlay to get back here).",
                    settings.autoStartOverlay, act.setAutoStartOverlay,
                )
            }
            ToggleRow(
                "Open on the second screen at launch",
                "If started on the game screen and another display exists, relaunch there.",
                settings.autoMoveToSecondary, act.setAutoMove,
            )
            ToggleRow(
                "Keep controller input with the game",
                "Makes the map window (overlay, or this window when the overlay permission is missing) non-focusable: touch works, gamepad/keyboard stay with the game. Lifted only while typing.",
                settings.nonFocusable, act.setNonFocusable,
            )
            ToggleRow("Keep screen on", null, settings.keepScreenOn, act.setKeepScreenOn)
            OutlinedButton(onClick = act.onMoveToOtherScreen, enabled = canMoveToOtherScreen) { Text("Move to other screen") }
        }
        GroupHeader("Language", TIcons.MapSwitch)
        SectionCard("") {
            Text("Category names", style = MaterialTheme.typography.titleSmall)
            Text(
                "Used for category names, regions and descriptions (API lang=). Switching re-fetches text on the next download.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val current = SUPPORTED_LANGUAGES.firstOrNull { it.first == settings.language }
            OutlinedButton(onClick = { langOpen = !langOpen }) { Text((current?.second ?: settings.language) + "  (${settings.language})") }
            if (langOpen) {
                Column {
                    SUPPORTED_LANGUAGES.forEach { (code, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { act.setLanguage(code); langOpen = false },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = code == settings.language, onClick = { act.setLanguage(code); langOpen = false })
                            Text("$label  ($code)")
                        }
                    }
                }
            }
            Text("Game UI language", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                "The language of the game's own UI. The area-name banner is read from the top screen by on-device OCR and matched to region names in this language (default Tiếng Việt).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val gcur = SUPPORTED_LANGUAGES.firstOrNull { it.first == settings.gameLanguage }
            OutlinedButton(onClick = { gameLangOpen = !gameLangOpen }) { Text((gcur?.second ?: settings.gameLanguage) + "  (${settings.gameLanguage})") }
            if (gameLangOpen) {
                Column {
                    SUPPORTED_LANGUAGES.forEach { (code, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { act.setGameLanguage(code); gameLangOpen = false },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = code == settings.gameLanguage, onClick = { act.setGameLanguage(code); gameLangOpen = false })
                            Text("$label  ($code)")
                        }
                    }
                }
            }
        }
        GroupHeader("Map data", TIcons.Download)
        SectionCard("Offline pack") {
            Text(
                "${points ?: 0} points imported. Pack folder: ${if (packBytes >= 0) formatBytes(packBytes) else "…"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            SelectionContainer { Text(ui.packPath, style = MaterialTheme.typography.bodySmall) }
            (download as? DownloadState.Running)?.let { d ->
                Text(d.stage + if (d.total > 0) "  ${d.done} / ${d.total}" else "")
                if (d.total > 0) LinearProgressIndicator(progress = { d.fraction }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = act.cancelDownload) { Text("Cancel download") }
            }
            (download as? DownloadState.Failed)?.let { Text("Last download failed: ${it.message}", color = MaterialTheme.colorScheme.error) }
            (import as? ImportState.Running)?.let {
                Text("Importing: ${it.progress.stage}")
                LinearProgressIndicator(progress = { it.progress.fraction }, Modifier.fillMaxWidth())
            }
            (import as? ImportState.Done)?.let { Text("Imported ${it.points} points in ${"%.1f".format(it.millis / 1000.0)} s.") }
            (import as? ImportState.Failed)?.let { Text("Import failed: ${it.message}", color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = withDetails, onCheckedChange = { withDetails = it })
                Text("Download descriptions (Wi-Fi + charging)", modifier = Modifier.padding(start = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = withMedia, onCheckedChange = { withMedia = it })
                Text("Download images (1920 px WebP, ~3 GB, Wi-Fi + charging)", modifier = Modifier.padding(start = 4.dp))
            }
            val busy = download is DownloadState.Running || import is ImportState.Running
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { act.startDownload(withDetails, withMedia) }, enabled = !busy) { Text("Download / resume") }
                OutlinedButton(onClick = act.importPack, enabled = !busy) { Text("Re-import pack") }
            }
            InstallZipPanel(ui.install, onPick = act.installZip, onCancel = act.cancelInstall, enabled = !busy)
        }

        SectionCard("Map data updates") {
            Text(
                "Checked automatically once a day, and at start when the last check is older than 12 h. Only changes are " +
                    "downloaded; your marks are never touched. Descriptions of new points are fetched if \"Download descriptions\" is on.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Last checked: ${formatTime(update.lastChecked)}", style = MaterialTheme.typography.bodyMedium)
            Text("Last updated: ${formatTime(update.lastUpdated)}", style = MaterialTheme.typography.bodyMedium)
            update.lastSummary?.let { Text("Last result: $it", style = MaterialTheme.typography.bodyMedium) }
            update.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (update.running || update.waiting) {
                Text(update.stage + if (update.total > 0) "  ${update.done} / ${update.total}" else "")
                if (update.total > 0) LinearProgressIndicator(progress = { update.fraction }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = act.updateNow, enabled = !(update.running || update.waiting)) { Text("Update now") }
                if (update.running || update.waiting) OutlinedButton(onClick = act.cancelUpdate) { Text("Cancel") }
            }
        }

        SectionCard("Position matching data") {
            Text(
                "Reference images the live position tracker matches the minimap against (preprocessed map levels, cave " +
                    "floors, entrances, teleport targets). Rebuilt automatically after an import or a map update.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("On disk: ${formatBytes(cvBytes)}", style = MaterialTheme.typography.bodyMedium)
            if (cv.running || cv.waiting) {
                Text(if (cv.running) cv.stage else "Waiting to start")
                if (cv.total > 0) LinearProgressIndicator(progress = { cv.fraction }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            cv.error?.let { Text("Last build failed: $it", color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = act.rebuildCvIndex, enabled = !(cv.running || cv.waiting)) { Text("Rebuild matching data") }
        }
        GroupHeader("Sync and backup", TIcons.SyncOk)

        SectionCard("Google Drive sync") {
            Text(
                "Marks, notes, pins and respawn rules sync through a hidden file in your own Google Drive (app data " +
                    "folder, nothing else is accessed). Merging is per item, newest change wins.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when (sync.phase) {
                    SyncPhase.SIGNED_OUT -> "Signed out"
                    SyncPhase.SYNCING -> "Syncing\u2026"
                    SyncPhase.IDLE -> "Last synced: ${formatTime(sync.lastSynced)}"
                    SyncPhase.ERROR -> "Sync error (will retry): ${sync.error}"
                    SyncPhase.NEEDS_SIGN_IN -> "Sign-in needed again: ${sync.error ?: ""}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (sync.phase == SyncPhase.ERROR || sync.phase == SyncPhase.NEEDS_SIGN_IN) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            sync.account?.let { Text("Account: $it", style = MaterialTheme.typography.bodyMedium) }
            syncMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!sync.enabled || sync.phase == SyncPhase.NEEDS_SIGN_IN) {
                    Button(onClick = act.signIn) { Text("Sign in with Google") }
                }
                if (sync.enabled) {
                    Button(onClick = act.syncNow, enabled = sync.phase != SyncPhase.SYNCING) { Text("Sync now") }
                    OutlinedButton(onClick = act.signOut) { Text("Sign out") }
                }
            }
        }

        SectionCard("Your data") {
            Text(
                "Marks, notes, custom pins and respawn rules. A snapshot is written once a day (the last 14 are kept in " +
                    "Android/data/<pkg>/files/backups). Import merges: newer changes always win.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (backupFiles.isEmpty()) "No local backups yet." else "${backupFiles.size} backups, latest ${backupFiles.first()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            dataMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = act.backupNow) { Text("Back up now") }
                OutlinedButton(onClick = { act.exportJson() }) { Text("Export JSON") }
                OutlinedButton(onClick = { act.importJson() }) { Text("Import JSON") }
            }
            OutlinedButton(onClick = act.onOpenRespawn) { Text("Respawn timers") }
        }
        GroupHeader("Advanced", TIcons.Diagnostics)
        SectionCard("") {
            Text(
                "Displays, MediaProjection capture test and gamepad/focus event log (Phase 0.1).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = act.onOpenDiagnostics) { Text("Open diagnostics") }
        }
        Text(
            "Teyvat Second Screen ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
