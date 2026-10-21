package com.teyvatmap.thor.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.teyvatmap.thor.data.MapRepository
import com.teyvatmap.thor.data.MarkRepository
import com.teyvatmap.thor.domain.LabelNode
import com.teyvatmap.thor.domain.RespawnDefaults
import com.teyvatmap.thor.domain.RespawnMath
import com.teyvatmap.thor.ui.ScreenPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RespawnViewModel @Inject constructor(
    repo: MapRepository,
    private val marks: MarkRepository,
) : ViewModel() {
    /** Categories of the main map (the rules are per label id and apply on every map). */
    val tree: StateFlow<List<LabelNode>> = repo.labelTree(2).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hours: StateFlow<Map<Int, Int>> = marks.respawnHours.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun set(ids: Collection<Int>, hours: Int) { viewModelScope.launch { marks.setRespawnOverride(ids, hours) } }

    /** Back to the shipped defaults (an override equal to the default is stored as such, which is equivalent). */
    fun resetAll() {
        viewModelScope.launch {
            val groupOf = HashMap<Int, Int>()
            for (g in tree.value) for (id in g.leafIds()) groupOf[id] = g.id
            val byHours = groupOf.keys.groupBy { RespawnDefaults.hoursFor(it, groupOf.getValue(it)) }
            for ((h, ids) in byHours) marks.setRespawnOverride(ids, h)
        }
    }
}

/** Editable respawn timers: per category group (sets all of its categories) and per category. */
@Composable
fun RespawnScreen(onBack: () -> Unit, vm: RespawnViewModel = hiltViewModel()) {
    val tree by vm.tree.collectAsStateWithLifecycle()
    val hours by vm.hours.collectAsStateWithLifecycle()
    val expanded = remember { mutableStateListOf<Int>() }

    ScreenPage("Respawn timers", onBack) {
        Text(
            "A found item returns to \"not found\" by itself after this time. 0 = never. " +
                "Defaults: ores 3 d, local specialties 2 d, wood 2 d, plants 1 d, animals 1 d, crystalflies 12 h.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = vm::resetAll) { Text("Reset all to defaults") }
        tree.forEach { g ->
            val ids = g.leafIds()
            val values = ids.map { hours[it] ?: 0 }.distinct()
            Row(
                Modifier.fillMaxWidth().clickable { if (g.id in expanded) expanded.remove(g.id) else expanded.add(g.id) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (g.id in expanded) "v" else ">", color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(18.dp))
                Text(g.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Stepper(
                    label = if (values.size == 1) RespawnMath.formatHours(values.first()) else "mixed",
                    onMinus = { vm.set(ids, RespawnMath.stepDown(values.maxOrNull() ?: 0)) },
                    onPlus = { vm.set(ids, RespawnMath.stepUp(values.minOrNull() ?: 0)) },
                )
            }
            if (g.id in expanded) {
                g.children.forEach { l ->
                    val h = hours[l.id] ?: 0
                    Row(
                        Modifier.fillMaxWidth().padding(start = 26.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(l.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Stepper(RespawnMath.formatHours(h), { vm.set(listOf(l.id), RespawnMath.stepDown(h)) }, { vm.set(listOf(l.id), RespawnMath.stepUp(h)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onMinus, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(44.dp)) { Text("−") }
        Text(label, Modifier.width(68.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onPlus, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), modifier = Modifier.width(44.dp)) { Text("+") }
    }
}
