package com.teyvatmap.thor.ui.point

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import com.teyvatmap.thor.ui.TIcon
import com.teyvatmap.thor.ui.TIcons
import com.teyvatmap.thor.ui.theme.Chrome
import com.teyvatmap.thor.ui.theme.Gold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.teyvatmap.thor.data.db.UserMarkEntity
import com.teyvatmap.thor.domain.MarkState
import com.teyvatmap.thor.domain.RespawnMath
import com.teyvatmap.thor.ui.map.IconCache
import com.teyvatmap.thor.ui.map.LabelIcon
import com.teyvatmap.thor.ui.map.SheetState
import kotlinx.coroutines.delay

/**
 * Point sheet: icon, category, region, **Found / Hide** buttons, respawn countdown, note, description and
 * screenshots. Drawn inside the activity window, not as a ModalBottomSheet, because separate dialog windows would
 * steal controller focus from the game.
 */
@Composable
fun PointSheet(
    state: SheetState.Shown,
    icons: IconCache,
    mark: UserMarkEntity?,
    respawnHours: Int,
    onFound: () -> Unit,
    onHide: () -> Unit,
    onNote: (String) -> Unit,
    onTextFocus: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val info = state.info
    val markState = MarkState.fromDb(mark?.state)
    val found = markState == MarkState.FOUND
    Column(
        modifier
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
            .widthIn(max = 600.dp)
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .background(Chrome.Panel, Chrome.Shape)
            .border(1.dp, Chrome.Border, Chrome.Shape)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(46.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { LabelIcon(icons, info.point.labelId, 34.dp) }
            Column(Modifier.weight(1f)) {
                Text(info.label?.name ?: "Point ${info.point.id}", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val sub = listOfNotNull(info.areaName, if (info.point.floorId != null) "Underground" else null).joinToString(" \u00b7 ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { TIcon(TIcons.Close, "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        Column(
            Modifier.weight(1f, fill = false).padding(end = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RespawnLine(markState, mark?.updatedAt ?: 0, respawnHours)

            val d = state.details
            when {
                d == null -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
                else -> {
                    if (d.description != null) {
                        Text(d.description, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            "No description for this point (or not downloaded yet and no network).",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    d.images.forEach { img ->
                        // Local file first (offline); remote URL as fallback.
                        AsyncImage(
                            model = img.local ?: img.url,
                            contentDescription = "Screenshot",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(10.dp)),
                        )
                    }
                }
            }
            NoteField(info.point.id, mark?.note.orEmpty(), onNote, onTextFocus)
            Text(
                "Point #${info.point.id}  \u00b7  x=${"%.0f".format(info.point.x)} y=${"%.0f".format(info.point.y)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            )
        }

        // Big buttons: pinned to the bottom of the sheet so they do not jump when the content above loads.
        Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onFound,
                modifier = Modifier.weight(1.5f).heightIn(min = 52.dp),
                colors = if (found) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = Gold)
                else ButtonDefaults.buttonColors(),
            ) {
                TIcon(TIcons.Found, null, tint = LocalContentColor.current, size = 22.dp)
                Text(if (found) "Found \u00b7 tap to undo" else "Mark found", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            OutlinedButton(onClick = onHide, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                val hidden = markState == MarkState.HIDDEN
                TIcon(if (hidden) TIcons.Eye else TIcons.EyeOff, null, tint = LocalContentColor.current, size = 22.dp)
                Text(if (hidden) "Unhide" else "Hide", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/** "Respawns in 2d 3h" countdown for a found item, or a hint of the rule for one that is not found. */
@Composable
private fun RespawnLine(state: MarkState, foundAt: Long, hours: Int) {
    if (hours <= 0) return
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    val text = if (state == MarkState.FOUND) {
        val rem = RespawnMath.remainingMs(foundAt, hours, now) ?: return
        "Respawns in ${RespawnMath.formatRemaining(rem)}"
    } else {
        "Respawns ${RespawnMath.formatHours(hours)} after it is found"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

/**
 * Note editor. It saves itself 0.7 s after the last keystroke. The text field needs a focusable window, so the
 * host drops FLAG_NOT_FOCUSABLE while it has focus ([onTextFocus]).
 */
@Composable
private fun NoteField(pointId: Long, saved: String, onNote: (String) -> Unit, onTextFocus: (Boolean) -> Unit) {
    var text by remember(pointId) { mutableStateOf(saved) }
    // Pick up a note that arrived from sync while the sheet is open (and we are not editing).
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (!focused && text != saved) text = saved }
    LaunchedEffect(text) {
        if (text != saved) {
            delay(700)
            onNote(text)
        }
    }
    DisposableEffect(pointId) { onDispose { onTextFocus(false) } }
    androidx.compose.foundation.text.BasicTextField(
        value = text,
        onValueChange = { text = it },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Gold),
        maxLines = 4,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; onTextFocus(it.isFocused) },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text("Add a note", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            }
        },
    )
}
