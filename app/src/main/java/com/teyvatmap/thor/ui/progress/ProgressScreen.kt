package com.teyvatmap.thor.ui.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teyvatmap.thor.data.db.ProgressRow
import com.teyvatmap.thor.domain.AreaNames
import com.teyvatmap.thor.domain.LabelNode
import com.teyvatmap.thor.ui.ScreenPage
import com.teyvatmap.thor.ui.SectionCard
import com.teyvatmap.thor.ui.map.MapViewModel

/** One area with its per-category progress. */
data class AreaProgress(val areaId: Int, val name: String, val found: Int, val total: Int, val groups: List<GroupProgress>)

data class GroupProgress(val groupId: Int, val name: String, val found: Int, val total: Int)

/** Pure table builder (unit-tested): rows of area x group -> areas sorted by size, groups in tree order. */
fun buildProgress(rows: List<ProgressRow>, groups: List<LabelNode>, mapName: String?): List<AreaProgress> {
    val order = groups.withIndex().associate { it.value.id to it.index }
    val names = groups.associate { it.id to it.name }
    return rows.groupBy { it.areaId }.map { (area, rs) ->
        val gs = rs.filter { it.total > 0 && it.groupId in names }
            .sortedBy { order[it.groupId] ?: Int.MAX_VALUE }
            .map { GroupProgress(it.groupId, names.getValue(it.groupId), it.found, it.total) }
        AreaProgress(area, AreaNames.name(area, mapName), gs.sumOf { it.found }, gs.sumOf { it.total }, gs)
    }.filter { it.total > 0 }.sortedByDescending { it.total }
}

/** Area x category progress (PLAN §5.5). Tapping a cell filters the map to that category and area. */
@Composable
fun ProgressScreen(onBack: () -> Unit, onPick: () -> Unit, vm: MapViewModel = hiltViewModel()) {
    val rows by vm.progress.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val map by vm.currentMap.collectAsStateWithLifecycle()
    val areas = remember(rows, tree, map) { buildProgress(rows, tree, map?.name) }
    val expanded = remember { mutableStateListOf<Int>() }
    val totalFound = areas.sumOf { it.found }
    val total = areas.sumOf { it.total }

    ScreenPage("Progress · ${map?.name?.removeSuffix(" Interactive Map") ?: ""}", onBack) {
        Text(
            "$totalFound / $total found" + if (total > 0) "  (${totalFound * 100 / total}%)" else "",
            style = MaterialTheme.typography.titleMedium,
        )
        if (total > 0) LinearProgressIndicator(progress = { totalFound.toFloat() / total }, Modifier.fillMaxWidth())
        Text(
            "Tap a category to show only it on the map. Hidden points are not counted.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        areas.forEach { a ->
            val open = a.areaId in expanded || areas.size == 1
            SectionCard(a.name) {
                Row(
                    Modifier.fillMaxWidth().clickable { if (a.areaId in expanded) expanded.remove(a.areaId) else expanded.add(a.areaId) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LinearProgressIndicator(progress = { a.found.toFloat() / a.total }, Modifier.weight(1f))
                    Text("${a.found} / ${a.total}", style = MaterialTheme.typography.titleSmall)
                    com.teyvatmap.thor.ui.TIcon(if (open) com.teyvatmap.thor.ui.TIcons.ChevronDown else com.teyvatmap.thor.ui.TIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.primary, size = 20.dp)
                }
                if (open) {
                    a.groups.forEach { g ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { vm.showProgress(a.areaId, g.groupId); onPick() }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("${a.name} · ${g.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("${g.found} / ${g.total}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
