package com.teyvatmap.thor.ui.diagnostics

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teyvatmap.thor.dualscreen.DisplayRouter
import com.teyvatmap.thor.tracking.CaptureHub
import com.teyvatmap.thor.tracking.CaptureService
import com.teyvatmap.thor.ui.AppViewModel
import com.teyvatmap.thor.ui.InputEventLog
import com.teyvatmap.thor.ui.ScreenPage
import com.teyvatmap.thor.ui.SectionCard
import com.teyvatmap.thor.ui.ToggleRow

/** Phase 0.1 feasibility tests: displays, MediaProjection capture of display 0, and input focus behaviour. */
@Composable
fun DiagnosticsScreen(vm: AppViewModel, onMoveToOtherScreen: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by CaptureHub.status.collectAsStateWithLifecycle()
    val thumb by CaptureHub.thumbnail.collectAsStateWithLifecycle()
    val events by InputEventLog.events.collectAsStateWithLifecycle()
    val windowFocus by InputEventLog.windowFocus.collectAsStateWithLifecycle()

    var displayTick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { displayTick++ }
            override fun onDisplayRemoved(displayId: Int) { displayTick++ }
            override fun onDisplayChanged(displayId: Int) { displayTick++ }
        }
        dm.registerDisplayListener(l, null)
        onDispose { dm.unregisterDisplayListener(l) }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) CaptureService.start(context, r.resultCode, data)
    }
    fun requestConsent() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        consent.launch(mpm.createScreenCaptureIntent())
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestConsent() }

    ScreenPage("Diagnostics", onBack) {
        SectionCard("Displays") {
            val currentId = activity?.let { DisplayRouter.currentDisplayId(it) }
            val tick = displayTick
            remember(tick) { DisplayRouter.displays(context) }.forEach { d -> Text(describe(d, currentId), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            Text(
                "This window is on display ${currentId ?: "?"}. Window focus: $windowFocus",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onMoveToOtherScreen) { Text("Move to other screen") }
        }

        SectionCard("MediaProjection capture test") {
            Text(
                "Captures the default display (${status.width}x${status.height} when running) every 2 s and saves a PNG to " +
                    "Android/data/<pkg>/files/captures/. Note which screen the consent dialog appears on.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) requestConsent() else notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    enabled = !status.running,
                ) { Text("Start capture test") }
                OutlinedButton(onClick = { CaptureService.stop(context) }, enabled = status.running) { Text("Stop") }
            }
            Text(
                "running=${status.running}  frames seen=${status.framesSeen}  PNGs saved=${status.framesSaved}\n" +
                    "last frame black=${status.lastFrameBlack}  black frames=${status.blackFrames}",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
            when (status.lastFrameBlack) {
                true -> Text("Frames look ALL BLACK (secure content / FLAG_SECURE, or nothing drawn).", color = MaterialTheme.colorScheme.error)
                false -> Text("Frames contain image data.", color = MaterialTheme.colorScheme.tertiary)
                null -> Unit
            }
            status.lastSavedPath?.let { Text("Last file: $it", style = MaterialTheme.typography.bodySmall) }
            status.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
            thumb?.let { Image(it.asImageBitmap(), contentDescription = "Latest frame", modifier = Modifier.fillMaxWidth()) }
        }

        SectionCard("Input focus / key event log") {
            Text(
                "Shows KeyEvents / MotionEvents this window receives. With the window non-focusable, gamepad buttons " +
                    "should NOT appear here while Genshin is focused on the other screen.",
                style = MaterialTheme.typography.bodySmall,
            )
            ToggleRow(
                "FLAG_NOT_FOCUSABLE", "Controller and keyboard stay with the other window.",
                settings.nonFocusable, vm::setNonFocusable,
            )
            Text("Window focus: $windowFocus", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            OutlinedButton(onClick = InputEventLog::clear) { Text("Clear log") }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 280.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp)).padding(8.dp),
            ) {
                items(events) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun describe(d: Display, currentId: Int?): String {
    val m = DisplayMetrics()
    d.getRealMetrics(m)
    val flags = buildList {
        if (d.flags and Display.FLAG_SECURE != 0) add("SECURE")
        if (d.flags and Display.FLAG_SUPPORTS_PROTECTED_BUFFERS != 0) add("PROTECTED_BUFFERS")
        if (d.flags and Display.FLAG_PRIVATE != 0) add("PRIVATE")
        if (d.flags and Display.FLAG_PRESENTATION != 0) add("PRESENTATION")
        if (d.flags and Display.FLAG_ROUND != 0) add("ROUND")
    }.joinToString("|").ifEmpty { "-" }
    val state = when (d.state) {
        Display.STATE_ON -> "ON"; Display.STATE_OFF -> "OFF"; Display.STATE_DOZE -> "DOZE"; else -> d.state.toString()
    }
    return "id=${d.displayId}${if (d.displayId == Display.DEFAULT_DISPLAY) " (default)" else ""}" +
        "${if (d.displayId == currentId) " <- this window" else ""}\n" +
        "  name=\"${d.name}\"  ${m.widthPixels}x${m.heightPixels}  ${m.densityDpi}dpi  ${"%.0f".format(d.refreshRate)}Hz  state=$state\n" +
        "  flags=0x${d.flags.toString(16)} [$flags]"
}

