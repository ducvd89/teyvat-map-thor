package com.teyvatmap.thor.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teyvatmap.thor.data.MapInfo
import com.teyvatmap.thor.data.BulkChange
import com.teyvatmap.thor.data.MarkChange
import com.teyvatmap.thor.data.MarkRepository
import com.teyvatmap.thor.data.MarkSnapshot
import com.teyvatmap.thor.data.db.CustomPinEntity
import com.teyvatmap.thor.data.db.ProgressRow
import com.teyvatmap.thor.data.db.UserMarkEntity
import com.teyvatmap.thor.domain.MarkState
import kotlinx.coroutines.delay
import com.teyvatmap.thor.data.MapRepository
import com.teyvatmap.thor.data.PointDetails
import com.teyvatmap.thor.data.PointInfo
import com.teyvatmap.thor.data.db.FloorEntity
import com.teyvatmap.thor.data.prefs.SettingsRepository
import com.teyvatmap.thor.data.store.TileStore
import com.teyvatmap.thor.domain.FloorNav
import com.teyvatmap.thor.tracking.PlayerPose
import com.teyvatmap.thor.tracking.TrackingManager
import com.teyvatmap.thor.tracking.cv.TrackState
import com.teyvatmap.thor.domain.LabelNode
import com.teyvatmap.thor.domain.SearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.sample
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Request to centre the map on a game coordinate (from a search result). Consumed once by the map screen. */
/** When follow mode should switch the displayed map (pure, unit-tested). */
object FollowMapPolicy {
    /** The map to show, or null to leave things as they are: following + tracking + a settled position on a map other than [displayed]. */
    fun target(following: Boolean, tracking: Boolean, pose: PlayerPose, displayed: Int?): Int? {
        if (!following || !tracking || displayed == null || !pose.hasPosition) return null
        if (pose.state != TrackState.TRACKING && pose.state != TrackState.FIXED) return null
        return pose.mapId.takeIf { it != displayed }
    }
}

data class FocusRequest(val mapId: Int, val x: Double, val y: Double, val nonce: Long = System.nanoTime())

/** The 5 s undo bar after a mark change. [nonce] restarts its timer when a new change replaces the old one. */
data class UndoAction(val message: String, val change: MarkChange? = null, val bulk: BulkChange? = null, val nonce: Long = System.nanoTime())

/** Pin being created (uuid == null) or edited in the in-window pin dialog. */
data class PinDraft(
    val uuid: String?, val mapId: Int, val x: Double, val y: Double, val floorId: Int?,
    val title: String = "", val note: String = "", val color: Int = PIN_COLORS.first(),
)

val PIN_COLORS = listOf(0xFFE5484D.toInt(), 0xFFF5A524.toInt(), 0xFF46A758.toInt(), 0xFF3E9BFF.toInt(), 0xFF8E4EC6.toInt(), 0xFFE93D82.toInt())

sealed interface SheetState {
    data object Hidden : SheetState
    data class Shown(val info: PointInfo, val details: PointDetails?) : SheetState
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MapViewModel @Inject constructor(
    private val repo: MapRepository,
    private val marksRepo: MarkRepository,
    private val settings: SettingsRepository,
    val tileStore: TileStore,
    val icons: IconCache,
    val tracking: TrackingManager,
) : ViewModel() {

    val repository: MapRepository get() = repo

    val maps: StateFlow<List<MapInfo>> = repo.maps().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val selectedMapId = MutableStateFlow<Int?>(null)

    init {
        viewModelScope.launch { selectedMapId.value = settings.current().lastMapId }
        // Respawn timers: FOUND marks revert by themselves when their time is up (checked every minute).
        viewModelScope.launch {
            while (true) {
                runCatching { marksRepo.sweepRespawns() }
                delay(60_000)
            }
        }
    }

    val currentMap: StateFlow<MapInfo?> = combine(maps, selectedMapId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val enabled: StateFlow<Set<Int>> = currentMap.flatMapLatest { m ->
        if (m == null) flowOf(emptySet()) else settings.enabledLabels(m.id).map { it ?: repo.defaultEnabled(m.id) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val tree: StateFlow<List<LabelNode>> = currentMap.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else repo.labelTree(m.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val floors: StateFlow<List<FloorEntity>> = currentMap.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else repo.floors(m.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeFloorId = MutableStateFlow<Int?>(null)

    /** Order of the floors inside their groups (from the data); empty until read. */
    private val floorOrder: StateFlow<Map<Int, Int>> = currentMap.flatMapLatest { m ->
        if (m == null) flowOf(emptyMap()) else flow { emit(repo.floorOrder(m.id)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ------------------------------------------------------------------- live position (PLAN §6.2 / §5.3)
    val pose: StateFlow<PlayerPose> = tracking.pose
    val trackingStatus = tracking.status

    /** Follow mode: the map keeps the player in the centre; switched off by a user pan, back on by the re-centre button. */
    val following = MutableStateFlow(true)

    /** A floor picked by hand wins over auto-detection until the player leaves (or changes) the layer zone. */
    private val floorOverride = MutableStateFlow(false)
    val floorAuto: StateFlow<Boolean> = combine(pose, floorOverride) { p, o -> !o && p.state == TrackState.TRACKING && p.floorId != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var lastGroup: Int? = null

    /** Up / Down layer buttons: set while the view or the player is inside a floor group. */
    val floorNav: StateFlow<FloorNav.Nav?> = combine(floors, activeFloorId, pose, floorOrder) { fl, active, p, order ->
        val inGroup = p.takeIf { it.state == TrackState.TRACKING && it.mapId == currentMap.value?.id }?.groupId
        FloorNav.nav(fl, active, inGroup, order)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun onPose(p: PlayerPose) {
        if (p.mapId != currentMap.value?.id) return
        if (p.state != TrackState.TRACKING) return
        if (p.groupId != lastGroup) { floorOverride.value = false; lastGroup = p.groupId }   // left / changed the zone
        // the shown layer follows the player only in follow mode (a panned-away view keeps what the user looks at)
        if (!following.value) return
        if (!floorOverride.value && activeFloorId.value != p.floorId) activeFloorId.value = p.floorId
    }

    init {
        viewModelScope.launch { combine(tracking.pose, following) { p, _ -> p }.collect { onPose(it) } }
    }

    /** Layer buttons: one step through the group's stack (surface on top); a manual pick overrides auto-detect until the zone is left. */
    fun stepFloor(up: Boolean) {
        val n = floorNav.value ?: return
        if (up && n.canUp) userSetFloor(n.upTo) else if (!up && n.canDown) userSetFloor(n.downTo)
    }

    /** Follow mode crosses maps: a lock on another map (a teleport into The Chasm, Enkanomiya ...) switches the displayed map. */
    init {
        viewModelScope.launch {
            combine(tracking.pose, following, tracking.status, currentMap) { p, f, st, m -> FollowMapPolicy.target(f, st.tracking, p, m?.id) }
                .distinctUntilChanged()
                .collect { id -> if (id != null) { selectMap(id); onPose(tracking.pose.value) } }
        }
    }

    /** The map switcher: choosing a map other than the player's ends follow mode like a pan does (else it would jump straight back). */
    fun userSelectMap(id: Int) {
        val p = tracking.pose.value
        if (id != p.mapId && p.hasPosition && tracking.status.value.tracking) following.value = false
        selectMap(id)
    }

    /** A floor chosen by the user (layer chooser, entrance card): overrides auto-detect until the zone is left. */
    fun userSetFloor(id: Int?) {
        if (tracking.pose.value.hasPosition && tracking.status.value.tracking) floorOverride.value = true
        activeFloorId.value = id
    }

    /** Set by the re-centre button: the next follow step also zooms all the way in. */
    val recenterZoom = MutableStateFlow(false)

    fun recenter() { recenterZoom.value = true; following.value = true }

    /** Retrack button: drop the lock, relocate from scratch and follow; the new lock zooms all the way in. */
    fun retrack() {
        following.value = true
        recenterZoom.value = true
        tracking.retrack()
    }

    /** After a teleport (loading screen / relocation / lost, or another map) the new lock zooms all the way in while following. */
    init {
        viewModelScope.launch {
            var unsettled = false
            var lastMap: Int? = null
            tracking.pose.collect { p ->
                val settled = p.hasPosition && (p.state == TrackState.TRACKING || p.state == TrackState.FIXED)
                if (p.state == TrackState.LOADING || p.state == TrackState.RELOCATING || p.state == TrackState.LOST) unsettled = true
                else if (settled) {
                    if ((unsettled || (lastMap != null && lastMap != p.mapId)) && following.value && tracking.status.value.tracking) recenterZoom.value = true
                    unsettled = false
                    lastMap = p.mapId
                }
            }
        }
    }

    data class NearbyItem(val pointId: Long, val labelId: Int, val name: String, val distance: Double, val bearingDeg: Double)

    val nearbyOpen = MutableStateFlow(false)

    /** Uncollected points of the enabled categories around the player, nearest first (PLAN §5.3 Nearby panel). */
    val nearby: StateFlow<List<NearbyItem>> = combine(pose.sample(1500), nearbyOpen, enabled, tree, markSnapshotFlow()) { p, open, en, tr, _ -> arrayOf<Any>(p, open, en, tr) }
        .flatMapLatest { a ->
            @Suppress("UNCHECKED_CAST")
            val p = a[0] as PlayerPose; val open = a[1] as Boolean; val en = a[2] as Set<Int>; val tr = a[3] as List<LabelNode>
            flow {
                val m = currentMap.value
                if (!open || m == null || !p.hasPosition || p.mapId != m.id) { emit(emptyList()); return@flow }
                val names = HashMap<Int, String>()
                fun walk(n: LabelNode) { if (n.isLeaf) names[n.id] = n.name else n.children.forEach(::walk) }
                tr.forEach(::walk)
                val r = NEARBY_RADIUS
                val pts = repo.pointsInBounds(m.id, en, p.xPos - r, p.xPos + r, p.yPos - r, p.yPos + r, p.floorId, hideFound = true, showHidden = false, limit = 400)
                emit(
                    pts.map {
                        val dx = it.x - p.xPos; val dy = it.y - p.yPos
                        NearbyItem(it.id, it.labelId, names[it.labelId] ?: "Point", hypot(dx, dy), Math.toDegrees(atan2(dx, -dy)))
                    }.filter { it.distance <= r }.sortedBy { it.distance }.take(14),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun markSnapshotFlow() = marksRepo.snapshot.map { it.found.size }.distinctUntilChanged()

    // -------------------------------------------------------------------- layer entrances
    data class EntranceCard(val pointId: Long, val title: String, val leadsTo: List<Pair<Int?, String>>)

    private val _entrance = MutableStateFlow<EntranceCard?>(null)
    val entrance: StateFlow<EntranceCard?> = _entrance

    fun selectEntrance(pointId: Long?) {
        if (pointId == null) { _entrance.value = null; return }
        viewModelScope.launch {
            val links = repo.entranceLinks(pointId)
            val p = repo.pointInfo(pointId)
            val current = activeFloorId.value
            val leads = if (current != null && links.any { it.floorId == current }) listOf<Pair<Int?, String>>(null to "Surface")
            else links.map { it.floorId to (repo.floorName(it.floorId)?.ifBlank { null } ?: "Floor ${it.floorId}") }
            _entrance.value = EntranceCard(pointId, p?.label?.name ?: "Entrance", leads)
        }
    }

    fun shouldRecenterOnFloor(): Boolean = !(following.value && tracking.pose.value.hasPosition && tracking.status.value.tracking)

    // ---------------------------------------------------------------- search
    val query = MutableStateFlow("")

    val searchResults: StateFlow<SearchResults> = combine(query.debounce(200), currentMap) { q, m -> q to m }
        .flatMapLatest { (q, m) ->
            flow {
                if (m == null || q.isBlank()) emit(SearchResults()) else emit(repo.search(m.id, q))
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchResults())

    // ----------------------------------------------------------------- point
    private val selectedPoint = MutableStateFlow<Long?>(null)
    val selectedPointId: StateFlow<Long?> = selectedPoint

    val sheet: StateFlow<SheetState> = selectedPoint.flatMapLatest { id ->
        flow {
            if (id == null) {
                emit(SheetState.Hidden)
                return@flow
            }
            val info = repo.pointInfo(id)
            if (info == null) {
                emit(SheetState.Hidden)
            } else {
                emit(SheetState.Shown(info, null))
                emit(SheetState.Shown(info, repo.details(info.point)))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SheetState.Hidden)

    val focusRequest = MutableStateFlow<FocusRequest?>(null)

    // ----------------------------------------------------------------- marks
    val markSnapshot: StateFlow<MarkSnapshot> =
        marksRepo.snapshot.stateIn(viewModelScope, SharingStarted.Eagerly, MarkSnapshot())

    /** Mark row of the point shown in the sheet (note, found time). */
    val sheetMark: StateFlow<UserMarkEntity?> = selectedPoint.flatMapLatest { id ->
        if (id == null) flowOf(null) else marksRepo.observeMark(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val respawnHours: StateFlow<Map<Int, Int>> =
        marksRepo.respawnHours.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _undo = MutableStateFlow<UndoAction?>(null)
    val undo: StateFlow<UndoAction?> = _undo

    fun toggleFound(pointId: Long) {
        viewModelScope.launch {
            val ch = marksRepo.toggleFound(pointId)
            _undo.value = UndoAction(if (ch.after.state == MarkState.FOUND.db) "Marked found" else "Unmarked", ch)
        }
    }

    fun toggleHidden(pointId: Long) {
        viewModelScope.launch {
            val ch = marksRepo.toggleHidden(pointId)
            _undo.value = UndoAction(if (ch.after.state == MarkState.HIDDEN.db) "Point hidden" else "Point shown again", ch)
        }
    }

    fun undo() {
        val a = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch {
            a.change?.let { marksRepo.undo(it) }
            a.bulk?.let { marksRepo.undoBulk(it) }
        }
    }

    /** Filter drawer: "Mark all found" / "Clear all found" for the leaf categories [leafIds] of the current map (5 s undo). */
    fun bulkSetFound(leafIds: Collection<Int>, found: Boolean, name: String) {
        val m = currentMap.value ?: return
        viewModelScope.launch {
            val ch = marksRepo.bulkSetFound(m.id, leafIds, found)
            val msg = when {
                ch.count == 0 -> if (found) "Nothing to mark in $name" else "No found marks in $name"
                found -> "Marked ${ch.count} found: $name"
                else -> "Cleared ${ch.count} found: $name"
            }
            _undo.value = UndoAction(msg, bulk = ch.takeIf { it.count > 0 })
        }
    }

    fun dismissUndo(nonce: Long) { if (_undo.value?.nonce == nonce) _undo.value = null }

    fun setNote(pointId: Long, note: String) { viewModelScope.launch { marksRepo.setNote(pointId, note) } }

    // ------------------------------------------------------------------ pins
    val pins: StateFlow<List<CustomPinEntity>> = currentMap.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else marksRepo.pins(m.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _pinEditor = MutableStateFlow<PinDraft?>(null)
    val pinEditor: StateFlow<PinDraft?> = _pinEditor

    fun beginPin(x: Double, y: Double) {
        val m = currentMap.value ?: return
        _pinEditor.value = PinDraft(null, m.id, x, y, activeFloorId.value)
    }

    fun editPin(uuid: String) {
        viewModelScope.launch {
            val p = marksRepo.pin(uuid)?.takeIf { !it.deleted } ?: return@launch
            _pinEditor.value = PinDraft(p.uuid, p.mapId, p.x, p.y, p.floorId, p.title, p.note, p.color)
        }
    }

    fun closePinEditor() { _pinEditor.value = null }

    fun savePin(d: PinDraft) {
        _pinEditor.value = null
        viewModelScope.launch { marksRepo.savePin(d.uuid, d.mapId, d.x, d.y, d.floorId, d.title, d.note, d.color) }
    }

    fun deletePin(uuid: String) {
        _pinEditor.value = null
        viewModelScope.launch { marksRepo.deletePin(uuid) }
    }

    // -------------------------------------------------------------- progress
    val progress: StateFlow<List<ProgressRow>> = currentMap.flatMapLatest { m ->
        if (m == null) flowOf(emptyList()) else marksRepo.progress(m.id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Progress screen -> map: show only the categories of [groupId] and centre on the area. */
    fun showProgress(areaId: Int, groupId: Int) {
        val m = currentMap.value ?: return
        viewModelScope.launch {
            val leaves = repo.leavesOfGroup(m.id, groupId)
            settings.setEnabledLabels(m.id, leaves)
            activeFloorId.value = null
            repo.centroid(m.id, areaId, groupId)?.let { (x, y) -> focusRequest.value = FocusRequest(m.id, x, y) }
        }
    }

    // --------------------------------------------------------------- actions
    fun selectMap(id: Int) {
        if (id == selectedMapId.value) return
        selectedPoint.value = null
        activeFloorId.value = null
        query.value = ""
        selectedMapId.value = id
        viewModelScope.launch { settings.setLastMapId(id) }
    }

    fun toggleLabel(id: Int) = setLabels(listOf(id), id !in enabled.value)

    fun setLabels(ids: Collection<Int>, on: Boolean) {
        val m = currentMap.value ?: return
        val next = if (on) enabled.value + ids else enabled.value - ids.toSet()
        viewModelScope.launch { settings.setEnabledLabels(m.id, next) }
    }

    fun clearLabels() {
        val m = currentMap.value ?: return
        viewModelScope.launch { settings.setEnabledLabels(m.id, emptySet()) }
    }

    fun selectPoint(id: Long?) { selectedPoint.value = id }

    fun setFloor(id: Int?) { activeFloorId.value = id }

    companion object { const val NEARBY_RADIUS = 300.0 }

    fun pickLabel(id: Int) {
        setLabels(listOf(id), true)
        query.value = ""
    }

    fun pickPoint(pointId: Long) {
        viewModelScope.launch {
            val p = repo.point(pointId) ?: return@launch
            if (p.mapId != currentMap.value?.id) {
                if (p.mapId != tracking.pose.value.mapId && tracking.pose.value.hasPosition && tracking.status.value.tracking) following.value = false
                selectMap(p.mapId)
            }
            // Make sure the point's category is visible.
            val m = p.mapId
            val cur = settings.enabledLabels(m).first() ?: repo.defaultEnabled(m)
            if (p.labelId !in cur) settings.setEnabledLabels(m, cur + p.labelId)
            activeFloorId.value = p.floorId
            selectedPoint.value = pointId
            focusRequest.value = FocusRequest(m, p.x, p.y)
            query.value = ""
        }
    }

    fun consumeFocus(nonce: Long) {
        if (focusRequest.value?.nonce == nonce) focusRequest.value = null
    }
}
