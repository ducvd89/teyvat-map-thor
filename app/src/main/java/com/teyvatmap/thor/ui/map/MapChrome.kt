package com.teyvatmap.thor.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teyvatmap.thor.data.db.FloorEntity
import com.teyvatmap.thor.data.db.UserMarkEntity
import com.teyvatmap.thor.domain.LabelNode
import com.teyvatmap.thor.domain.SearchResults
import com.teyvatmap.thor.sync.SyncPhase
import com.teyvatmap.thor.sync.SyncUiState
import com.teyvatmap.thor.tracking.TrackingStatus
import com.teyvatmap.thor.tracking.cv.TrackState
import com.teyvatmap.thor.ui.TIcon
import com.teyvatmap.thor.ui.TIcons
import com.teyvatmap.thor.ui.point.PointSheet
import com.teyvatmap.thor.ui.theme.Chrome
import com.teyvatmap.thor.ui.theme.Gold
import kotlinx.coroutines.delay

/** Which side panel is open next to the rail. */
enum class MapPanel { NONE, FILTER, FLOORS, MAPS, MENU, NEARBY }

data class MapChoice(val id: Int, val name: String)

/** Everything the control layer above the map needs to draw (pure data, so it can be rendered without a ViewModel). */
data class ChromeState(
    val maps: List<MapChoice> = emptyList(),
    val currentMapId: Int? = null,
    val query: String = "",
    val results: SearchResults = SearchResults(),
    val searchOpen: Boolean = false,
    val panel: MapPanel = MapPanel.NONE,
    val tree: List<LabelNode> = emptyList(),
    val enabled: Set<Int> = emptySet(),
    val hideFound: Boolean = false,
    val showHidden: Boolean = false,
    val floors: List<FloorEntity> = emptyList(),
    val activeFloorId: Int? = null,
    val floorAuto: Boolean = false,
    /** Up / Down layer buttons; null when neither the view nor the player is inside a floor group. */
    val floorNav: com.teyvatmap.thor.domain.FloorNav.Nav? = null,
    val track: TrackingStatus = TrackingStatus(),
    val trackState: TrackState = TrackState.OFF,
    val hasPosition: Boolean = false,
    val following: Boolean = false,
    val calibrated: Boolean = true,
    val sync: SyncUiState = SyncUiState(),
    val gameModeAvailable: Boolean = false,
    val hasOpenApp: Boolean = false,
    val canMoveToOtherScreen: Boolean = false,
    val nearby: List<MapViewModel.NearbyItem> = emptyList(),
    val entrance: MapViewModel.EntranceCard? = null,
    val undo: UndoAction? = null,
    val sheet: SheetState.Shown? = null,
    val sheetMark: UserMarkEntity? = null,
    val respawnHours: Int = 0,
    val expandedGroups: Set<Int> = emptySet(),
)

class ChromeActions(
    val onPanel: (MapPanel) -> Unit = {},
    val onSearchOpen: (Boolean) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onSearchFocus: (Boolean) -> Unit = {},
    val onPickLabel: (Int) -> Unit = {},
    val onPickPoint: (Long) -> Unit = {},
    val onPickMap: (Int) -> Unit = {},
    val onToggleLeaf: (Int) -> Unit = {},
    val onSetGroup: (List<Int>, Boolean) -> Unit = { _, _ -> },
    val onClearLabels: () -> Unit = {},
    val onBulkFound: (List<Int>, Boolean, String) -> Unit = { _, _, _ -> },
    val onHideFound: (Boolean) -> Unit = {},
    val onShowHidden: (Boolean) -> Unit = {},
    val onPickFloor: (Int?) -> Unit = {},
    val onFloorStep: (up: Boolean) -> Unit = {},
    val onFollowToggle: () -> Unit = {},
    val onRecenter: () -> Unit = {},
    val onRetrack: () -> Unit = {},
    val onNearbyFound: (Long) -> Unit = {},
    val onTrackingClick: () -> Unit = {},
    val onSyncClick: () -> Unit = {},
    val onGameMode: () -> Unit = {},
    val onOpenApp: () -> Unit = {},
    val onOpenProgress: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenCalibration: () -> Unit = {},
    val onOpenDebug: () -> Unit = {},
    val onOpenDiagnostics: () -> Unit = {},
    val onMoveToOtherScreen: () -> Unit = {},
    val onTogglePause: () -> Unit = {},
    val onStopCapture: () -> Unit = {},
    val onEntranceShow: (Int?) -> Unit = {},
    val onEntranceClose: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onUndoTimeout: (Long) -> Unit = {},
    val onSheetFound: () -> Unit = {},
    val onSheetHide: () -> Unit = {},
    val onSheetNote: (String) -> Unit = {},
    val onTextFocus: (Boolean) -> Unit = {},
    val onSheetClose: () -> Unit = {},
)

/** Width reserved on the right for a one-column rail so panels and the sheet never sit under it. */
private val RailInset = 58.dp
private val RailInset2 = 106.dp

private fun railButtonCount(s: ChromeState): Int {
    var n = 4                                           // filter, search, tracking, more (progress / open app / game mode live in the menu)
    if (s.track.tracking) n += 3                        // follow, retrack, nearby
    if (s.floors.isNotEmpty()) n++
    if (s.maps.size > 1) n++
    if (s.sync.enabled) n++
    return n
}

/** Whether the rail has to be split into two columns because the screen is too short for one. */
@Composable
private fun rememberTwoColumnRail(s: ChromeState): Boolean {
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val need = railButtonCount(s) * 40 + 3 * 6 + 2 * 6 + 16
    return need > screenH
}

/**
 * Every control that floats above the map: search, the single right-hand rail of icon buttons, the status chip, side
 * panels, undo bar and point sheet. Stateless: [MapScreen] feeds it from the ViewModels.
 */
@Composable
fun BoxScope.MapChrome(s: ChromeState, a: ChromeActions, icons: IconCache, modifier: Modifier = Modifier) {
    val showSearch = s.searchOpen || s.query.isNotBlank()
    val twoCol = rememberTwoColumnRail(s)
    val RailInset = if (twoCol) RailInset2 else RailInset

    // ---------------------------------------------------------------- search (top-left)
    if (showSearch) {
        Column(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 10.dp, top = 10.dp, end = RailInset)
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(s.query, a.onQuery, a.onSearchFocus, onClose = { a.onQuery(""); a.onSearchOpen(false) })
            if (s.query.isNotBlank()) SearchResultsPanel(s.results, icons, a.onPickLabel, a.onPickPoint)
        }
    }

    // ---------------------------------------------------------------- rail (right)
    MapRail(s, a, twoCol, Modifier.align(Alignment.TopEnd).statusBarsPadding().navigationBarsPadding().padding(end = 6.dp, top = 6.dp, bottom = 6.dp))

    // ---------------------------------------------------------------- side panels
    AnimatedVisibility(
        visible = s.panel == MapPanel.FILTER,
        modifier = Modifier.align(Alignment.CenterEnd),
        enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
    ) {
        FilterPanel(
            tree = s.tree, enabled = s.enabled, icons = icons, hideFound = s.hideFound,
            onHideFound = a.onHideFound, showHidden = s.showHidden, onShowHidden = a.onShowHidden,
            onToggleLeaf = a.onToggleLeaf, onSetGroup = a.onSetGroup, onClearAll = a.onClearLabels,
            onBulkFound = a.onBulkFound, initiallyExpanded = s.expandedGroups, onClose = { a.onPanel(MapPanel.NONE) },
        )
    }
    AnimatedVisibility(
        visible = s.panel == MapPanel.FLOORS,
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = RailInset),
        enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
    ) { FloorPanel(s.floors, s.activeFloorId, s.floorAuto, onPick = { a.onPickFloor(it); a.onPanel(MapPanel.NONE) }) }
    AnimatedVisibility(
        visible = s.panel == MapPanel.MAPS,
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = RailInset),
        enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
    ) { MapsPanel(s.maps, s.currentMapId, onPick = { a.onPickMap(it); a.onPanel(MapPanel.NONE) }) }
    AnimatedVisibility(
        visible = s.panel == MapPanel.MENU,
        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = RailInset, top = 6.dp),
        enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
    ) { MenuPanel(s, a) }
    AnimatedVisibility(
        visible = s.panel == MapPanel.NEARBY && s.track.tracking,
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
        enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it },
    ) {
        NearbyPanel(s.nearby, icons, onFound = a.onNearbyFound, onClose = { a.onPanel(MapPanel.NONE) })
    }

    // ---------------------------------------------------------------- one status chip
    if (!showSearch) {
        val floorLabel = s.floors.firstOrNull { it.id == s.activeFloorId }?.name?.ifBlank { null }
        StatusChip(
            s.track, s.trackState,
            floor = when {
                s.activeFloorId != null -> floorLabel ?: "Floor ${s.activeFloorId}"
                s.track.tracking && s.hasPosition -> "Surface"
                else -> null
            },
            floorAuto = s.floorAuto,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 10.dp),
        )
    }

    // ---------------------------------------------------------------- Up / Down through the layers of the group
    s.floorNav?.let { nav ->
        if (!(s.panel == MapPanel.NEARBY && s.track.tracking) && !showSearch) {
            LayerStepper(nav, a.onFloorStep, Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
        }
    }

    if (s.track.tracking && s.hasPosition && !s.following) {
        RecenterFab(
            onClick = a.onRecenter,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = RailInset, bottom = 12.dp),
        )
    }

    // ---------------------------------------------------------------- bottom: entrance card, undo, point sheet
    Column(
        Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(end = RailInset).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        s.entrance?.let { e -> EntranceCardView(e, onShow = a.onEntranceShow, onClose = a.onEntranceClose) }
        s.undo?.let { u -> UndoBar(u, onUndo = a.onUndo, onTimeout = a.onUndoTimeout) }
        AnimatedVisibility(
            visible = s.sheet != null,
            enter = slideInVertically { it }, exit = slideOutVertically { it },
        ) {
            // Keep the last sheet content during the exit animation.
            val last = remember { mutableStateOf<SheetState.Shown?>(null) }
            if (s.sheet != null) last.value = s.sheet
            last.value?.let { shown ->
                PointSheet(
                    shown, icons,
                    mark = s.sheetMark?.takeIf { it.pointId == shown.info.point.id },
                    respawnHours = s.respawnHours,
                    onFound = a.onSheetFound, onHide = a.onSheetHide, onNote = a.onSheetNote,
                    onTextFocus = a.onTextFocus, onClose = a.onSheetClose,
                )
            }
        }
    }
}

// ====================================================================================================== rail

/** One 40 dp icon button; [selected] = gold tint on a gold wash. [label] only where the icon alone is ambiguous. */
@Composable
fun RailButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color? = null,
    badge: String? = null,
    label: String? = null,
) {
    val color = tint ?: if (selected) Gold else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
    Box(
        modifier
            .size(width = 40.dp, height = if (label != null) 48.dp else 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Chrome.Active else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TIcon(icon, null, tint = color, size = 22.dp)
            if (label != null) Text(label, fontSize = 9.sp, lineHeight = 12.sp, color = color, maxLines = 1)
        }
        if (badge != null) {
            Text(
                badge, fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 0.dp)
                    .background(Gold, Chrome.Pill).padding(horizontal = 4.dp, vertical = 0.dp),
            )
        }
    }
}

/** Compact Up / Down pair with the position in the stack ("2/4"; the surface is the top, 0). Disabled at the ends. */
@Composable
private fun LayerStepper(nav: com.teyvatmap.thor.domain.FloorNav.Nav, onStep: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    RailGroup(modifier) {
        LayerStepButton(TIcons.LayerUp, "Layer up", nav.canUp) { onStep(true) }
        Text(
            if (nav.index == 0) "Surf." else "${nav.index}/${nav.count}",
            fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1,
            color = if (nav.index == 0) MaterialTheme.colorScheme.onSurfaceVariant else Gold,
        )
        LayerStepButton(TIcons.LayerDown, "Layer down", nav.canDown) { onStep(false) }
    }
}

@Composable
private fun LayerStepButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        TIcon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.9f else 0.28f), size = 24.dp)
    }
}

@Composable
private fun RailGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .background(Chrome.Surface, Chrome.Shape)
            .border(1.dp, Chrome.Border, Chrome.Shape)
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** The single right-hand rail: map group, tools group, system group (scrolls if the screen is short). */
@Composable
fun MapRail(s: ChromeState, a: ChromeActions, twoColumns: Boolean, modifier: Modifier = Modifier) {
    fun toggle(p: MapPanel) = a.onPanel(if (s.panel == p) MapPanel.NONE else p)
    val mapGroup: @Composable () -> Unit = {
        val showMapGroup = s.track.tracking || s.floors.isNotEmpty() || s.maps.size > 1
        if (showMapGroup) RailGroup {
            if (s.track.tracking) RailButton(TIcons.Follow, if (s.following) "Following you, tap to stop" else "Follow your position", a.onFollowToggle, selected = s.following)
            if (s.track.tracking) RailButton(TIcons.Retrack, "Retrack: find my position again", a.onRetrack)
            if (s.floors.isNotEmpty()) RailButton(TIcons.Layers, "Underground layers", { toggle(MapPanel.FLOORS) }, selected = s.panel == MapPanel.FLOORS || s.activeFloorId != null)
            if (s.maps.size > 1) RailButton(TIcons.MapSwitch, "Switch map", { toggle(MapPanel.MAPS) }, selected = s.panel == MapPanel.MAPS)
        }
    }
    val toolsGroup: @Composable () -> Unit = {
        RailGroup {
            RailButton(
                TIcons.Filter, "Filter categories", { toggle(MapPanel.FILTER) },
                selected = s.panel == MapPanel.FILTER, badge = s.enabled.size.takeIf { it > 0 }?.toString(),
            )
            RailButton(TIcons.Search, "Search", { a.onSearchOpen(!s.searchOpen) }, selected = s.searchOpen || s.query.isNotBlank())
            if (s.track.tracking) RailButton(TIcons.Nearby, "Nearby points", { toggle(MapPanel.NEARBY) }, selected = s.panel == MapPanel.NEARBY)
        }
    }
    val systemGroup: @Composable () -> Unit = {
        RailGroup {
            val t = s.track
            when {
                t.tracking && t.paused -> RailButton(TIcons.Pause, "Tracking paused, tap to resume", a.onTrackingClick, selected = true)
                t.tracking -> RailButton(TIcons.TrackingOn, "Tracking on, tap to pause", a.onTrackingClick, selected = true)
                t.capturing -> RailButton(TIcons.TrackingOn, "Capturing without a profile, tap to stop", a.onTrackingClick, tint = MaterialTheme.colorScheme.error)
                !s.calibrated -> RailButton(TIcons.TrackingOff, "Set up tracking (calibrate minimap)", a.onTrackingClick, label = "Set up")
                else -> RailButton(TIcons.TrackingOff, "Start tracking", a.onTrackingClick)
            }
            if (s.sync.enabled) {
                val (ic, d, tint) = when (s.sync.phase) {
                    SyncPhase.SYNCING -> Triple(TIcons.SyncBusy, "Syncing", Gold)
                    SyncPhase.IDLE -> Triple(TIcons.SyncOk, "Synced, tap to sync now", null)
                    SyncPhase.NEEDS_SIGN_IN -> Triple(TIcons.SyncError, "Sign-in needed, open settings", MaterialTheme.colorScheme.error)
                    else -> Triple(TIcons.SyncError, "Sync error, tap to retry", MaterialTheme.colorScheme.error)
                }
                RailButton(ic, d, a.onSyncClick, tint = tint)
            }
            RailButton(TIcons.More, "More", { toggle(MapPanel.MENU) }, selected = s.panel == MapPanel.MENU)
        }
    }
    if (!twoColumns) {
        Column(modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(6.dp), Alignment.CenterHorizontally) {
            mapGroup(); toolsGroup(); systemGroup()
        }
    } else {
        Row(modifier.verticalScroll(rememberScrollState()), Arrangement.spacedBy(6.dp), Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) { mapGroup(); systemGroup() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) { toolsGroup() }
        }
    }
}

// ================================================================================================ status chip

/** "Tracking", "Finding position…", "In a domain", "Position lost" ... plus the current layer, in one compact chip. */
@Composable
fun StatusChip(status: TrackingStatus, state: TrackState, floor: String?, floorAuto: Boolean, modifier: Modifier = Modifier) {
    val text: String? = when {
        status.capturing && !status.tracking && status.message != null -> status.message
        !status.tracking -> null
        status.paused -> "Paused"
        else -> when (state) {
            TrackState.LOADING -> "Loading…"
            TrackState.RELOCATING -> "Finding position…"
            TrackState.DOMAIN -> "In a domain"
            TrackState.LOST -> "Position lost"
            TrackState.HIDDEN -> "Minimap hidden"
            TrackState.FIXED -> "Fixed from map"
            TrackState.TRACKING -> "Tracking"
            else -> null
        }
    }
    if (text == null && floor == null) return
    val warn = state == TrackState.LOST || (status.message != null && !status.tracking)
    val dot = when {
        warn -> MaterialTheme.colorScheme.error
        status.paused -> MaterialTheme.colorScheme.outline
        state == TrackState.TRACKING || state == TrackState.FIXED -> Gold
        else -> MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier
            .background(Chrome.Surface, Chrome.Pill)
            .border(1.dp, if (warn) MaterialTheme.colorScheme.error.copy(alpha = 0.7f) else Chrome.Border, Chrome.Pill)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (text != null) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(
                text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }
        if (floor != null) {
            if (text != null) Box(Modifier.size(width = 1.dp, height = 16.dp).background(Chrome.Border))
            TIcon(TIcons.Layers, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp)
            Text(floor, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.widthIn(max = 200.dp))
            if (floorAuto) Text("auto", style = MaterialTheme.typography.labelSmall, color = Gold)
        }
    }
}

@Composable
private fun RecenterFab(onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(56.dp)
            .background(Gold, CircleShape)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Re-centre on your position" },
        contentAlignment = Alignment.Center,
    ) { TIcon(TIcons.Recenter, null, tint = Color(0xFF241A00), size = 26.dp) }
}

// ====================================================================================================== search

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, onFocus: (Boolean) -> Unit, onClose: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (query.isBlank()) runCatching { requester.requestFocus() } }
    LaunchedEffect(focused) {
        if (focused) {
            // The window flag is dropped by the activity once the field is focused; give it a moment, then show the IME.
            delay(180)
            keyboard?.show()
            delay(450)          // an overlay window only becomes focusable after a round trip to the window manager
            keyboard?.show()
        }
    }
    TextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text("Search categories and descriptions", maxLines = 1) },
        leadingIcon = { TIcon(TIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = {
            RailButton(TIcons.Close, "Close search", { focusManager.clearFocus(); onClose() })
        },
        shape = Chrome.Pill,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Chrome.SurfaceSolid,
            unfocusedContainerColor = Chrome.Surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().focusRequester(requester)
            .border(1.dp, Chrome.Border, Chrome.Pill)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) },
    )
}

@Composable
private fun PanelSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .background(Chrome.Panel, Chrome.Shape)
            .border(1.dp, Chrome.Border, Chrome.Shape)
            .padding(6.dp),
    ) { content() }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = Gold)
}

@Composable
private fun SearchResultsPanel(results: SearchResults, icons: IconCache, onLabel: (Int) -> Unit, onPoint: (Long) -> Unit) {
    PanelSurface(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
        if (results.isEmpty) {
            Text("No matches", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn {
                if (results.labels.isNotEmpty()) item { SectionLabel("Categories") }
                items(results.labels, key = { "l${it.id}" }) { h ->
                    Row(Modifier.fillMaxWidth().clickable { onLabel(h.id) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LabelIcon(icons, h.id, 28.dp)
                        Text(h.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        Text("${h.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (results.points.isNotEmpty()) item { SectionLabel("Points") }
                items(results.points, key = { "p${it.pointId}" }) { h ->
                    Row(Modifier.fillMaxWidth().clickable { onPoint(h.pointId) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LabelIcon(icons, h.labelId, 28.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(h.labelName, style = MaterialTheme.typography.bodyMedium)
                            Text(h.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

// ================================================================================================== panels

@Composable
private fun PanelRow(text: String, selected: Boolean, onClick: () -> Unit, icon: Int? = null, tint: Color? = null, trailing: @Composable (() -> Unit)? = null) {
    val c = tint ?: if (selected) Gold else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Chrome.Active else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) TIcon(icon, null, tint = c, size = 22.dp)
        Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = c, maxLines = 2)
        trailing?.invoke()
    }
}

@Composable
private fun FloorPanel(floors: List<FloorEntity>, active: Int?, auto: Boolean, onPick: (Int?) -> Unit) {
    PanelSurface(Modifier.widthIn(min = 240.dp, max = 340.dp).heightIn(max = 460.dp)) {
        SectionLabel(if (auto) "Underground layers (auto)" else "Underground layers")
        PanelRow("Surface", active == null, { onPick(null) }, TIcons.Layers)
        LazyColumn {
            items(floors, key = { it.id }) { f ->
                PanelRow(f.name.ifBlank { "Floor ${f.id}" }, f.id == active, { onPick(f.id) })
            }
        }
    }
}

@Composable
private fun MapsPanel(maps: List<MapChoice>, current: Int?, onPick: (Int) -> Unit) {
    PanelSurface(Modifier.widthIn(min = 220.dp, max = 320.dp).heightIn(max = 460.dp)) {
        SectionLabel("Map")
        LazyColumn { items(maps, key = { it.id }) { m -> PanelRow(shortName(m.name), m.id == current, { onPick(m.id) }, TIcons.MapSwitch) } }
    }
}

@Composable
private fun MenuPanel(s: ChromeState, a: ChromeActions) {
    fun go(f: () -> Unit): () -> Unit = { a.onPanel(MapPanel.NONE); f() }
    PanelSurface(Modifier.widthIn(min = 250.dp, max = 320.dp)) {
        PanelRow("Progress", false, go(a.onOpenProgress), TIcons.Progress)
        if (s.gameModeAvailable) PanelRow("Start on bottom screen (game mode)", false, go(a.onGameMode), TIcons.GameMode)
        if (s.hasOpenApp) PanelRow("Open the app", false, go(a.onOpenApp), TIcons.OpenApp)
        if (s.track.tracking) PanelRow(if (s.track.paused) "Resume tracking" else "Pause tracking", false, go(a.onTogglePause), if (s.track.paused) TIcons.Play else TIcons.Pause)
        if (s.track.capturing) PanelRow("Stop screen capture", false, go(a.onStopCapture), TIcons.TrackingOff)
        PanelRow("Calibrate minimap", false, go(a.onOpenCalibration), TIcons.Calibrate)
        PanelRow("Settings", false, go(a.onOpenSettings), TIcons.Settings)
        PanelRow("Tracking debug", false, go(a.onOpenDebug), TIcons.Debug)
        PanelRow("Diagnostics", false, go(a.onOpenDiagnostics), TIcons.Diagnostics)
        if (s.canMoveToOtherScreen) PanelRow("Move to other screen", false, go(a.onMoveToOtherScreen), TIcons.OpenApp)
    }
}

/** Uncollected points near the player, nearest first, with a direction arrow and a one-tap Found. */
@Composable
private fun NearbyPanel(items: List<MapViewModel.NearbyItem>, icons: IconCache, onFound: (Long) -> Unit, onClose: () -> Unit) {
    PanelSurface(Modifier.widthIn(min = 300.dp, max = 340.dp).heightIn(max = 460.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Nearby", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            RailButton(TIcons.Close, "Close nearby list", onClose)
        }
        if (items.isEmpty()) {
            Text("Nothing uncollected within ${MapViewModel.NEARBY_RADIUS.toInt()} px.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn {
                items(items, key = { it.pointId }) { n ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Canvas(Modifier.size(24.dp)) {
                            rotate(n.bearingDeg.toFloat()) {
                                val p = Path().apply {
                                    moveTo(center.x, 2f); lineTo(size.width - 4f, size.height - 4f); lineTo(center.x, size.height - 9f); lineTo(4f, size.height - 4f); close()
                                }
                                drawPath(p, Gold)
                            }
                        }
                        LabelIcon(icons, n.labelId, 28.dp)
                        Column(Modifier.weight(1f)) {
                            Text(n.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${n.distance.toInt()} px", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RailButton(TIcons.Found, "Mark ${n.name} as found", { onFound(n.pointId) })
                    }
                }
            }
        }
    }
}

/** Tap on a layer entrance: where it leads, with a button that switches the map to that layer. */
@Composable
private fun EntranceCardView(card: MapViewModel.EntranceCard, onShow: (Int?) -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .padding(10.dp)
            .widthIn(max = 520.dp)
            .background(Chrome.Surface, Chrome.Shape)
            .border(1.dp, Color(0xFF4FC3F7).copy(alpha = 0.8f), Chrome.Shape)
            .padding(start = 16.dp, top = 6.dp, end = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            RailButton(TIcons.Close, "Close", onClose)
        }
        if (card.leadsTo.isEmpty()) Text("No linked layer", color = MaterialTheme.colorScheme.onSurfaceVariant)
        card.leadsTo.forEach { (fid, name) ->
            Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Leads to: $name", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { onShow(fid) }) { Text(if (fid == null) "Show surface" else "Show layer") }
            }
        }
    }
}

internal fun shortName(name: String): String = when {
    name.contains("Teyvat", true) -> "Teyvat"
    name.contains(':') -> name.substringBefore(':').removePrefix("The ").trim().ifEmpty { name }
    else -> name.removeSuffix(" Interactive Map")
}
