package com.teyvatmap.thor.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.teyvatmap.thor.ui.InWindowDialog
import com.teyvatmap.thor.ui.TIcon
import com.teyvatmap.thor.ui.TIcons
import com.teyvatmap.thor.ui.theme.Chrome
import com.teyvatmap.thor.ui.theme.Gold
import kotlinx.coroutines.delay

/** The 5 s undo bar (in-window, not a Snackbar host window). */
@Composable
fun UndoBar(action: UndoAction, onUndo: () -> Unit, onTimeout: (Long) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(action.nonce) {
        delay(UNDO_MS)
        onTimeout(action.nonce)
    }
    Row(
        modifier
            .padding(12.dp)
            .background(Chrome.SurfaceSolid, Chrome.Pill)
            .border(1.dp, Chrome.Border, Chrome.Pill)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(action.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp))
        if (action.change != null || action.bulk != null) {
            TextButton(onClick = onUndo) {
                TIcon(TIcons.Undo, null, tint = Gold, size = 18.dp)
                Text("Undo", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = Gold)
            }
        } else Spacer(Modifier.width(12.dp))
    }
}

const val UNDO_MS = 5_000L

/** Create / edit a custom pin: title, note and colour. Needs a focusable window while typing ([onTextFocus]). */
@Composable
fun PinEditor(
    draft: PinDraft,
    onSave: (PinDraft) -> Unit,
    onDelete: (String) -> Unit,
    onCancel: () -> Unit,
    onTextFocus: (Boolean) -> Unit,
) {
    var title by remember(draft) { mutableStateOf(draft.title) }
    var note by remember(draft) { mutableStateOf(draft.note) }
    var color by remember(draft) { mutableStateOf(draft.color) }
    DisposableEffect(Unit) { onDispose { onTextFocus(false) } }
    InWindowDialog(onDismiss = onCancel, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Text(if (draft.uuid == null) "New pin" else "Edit pin", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { onTextFocus(it.isFocused) },
        )
        OutlinedTextField(
            value = note, onValueChange = { note = it }, label = { Text("Note") }, minLines = 2, maxLines = 4,
            modifier = Modifier.fillMaxWidth().onFocusChanged { onTextFocus(it.isFocused) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PIN_COLORS.forEach { c ->
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(40.dp)
                        .background(Color(c), CircleShape)
                        .border(if (c == color) 4.dp else 1.dp, if (c == color) Color.White else Color(0x66FFFFFF), CircleShape)
                        .clickable { color = c },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSave(draft.copy(title = title, note = note, color = color)) }, modifier = Modifier.weight(1f)) { Text("Save") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            if (draft.uuid != null) {
                OutlinedButton(onClick = { onDelete(draft.uuid) }, modifier = Modifier.weight(1f)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
