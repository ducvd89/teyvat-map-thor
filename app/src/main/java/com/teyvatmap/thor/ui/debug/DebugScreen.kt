package com.teyvatmap.thor.ui.debug

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teyvatmap.thor.tracking.DebugSnapshot
import com.teyvatmap.thor.tracking.TrackingManager
import com.teyvatmap.thor.tracking.cv.PARAM_FIELDS
import com.teyvatmap.thor.tracking.cv.TrackerParams
import com.teyvatmap.thor.ui.ScreenPage
import com.teyvatmap.thor.ui.SectionCard
import com.teyvatmap.thor.ui.ToggleRow

/**
 * Debug view (PLAN §6.6): the crop, the mask the matcher correlates with and the score map of the latest check,
 * state / scores / per-stage ms / counters, "save frame" to `getExternalFilesDir/dataset/`, and the editable
 * [TrackerParams]. Stepper buttons instead of text fields: the window is non-focusable (no IME).
 */
@Composable
fun DebugScreen(manager: TrackingManager, onBack: () -> Unit) {
    DisposableEffect(manager) {
        manager.debugEnabled = true
        onDispose { manager.debugEnabled = false }
    }
    val snap by manager.debug.collectAsStateWithLifecycle()
    val pose by manager.pose.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    val params by manager.store.params.collectAsStateWithLifecycle()
    var saveMsg by remember { mutableStateOf<String?>(null) }

    ScreenPage("Tracking debug", onBack) {
        SectionCard("State") {
            val s = snap
            Text(
                "capture=${status.capturing} tracking=${status.tracking} paused=${status.paused}" +
                    (status.message?.let { "\n$it" } ?: ""),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
            if (s == null) {
                Text("No check yet (start tracking, then leave this view open).", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stateText(s, pose.xPos, pose.yPos, pose.headingDeg), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        SectionCard("Images") {
            val s = snap
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Labeled("crop", s?.crop)
                Labeled("mask", s?.mask)
                Labeled("score map", s?.heat)
            }
        }
        snap?.let { s ->
            SectionCard("Minimap zoom") {
                Text(
                    "scale %.3f (base %.3f, x%.2f)".format(s.scale, s.baseScale, s.scale / s.baseScale),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
                ScaleCurve(s.scaleCurve, s.scale)
            }
            SectionCard("Stages and counters") {
                Text(
                    s.stageMs.entries.joinToString("\n") { (k, v) -> "%-9s %7.1f ms".format(k, v) } +
                        "\ntotal     %7.1f ms".format(s.totalMs) + "\n\n" +
                        s.counters.entries.joinToString("   ") { (k, v) -> "$k=$v" },
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            }
        }
        SectionCard("Dataset") {
            Text("Saves the newest full frame and the current pose as a labelled-set candidate (an explicit action; normal ticks only read the minimap).", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { saveMsg = manager.saveFrame()?.let { "Saved $it" } ?: "No frame available" }, enabled = status.capturing) { Text("Save frame") }
            }
            saveMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        SectionCard("Parameters") {
            Text(
                "sigma=${params.sigma} lcnC=${params.lcnC} are baked into the reference images (rebuild to change).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow("Layers", "Detect cave floors", params.layersOn) { manager.saveParams(params.copy(layersOn = it)) }
            ToggleRow("Entrances", "Use layer entry points", params.useEntrances) { manager.saveParams(params.copy(useEntrances = it)) }
            ToggleRow("Icon suppression", null, params.icons) { manager.saveParams(params.copy(icons = it)) }
            ToggleRow("Skip flat minimap", "No relocation search on open sea", params.skipFlat) { manager.saveParams(params.copy(skipFlat = it)) }
            ToggleRow("Feature fallback", "ORB after a failed whole-map search", params.featureFallback) { manager.saveParams(params.copy(featureFallback = it)) }
            ToggleRow("Targets before whole map", "Relocation step 2", params.relocOrderTargets) { manager.saveParams(params.copy(relocOrderTargets = it)) }
            PARAM_FIELDS.groupBy { it.group }.forEach { (g, fields) ->
                Text(g, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                fields.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        OutlinedButton(onClick = { manager.saveParams(f.set(params, f.get(params) - f.step)) }) { Text("-") }
                        Text("%8.3f".format(f.get(params)), fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.size(width = 84.dp, height = 20.dp))
                        OutlinedButton(onClick = { manager.saveParams(f.set(params, f.get(params) + f.step)) }) { Text("+") }
                    }
                }
            }
            OutlinedButton(onClick = { manager.saveParams(TrackerParams()) }) { Text("Reset to defaults") }
        }
    }
}

private fun stateText(s: DebugSnapshot, x: Double, y: Double, h: Double?): String =
    "state=${s.state} mode=${s.mode} floor=${s.floorId}\n" +
        "score=%.3f margin=%.3f conf=%.2f next=%.1fs\n".format(s.score, s.margin, s.confidence, s.delaySec) +
        "ring contrast=%.1f thumb median=%.0f uniform=%.2f layer=%s\n".format(s.ringNcc, s.thumbMedian, s.thumbUniform, s.layerTrigger.ifEmpty { "-" }) +
        "pos=%.0f, %.0f heading=%s\n".format(x, y, h?.let { "%.0f".format(it) } ?: "-") + s.info

@Composable
private fun Labeled(label: String, bmp: android.graphics.Bitmap?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(180.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = label, filterQuality = FilterQuality.None, modifier = Modifier.size(180.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Score of the latest scale test / ladder sweep per ladder rung; the current scale is marked. */
@Composable
private fun ScaleCurve(points: List<Pair<Double, Double>>, current: Double) {
    if (points.isEmpty()) { Text("no scale test in the latest check", style = MaterialTheme.typography.bodySmall); return }
    val line = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.tertiary
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().size(width = 400.dp, height = 90.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))) {
        val lo = points.minOf { it.first }
        val hi = points.maxOf { it.first }
        val span = (hi - lo).coerceAtLeast(1e-6)
        val bw = (size.width / (points.size.coerceAtLeast(2))) * 0.7f
        points.forEach { (sc, score) ->
            val x = ((sc - lo) / span * (size.width - bw)).toFloat()
            val h = (score.coerceIn(0.0, 1.0) * size.height).toFloat()
            drawRect(if (kotlin.math.abs(sc - current) < 1e-9) mark else line, androidx.compose.ui.geometry.Offset(x, size.height - h), androidx.compose.ui.geometry.Size(bw, h))
        }
    }
}
