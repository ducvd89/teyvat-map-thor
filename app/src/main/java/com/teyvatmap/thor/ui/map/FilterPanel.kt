package com.teyvatmap.thor.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.teyvatmap.thor.ui.TIcon
import com.teyvatmap.thor.ui.TIcons
import com.teyvatmap.thor.ui.theme.Chrome
import com.teyvatmap.thor.ui.theme.Gold
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.teyvatmap.thor.domain.LabelNode

/** "Mark all found" / "Clear all found" under a category row, with an in-window confirm step (no Dialog / Popup). */
@Composable
private fun BulkActions(
    name: String, count: Int, depth: Int, pendingFound: Boolean?,
    onAsk: (Boolean) -> Unit, onCancel: () -> Unit, onConfirm: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = (16 + depth * 18).dp, end = 12.dp, bottom = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        if (pendingFound == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAsk(true) }) { Text("Mark all found") }
                TextButton(onClick = { onAsk(false) }) { Text("Clear all found") }
            }
        } else {
            Text(
                if (pendingFound) "Mark all $count points of $name as found?" else "Clear every found mark in $name?",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onConfirm(pendingFound) }) { Text(if (pendingFound) "Yes, mark" else "Yes, clear") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

private data class Row2(val node: LabelNode, val depth: Int)

/** Category tree with icons and counts (right-hand panel). */
@Composable
fun FilterPanel(
    tree: List<LabelNode>,
    enabled: Set<Int>,
    icons: IconCache,
    hideFound: Boolean,
    onHideFound: (Boolean) -> Unit,
    showHidden: Boolean,
    onShowHidden: (Boolean) -> Unit,
    onToggleLeaf: (Int) -> Unit,
    onSetGroup: (List<Int>, Boolean) -> Unit,
    onClearAll: () -> Unit,
    onBulkFound: (leafIds: List<Int>, found: Boolean, name: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Set<Int> = emptySet(),
) {
    val expanded = remember { mutableStateListOf<Int>().apply { addAll(initiallyExpanded) } }
    // Inline bulk-mark controls: at most one row has its actions open; a pending action asks for a confirm first.
    var actionsFor by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    val rows = buildList {
        fun walk(nodes: List<LabelNode>, depth: Int) {
            for (n in nodes) {
                add(Row2(n, depth))
                if (!n.isLeaf && n.id in expanded) walk(n.children, depth + 1)
            }
        }
        walk(tree, 0)
    }
    Column(
        modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(8.dp)
            .width(372.dp)
            .fillMaxHeight()
            .background(Chrome.SurfaceSolid, Chrome.Shape)
            .border(1.dp, Chrome.Border, Chrome.Shape)
            .clip(Chrome.Shape)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Categories", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClearAll) { Text("Clear all") }
            RailButton(TIcons.Close, "Close filter", onClose)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(TIcons.EyeOff, "Hide found", hideFound, onHideFound, Modifier.weight(1f))
            ToggleChip(TIcons.Eye, "Show hidden", showHidden, onShowHidden, Modifier.weight(1f))
        }
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(Chrome.Border))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(rows, key = { it.node.id }) { (node, depth) ->
                val leafIds = remember(node) { node.leafIds() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .clickable {
                            if (node.isLeaf) onToggleLeaf(node.id)
                            else if (node.id in expanded) expanded.remove(node.id) else expanded.add(node.id)
                        }
                        .padding(start = (8 + depth * 20).dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (node.isLeaf) {
                        Checkbox(checked = node.id in enabled, onCheckedChange = { onToggleLeaf(node.id) })
                        LabelIcon(icons, node.id, 26.dp)
                    } else {
                        val on = leafIds.count { it in enabled }
                        val st = when (on) { 0 -> ToggleableState.Off; leafIds.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
                        TriStateCheckbox(state = st, onClick = { onSetGroup(leafIds, st != ToggleableState.On) })
                        TIcon(
                            if (node.id in expanded) TIcons.ChevronDown else TIcons.ChevronRight, if (node.id in expanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.primary, size = 18.dp,
                        )
                    }
                    Text(
                        node.name,
                        Modifier.weight(1f).padding(start = 10.dp),
                        style = if (node.isLeaf) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                    )
                    Text(node.count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(
                        onClick = { actionsFor = if (actionsFor == node.id) null else node.id; pending = null },
                        modifier = Modifier.size(44.dp),
                    ) { TIcon(TIcons.More, "Found marks for ${node.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp) }
                }
                if (actionsFor == node.id) {
                    BulkActions(
                        name = node.name, count = node.count, depth = depth,
                        pendingFound = pending?.takeIf { it.first == node.id }?.second,
                        onAsk = { found -> pending = node.id to found },
                        onCancel = { pending = null },
                        onConfirm = { found ->
                            onBulkFound(leafIds, found, node.name)
                            pending = null; actionsFor = null
                        },
                    )
                }
            }
        }
    }
}

/** Two-state pill with an icon: gold wash when on. */
@Composable
fun ToggleChip(icon: Int, label: String, checked: Boolean, onChecked: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = if (checked) Gold else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clip(Chrome.ShapeSmall)
            .background(if (checked) Chrome.Active else Color.Transparent)
            .border(1.dp, if (checked) Gold.copy(alpha = 0.6f) else Chrome.Border, Chrome.ShapeSmall)
            .clickable(role = Role.Switch) { onChecked(!checked) }
            .semantics { selected = checked }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        TIcon(icon, null, tint = c, size = 20.dp)
        Text(label, style = MaterialTheme.typography.labelLarge, color = c, maxLines = 1)
    }
}
