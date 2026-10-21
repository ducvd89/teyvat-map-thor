package com.teyvatmap.thor.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teyvatmap.thor.data.db.FloorEntity
import com.teyvatmap.thor.sync.SyncPhase
import com.teyvatmap.thor.ui.AppViewModel
import com.teyvatmap.thor.ui.point.PointSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ovh.plrapps.mapcompose.ui.MapUI

@Composable
fun MapScreen(
    appVm: AppViewModel,
    canMoveToOtherScreen: Boolean,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenProgress: () -> Unit,
    onMoveToOtherScreen: () -> Unit,
    onOpenCalibration: () -> Unit = {},
    onOpenDebug: () -> Unit = {},
    /** Starts screen capture (MediaProjection consent); supplied by the host because the consent needs an Activity. */
    startCapture: () -> Unit,
    /** Overlay host only: return to the normal app window. */
    onOpenApp: (() -> Unit)? = null,
    /** Activity host only: show the map as an overlay on the bottom screen (game mode). */
    overlay: com.teyvatmap.thor.dualscreen.OverlayControls? = null,
    vm: MapViewModel = hiltViewModel(),
) {
    val map by vm.currentMap.collectAsStateWithLifecycle()
    val maps by vm.maps.collectAsStateWithLifecycle()
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val floors by vm.floors.collectAsStateWithLifecycle()
    val activeFloorId by vm.activeFloorId.collectAsStateWithLifecycle()
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    val settings by appVm.settings.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val focusReq by vm.focusRequest.collectAsStateWithLifecycle()
    val marks by vm.markSnapshot.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val pinEditor by vm.pinEditor.collectAsStateWithLifecycle()
    val sheetMark by vm.sheetMark.collectAsStateWithLifecycle()
    val respawn by vm.respawnHours.collectAsStateWithLifecycle()
    val syncState by appVm.syncState.collectAsStateWithLifecycle()
    val pose by vm.pose.collectAsStateWithLifecycle()
    val track by vm.trackingStatus.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    val recenterZoom by vm.recenterZoom.collectAsStateWithLifecycle()
    val floorAuto by vm.floorAuto.collectAsStateWithLifecycle()
    val floorNav by vm.floorNav.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val entrance by vm.entrance.collectAsStateWithLifecycle()
    val calibration by vm.tracking.store.calib.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var panel by rememberSaveable { mutableStateOf(MapPanel.NONE) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val m = map
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (m != null) {
            val ctrl = remember(m.id, m.version) {
                MapController(
                    m, vm.tileStore, vm.repository, vm.icons, scope,
                    onPointClick = { id -> vm.selectPoint(id) },
                    onPointLongPress = { id -> vm.toggleFound(id) },
                    onPinClick = { uuid -> vm.editPin(uuid) },
                    onMapLongPress = { x, y -> vm.beginPin(x, y) },
                    onEntranceClick = { id -> vm.selectEntrance(id) },
                )
            }
            DisposableEffect(ctrl) { onDispose { ctrl.shutdown() } }

            MapUI(Modifier.fillMaxSize().detectUserPan { vm.following.value = false }, state = ctrl.state)

            // Player arrow + follow mode
            LaunchedEffect(ctrl, pose, track.tracking) { ctrl.updatePlayer(pose.takeIf { track.tracking || it.hasPosition }, dimmed = !track.tracking) }
            LaunchedEffect(ctrl, pose.xPos, pose.yPos, following, pose.state, track.tracking, recenterZoom) {
                if (following && track.tracking && pose.hasPosition && pose.mapId == m.id && (pose.state == com.teyvatmap.thor.tracking.cv.TrackState.TRACKING || pose.state == com.teyvatmap.thor.tracking.cv.TrackState.FIXED)) {
                    ctrl.followPlayer(pose.xPos, pose.yPos, zoomIn = recenterZoom)
                    if (recenterZoom) vm.recenterZoom.value = false
                }
            }

            LaunchedEffect(ctrl, marks) { ctrl.updateMarks(marks) }
            LaunchedEffect(ctrl, enabled, settings.hideFound, settings.showHidden, activeFloorId) {
                ctrl.syncMarkers(enabled, settings.hideFound, settings.showHidden, activeFloorId)
            }
            LaunchedEffect(ctrl, pins, activeFloorId) { ctrl.syncPins(pins, activeFloorId) }

            // Selected point ring.
            val shown = sheet as? SheetState.Shown
            LaunchedEffect(ctrl, shown?.info?.point?.id) {
                ctrl.showSelection(shown?.info?.point?.x, shown?.info?.point?.y)
            }

            // Underground overlay + recentre when a floor is chosen.
            val floor = floors.firstOrNull { it.id == activeFloorId }
            val overlayFile by produceState<java.io.File?>(null, floor?.id) {
                value = floor?.let { vm.repository.overlayFile(it.id) }
            }
            LaunchedEffect(ctrl, floor?.id, overlayFile) {
                ctrl.showFloorOverlay(floor, overlayFile)
            }
            LaunchedEffect(ctrl, floor?.id) {
                if (floor != null && vm.shouldRecenterOnFloor()) ctrl.focusOn((floor.lX + floor.rX) / 2, (floor.lY + floor.rY) / 2)
            }

            LaunchedEffect(ctrl, focusReq) {
                val r = focusReq
                if (r != null && r.mapId == m.id) {
                    ctrl.focusOn(r.x, r.y)
                    vm.consumeFocus(r.nonce)
                }
            }
        }

        // ------------------------------------------------------------ controls above the map
        val chromeState = ChromeState(
            maps = maps.map { MapChoice(it.id, it.name) }, currentMapId = m?.id,
            query = query, results = results, searchOpen = searchOpen, panel = panel,
            tree = tree, enabled = enabled, hideFound = settings.hideFound, showHidden = settings.showHidden,
            floors = floors, activeFloorId = activeFloorId, floorAuto = floorAuto, floorNav = floorNav,
            track = track, trackState = pose.state, hasPosition = pose.hasPosition, following = following,
            calibrated = calibration != null, sync = syncState,
            gameModeAvailable = overlay != null && overlay.available, hasOpenApp = onOpenApp != null,
            canMoveToOtherScreen = canMoveToOtherScreen,
            nearby = nearby, entrance = entrance, undo = undo,
            sheet = sheet as? SheetState.Shown, sheetMark = sheetMark, respawnHours = (sheet as? SheetState.Shown)?.info?.point?.labelId?.let { respawn[it] } ?: 0,
        )
        val chromeActions = run {
            ChromeActions(
                onPanel = { p -> panel = p; vm.nearbyOpen.value = p == MapPanel.NEARBY },
                onSearchOpen = { searchOpen = it },
                onQuery = { vm.query.value = it },
                onSearchFocus = { appVm.searchFocused.value = it },
                onPickLabel = { vm.pickLabel(it); focusManager.clearFocus() },
                onPickPoint = { vm.pickPoint(it); focusManager.clearFocus() },
                onPickMap = vm::userSelectMap,
                onToggleLeaf = vm::toggleLabel,
                onSetGroup = { ids, on -> vm.setLabels(ids, on) },
                onClearLabels = vm::clearLabels,
                onBulkFound = vm::bulkSetFound,
                onHideFound = appVm::setHideFound,
                onShowHidden = appVm::setShowHidden,
                onPickFloor = vm::userSetFloor,
                onFloorStep = vm::stepFloor,
                onFollowToggle = { vm.following.value = !vm.following.value },
                onRecenter = vm::recenter,
                onRetrack = vm::retrack,
                onNearbyFound = vm::toggleFound,
                onTrackingClick = {
                    when {
                        // Track off/on only pauses/resumes: the projection and VirtualDisplay stay (see CapturePolicy)
                        vm.trackingStatus.value.tracking -> vm.tracking.togglePause()
                        vm.trackingStatus.value.capturing -> vm.tracking.stop()
                        vm.tracking.store.calib.value == null -> onOpenCalibration()
                        else -> startCapture()
                    }
                },
                onSyncClick = { if (appVm.syncState.value.phase == SyncPhase.NEEDS_SIGN_IN) onOpenSettings() else appVm.syncNow() },
                onGameMode = { overlay?.start?.invoke() },
                onOpenApp = { onOpenApp?.invoke() },
                onOpenProgress = onOpenProgress,
                onOpenSettings = onOpenSettings,
                onOpenCalibration = onOpenCalibration,
                onOpenDebug = onOpenDebug,
                onOpenDiagnostics = onOpenDiagnostics,
                onMoveToOtherScreen = onMoveToOtherScreen,
                onTogglePause = { vm.tracking.togglePause() },
                onStopCapture = { vm.tracking.stop() },
                onEntranceShow = { fid -> vm.userSetFloor(fid); vm.selectEntrance(null) },
                onEntranceClose = { vm.selectEntrance(null) },
                onUndo = vm::undo,
                onUndoTimeout = vm::dismissUndo,
                onSheetFound = { (vm.sheet.value as? SheetState.Shown)?.let { vm.toggleFound(it.info.point.id) } },
                onSheetHide = { (vm.sheet.value as? SheetState.Shown)?.let { vm.toggleHidden(it.info.point.id) } },
                onSheetNote = { n -> (vm.sheet.value as? SheetState.Shown)?.let { vm.setNote(it.info.point.id, n) } },
                onTextFocus = { appVm.searchFocused.value = it },
                onSheetClose = { vm.selectPoint(null) },
            )
        }
        MapChrome(chromeState, chromeActions, vm.icons)

        pinEditor?.let { d ->
            PinEditor(
                d, onSave = vm::savePin, onDelete = vm::deletePin, onCancel = vm::closePinEditor,
                onTextFocus = { appVm.searchFocused.value = it },
            )
        }
    }

    BackHandler(enabled = sheet !is SheetState.Hidden || panel != MapPanel.NONE || query.isNotEmpty() || searchOpen || pinEditor != null || entrance != null) {
        when {
            entrance != null -> vm.selectEntrance(null)
            pinEditor != null -> vm.closePinEditor()
            panel != MapPanel.NONE -> { panel = MapPanel.NONE; vm.nearbyOpen.value = false }
            sheet !is SheetState.Hidden -> vm.selectPoint(null)
            else -> { vm.query.value = ""; searchOpen = false; focusManager.clearFocus() }
        }
    }
}



// ---------------------------------------------------------------------------------------- live position UI
/** Calls [onPan] when a single finger drags the map (follow mode ends). Never consumes events. */
private fun Modifier.detectUserPan(onPan: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        var down: androidx.compose.ui.geometry.Offset? = null
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val c = e.changes
            if (c.size == 1) {
                val ch = c[0]
                if (ch.pressed && down == null) down = ch.position
                if (!ch.pressed) down = null
                val d = down
                if (d != null && (ch.position - d).getDistance() > viewConfiguration.touchSlop * 1.5f) { onPan(); down = null }
            } else if (c.size > 1) down = null
        }
    }
}