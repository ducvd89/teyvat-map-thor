package com.teyvatmap.thor.ui.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teyvatmap.thor.ui.AppViewModel
import com.teyvatmap.thor.ui.map.MapScreen
import com.teyvatmap.thor.ui.map.MapViewModel
import com.teyvatmap.thor.ui.progress.ProgressScreen

/**
 * The map UI as hosted by the bottom-screen overlay window (game mode). Same [MapScreen] as the Activity; the pages
 * that need Activity features (settings, calibration, diagnostics, import) hand over to the app via [onOpenApp].
 */
@Composable
fun OverlayApp(
    appVm: AppViewModel,
    mapVm: MapViewModel,
    onOpenApp: (screen: String?) -> Unit,
    onStartCapture: () -> Unit,
) {
    val count by appVm.pointCount.collectAsStateWithLifecycle()
    var progress by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            count == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            (count ?: 0) == 0 -> Column(
                Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("No map data yet. Open the app to download or import it.")
                Button(onClick = { onOpenApp(null) }) { Text("Open app") }
            }
            else -> MapScreen(
                appVm = appVm,
                canMoveToOtherScreen = false,
                onOpenSettings = { onOpenApp("SETTINGS") },
                onOpenDiagnostics = { onOpenApp("DIAGNOSTICS") },
                onOpenProgress = { progress = true },
                onMoveToOtherScreen = {},
                onOpenCalibration = { onOpenApp("CALIBRATION") },
                onOpenDebug = { onOpenApp("DEBUG") },
                startCapture = onStartCapture,
                onOpenApp = { onOpenApp(null) },
                vm = mapVm,
            )
        }
        if (progress && (count ?: 0) > 0) ProgressScreen(onBack = { progress = false }, onPick = { progress = false }, vm = mapVm)
    }
    BackHandler(enabled = progress) { progress = false }
}
