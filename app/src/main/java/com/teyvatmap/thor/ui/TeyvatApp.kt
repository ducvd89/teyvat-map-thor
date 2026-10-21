package com.teyvatmap.thor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import com.teyvatmap.thor.dualscreen.OverlayAccess
import com.teyvatmap.thor.dualscreen.OverlayControls
import com.teyvatmap.thor.ui.tracking.rememberCaptureStarter
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teyvatmap.thor.data.update.DownloadState
import com.teyvatmap.thor.ui.diagnostics.DiagnosticsScreen
import com.teyvatmap.thor.ui.firstrun.FirstRunScreen
import com.teyvatmap.thor.ui.map.MapScreen
import com.teyvatmap.thor.ui.progress.ProgressScreen
import com.teyvatmap.thor.ui.settings.RespawnScreen
import com.teyvatmap.thor.ui.settings.SettingsScreen

private enum class Screen { MAP, SETTINGS, DIAGNOSTICS, PROGRESS, RESPAWN, CALIBRATION, DEBUG }

@Composable
fun TeyvatApp(
    vm: AppViewModel,
    canMoveToOtherScreen: () -> Boolean,
    onMoveToOtherScreen: () -> Boolean,
    overlay: OverlayControls,
    initialScreen: String? = null,
) {
    val count by vm.pointCount.collectAsStateWithLifecycle()
    val download by vm.downloadState.collectAsStateWithLifecycle()
    val importing by vm.importState.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(initialScreen?.let { s -> Screen.entries.firstOrNull { it.name == s } } ?: Screen.MAP) }
    val startCapture = rememberCaptureStarter(vm.tracking)
    var explainPermission by rememberSaveable { mutableStateOf(false) }
    // Without the overlay permission "Start on bottom screen" explains it first (in-window, no Dialog) and then opens its settings page.
    val ov = overlay.copy(start = { if (overlay.granted) overlay.start() else explainPermission = true })

    val blocking = (download as? DownloadState.Running)?.blocking == true
    // Once the map has been shown it stays up during a re-import (progress is shown in Settings instead).
    var mapShown by rememberSaveable { mutableStateOf(false) }
    val ready = (count ?: 0) > 0 && !blocking && (importing !is ImportState.Running || mapShown)
    if (ready) mapShown = true
    val canMove = canMoveToOtherScreen()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            count == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            ready -> MapScreen(
                appVm = vm,
                canMoveToOtherScreen = canMove,
                startCapture = startCapture,
                overlay = ov,
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
                onOpenProgress = { screen = Screen.PROGRESS },
                onMoveToOtherScreen = { onMoveToOtherScreen() },
                onOpenCalibration = { screen = Screen.CALIBRATION },
                onOpenDebug = { screen = Screen.DEBUG },
            )
            else -> FirstRunScreen(vm, onOpenDiagnostics = { screen = Screen.DIAGNOSTICS })
        }
        when (screen) {
            Screen.DIAGNOSTICS -> DiagnosticsScreen(vm, onMoveToOtherScreen = { onMoveToOtherScreen() }, onBack = { screen = Screen.MAP })
            Screen.SETTINGS -> if (ready) {
                SettingsScreen(
                    vm, canMove, onMoveToOtherScreen = { onMoveToOtherScreen() }, overlay = ov,
                    onOpenDiagnostics = { screen = Screen.DIAGNOSTICS }, onOpenRespawn = { screen = Screen.RESPAWN },
                    onBack = { screen = Screen.MAP },
                )
            }
            Screen.PROGRESS -> if (ready) ProgressScreen(onBack = { screen = Screen.MAP }, onPick = { screen = Screen.MAP })
            Screen.RESPAWN -> if (ready) RespawnScreen(onBack = { screen = Screen.SETTINGS })
            Screen.CALIBRATION -> if (ready) com.teyvatmap.thor.ui.calibration.CalibrationScreen(vm.tracking, onBack = { screen = Screen.MAP })
            Screen.DEBUG -> if (ready) com.teyvatmap.thor.ui.debug.DebugScreen(vm.tracking, onBack = { screen = Screen.MAP })
            Screen.MAP -> Unit
        }
        if (explainPermission) {
            InWindowDialog(onDismiss = { explainPermission = false }, modifier = Modifier.widthIn(max = 640.dp)) {
                Text("Allow the bottom-screen map", style = MaterialTheme.typography.titleLarge)
                Text(OverlayAccess.EXPLANATION, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { explainPermission = false; overlay.requestPermission() }) { Text("Open settings") }
                    TextButton(onClick = { explainPermission = false }) { Text("Not now") }
                }
            }
        }
    }
    BackHandler(enabled = screen != Screen.MAP || explainPermission) {
        if (explainPermission) explainPermission = false else screen = if (screen == Screen.RESPAWN) Screen.SETTINGS else Screen.MAP
    }
}
