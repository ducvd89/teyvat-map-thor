package com.teyvatmap.thor.ui.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teyvatmap.thor.tracking.TrackingManager
import com.teyvatmap.thor.ui.ScreenPage
import com.teyvatmap.thor.ui.SectionCard
import com.teyvatmap.thor.ui.tracking.rememberCaptureStarter
import kotlin.math.hypot

/**
 * Calibration (PLAN §6.5): capture a frame, find the minimap circle, show the whole frame scaled with draggable
 * handles, estimate the scale (optionally with a tapped position hint), save the profile. All in-window UI: no
 * Dialog/Popup windows (they would take the controller focus away from the game).
 */
@Composable
fun CalibrationScreen(manager: TrackingManager, onBack: () -> Unit, vm: CalibrationViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val mapBmp by vm.mapBitmap.collectAsStateWithLifecycle()
    val startCapture = rememberCaptureStarter(manager)
    var pickOnMap by remember { mutableStateOf(false) }
    LaunchedEffect(pickOnMap) { if (pickOnMap) vm.loadMap() }

    ScreenPage("Calibrate minimap", onBack) {
        SectionCard("1. Capture") {
            Text(
                "Stand in the open world (not in a domain, not on the sea) with the game on the top screen and the " +
                    "minimap visible, then capture one frame.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (status.capturing) "Screen capture is running." else "Screen capture is not running.",
                color = if (status.capturing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!status.capturing) Button(onClick = startCapture) { Text("Start capture") }
                Button(onClick = vm::captureFrame, enabled = status.capturing && ui.busy == null) { Text("Capture frame") }
                OutlinedButton(onClick = vm::detectAgain, enabled = ui.frame != null) { Text("Detect circle") }
            }
            saved?.let { Text("Saved profile: ${it.frameW}x${it.frameH}, circle ${"%.0f".format(it.cx)},${"%.0f".format(it.cy)} r=${"%.0f".format(it.r)}, scale ${"%.3f".format(it.s)}", style = MaterialTheme.typography.bodySmall) }
        }

        SectionCard("2. Minimap circle") {
            val frame = ui.frame
            if (frame == null) {
                Text("No frame captured yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                FramePreview(ui, onCircle = vm::setCircle)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NudgeButton("x-") { vm.nudge(-1.0, 0.0, 0.0) }; NudgeButton("x+") { vm.nudge(1.0, 0.0, 0.0) }
                    NudgeButton("y-") { vm.nudge(0.0, -1.0, 0.0) }; NudgeButton("y+") { vm.nudge(0.0, 1.0, 0.0) }
                    NudgeButton("r-") { vm.nudge(0.0, 0.0, -1.0) }; NudgeButton("r+") { vm.nudge(0.0, 0.0, 1.0) }
                }
                Text("center ${"%.1f".format(ui.cx)}, ${"%.1f".format(ui.cy)}   radius ${"%.1f".format(ui.r)}", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("3. Scale") {
            Text(
                "Searches the whole map over scales 0.3 to 1.5. Telling the app roughly where you are makes it faster and safer.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { pickOnMap = !pickOnMap }) { Text(if (pickOnMap) "Hide map" else "Tap my position on the map") }
                if (ui.hint != null) OutlinedButton(onClick = vm::clearHint) { Text("Clear position") }
            }
            if (pickOnMap) MapPicker(mapBmp, ui, vm)
            ui.hint?.let { Text("Position hint set (z0 ${"%.0f".format(it.x)}, ${"%.0f".format(it.y)})", style = MaterialTheme.typography.bodySmall) }
            Button(onClick = vm::estimateScale, enabled = ui.frame != null && ui.busy == null) { Text("Estimate scale") }
            ui.busy?.let { Text(it); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            ui.message?.let {
                Text(it, color = if (ui.score != null && ui.score!! < 0.5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }

        SectionCard("4. Save") {
            Button(onClick = vm::saveProfile, enabled = ui.scale != null && ui.busy == null) { Text("Save profile") }
            OutlinedButton(onClick = vm::useThorDefaults) { Text("Use the Thor 1080p defaults") }
            if (ui.saved) Text("Saved. Tracking uses this profile from now on.", color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun NudgeButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(label) }
}

/** The captured frame scaled to the width, with the circle, a centre handle and a radius handle. */
@Composable
private fun FramePreview(ui: CalibrationUi, onCircle: (Double, Double, Double) -> Unit) {
    val cur = rememberUpdatedState(ui)
    val frame = ui.frame ?: return
    Box(
        Modifier.fillMaxWidth().aspectRatio(ui.frameW.toFloat() / ui.frameH).background(Color.Black, RoundedCornerShape(8.dp)),
    ) {
        Image(frame.asImageBitmap(), contentDescription = "Captured frame", contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize())
        Canvas(
            Modifier.matchParentSize().pointerInput(ui.frameW) {
                var mode = 0 // 1 = move, 2 = resize
                detectDragGestures(
                    onDragStart = { p ->
                        val u = cur.value
                        val k = size.width.toFloat() / u.frameW
                        val d = hypot(p.x - (u.cx * k).toFloat(), p.y - (u.cy * k).toFloat())
                        val rr = (u.r * k).toFloat()
                        mode = if (kotlin.math.abs(d - rr) < 36f) 2 else 1
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        val u = cur.value
                        val k = size.width.toFloat() / u.frameW
                        if (mode == 2) {
                            val d = hypot(change.position.x - (u.cx * k).toFloat(), change.position.y - (u.cy * k).toFloat()) / k
                            onCircle(u.cx, u.cy, d.toDouble())
                        } else {
                            onCircle(u.cx + drag.x / k, u.cy + drag.y / k, u.r)
                        }
                    },
                )
            },
        ) {
            val k = size.width / ui.frameW
            val c = Offset((ui.cx * k).toFloat(), (ui.cy * k).toFloat())
            drawCircle(Color.Cyan, (ui.r * k).toFloat(), c, style = Stroke(2f))
            drawCircle(Color(0x55FFFFFF), (ui.r * k * 0.15).toFloat(), c, style = Stroke(1f))
            drawCircle(Color(0x55FFFFFF), (ui.r * k * 0.88).toFloat(), c, style = Stroke(1f))
            drawCircle(Color.Cyan, 9f, c)
            drawCircle(Color.Yellow, 11f, Offset(c.x + (ui.r * k).toFloat(), c.y))
        }
    }
}


/** Whole-world picture; a tap sets the position hint. */
@Composable
private fun MapPicker(bmp: android.graphics.Bitmap?, ui: CalibrationUi, vm: CalibrationViewModel) {
    if (bmp == null) {
        Text("Loading map ...")
        return
    }
    Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height)) {
        Image(bmp.asImageBitmap(), contentDescription = "Map", contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize())
        Canvas(
            Modifier.matchParentSize().pointerInput(bmp) {
                detectTapGestures { p ->
                    val k = bmp.width.toFloat() / size.width
                    val z = vm.mapPixelToZ0(p.x * k, p.y * k)
                    vm.setHint(z.x, z.y)
                }
            },
        ) {
            ui.hint?.let {
                val (u, v) = vm.z0ToMapPixel(it)
                val k = size.width / bmp.width
                drawCircle(Color.Red, 8f, Offset(u * k, v * k))
                drawCircle(Color.White, 12f, Offset(u * k, v * k), style = Stroke(2f))
            }
            ui.matchPos?.let {
                val (u, v) = vm.z0ToMapPixel(it)
                val k = size.width / bmp.width
                drawCircle(Color.Green, 8f, Offset(u * k, v * k), style = Stroke(3f))
            }
        }
    }
}
