package com.teyvatmap.thor.ui.map

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.alpha
import com.teyvatmap.thor.data.MarkSnapshot
import com.teyvatmap.thor.data.db.CustomPinEntity
import com.teyvatmap.thor.domain.MarkState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ovh.plrapps.mapcompose.api.onLongPress
import ovh.plrapps.mapcompose.api.onMarkerLongPress
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teyvatmap.thor.data.MapInfo
import com.teyvatmap.thor.data.MapRepository
import com.teyvatmap.thor.data.db.FloorEntity
import com.teyvatmap.thor.data.store.TileStore
import com.teyvatmap.thor.domain.MapTransform
import com.teyvatmap.thor.ui.theme.Gold
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import ovh.plrapps.mapcompose.api.addClusterer
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.addMarker
import ovh.plrapps.mapcompose.api.addLayer
import ovh.plrapps.mapcompose.api.removeLayer
import ovh.plrapps.mapcompose.api.setLayerOpacity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.pow
import androidx.compose.ui.graphics.drawscope.rotate
import ovh.plrapps.mapcompose.api.hasMarker
import ovh.plrapps.mapcompose.api.moveMarker
import ovh.plrapps.mapcompose.api.maxScale
import ovh.plrapps.mapcompose.api.onMarkerClick
import ovh.plrapps.mapcompose.api.referentialSnapshotFlow
import ovh.plrapps.mapcompose.api.removeMarker
import ovh.plrapps.mapcompose.api.scale
import ovh.plrapps.mapcompose.api.scrollTo
import ovh.plrapps.mapcompose.api.setMapBackground
import ovh.plrapps.mapcompose.api.visibleBoundingBox
import ovh.plrapps.mapcompose.core.TileStreamProvider
import ovh.plrapps.mapcompose.ui.state.MapState
import ovh.plrapps.mapcompose.ui.state.markers.model.RenderingStrategy

/**
 * Owns the MapCompose [MapState] of one map: the local tile layer, the clusterer and the marker synchronisation
 * with Room (viewport-driven: only points near the visible area become markers; when too many would be shown, SQL
 * grid clusters are shown instead).
 */
class MapController(
    val info: MapInfo,
    tileStore: TileStore,
    private val repo: MapRepository,
    private val icons: IconCache,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onPointClick: (Long) -> Unit,
    private val onPointLongPress: (Long) -> Unit,
    private val onPinClick: (String) -> Unit,
    private val onMapLongPress: (xPos: Double, yPos: Double) -> Unit,
    private val onEntranceClick: (pointId: Long) -> Unit = {},
) {
    val transform = MapTransform(info.geometry)
    private val geom = info.geometry

    val state: MapState = MapState(
        levelCount = geom.levelCount,
        fullWidth = transform.fullWidth,
        fullHeight = transform.fullHeight,
        tileSize = geom.tileSize,
        workerCount = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6),
    ) {
        maxScale(MAX_SCALE)
        if (info.id == 2) {
            // Teyvat: start around game origin, zoomed out.
            val c = transform.toNormalized(0.0, 0.0)
            scale(0.12).scroll(c.x, c.y)
        } else {
            scale(0.0)
        }
    }

    private val pointMarkers = HashMap<String, Int>() // marker id -> label id
    private val clusterMarkers = HashSet<String>()
    private val entranceMarkers = HashSet<String>()
    private val pinMarkers = HashMap<String, CustomPinEntity>() // marker id -> pin as drawn

    /** Found / hidden ids; marker composables read it, so a mark change repaints without re-adding markers. */
    private val marks = mutableStateOf(MarkSnapshot())
    fun updateMarks(s: MarkSnapshot) { marks.value = s }

    private var baseLayerId: String = ""
    private var floorLayerId: String? = null
    private var floorDecoder: BitmapRegionDecoder? = null

    init {
        baseLayerId = state.addLayer(
            TileStreamProvider { row, col, level ->
                tileStore.open(info.id, info.version, transform.zoomOfLevel(level), col, row)
            },
        )
        // Blank (transparent) tiles are open sea; HoYoLAB's web map shows its sea colour there.
        state.setMapBackground(SEA_COLOR)
        state.addClusterer(CLUSTERER, clusteringThreshold = 34.dp) { ids -> { ClusterBubble(ids.size) } }
        state.onMarkerClick { id, x, y ->
            when {
                id.startsWith("p:") -> onPointClick(id.substring(2).toLong())
                id.startsWith("u:") -> onPinClick(id.substring(2))
                id.startsWith("e:") -> onEntranceClick(id.split(':')[1].toLong())
                id.startsWith("c:") -> scope.launch { state.scrollTo(x, y, destScale = state.scale * 3.0) }
                else -> Unit
            }
        }
        state.onMarkerLongPress { id, _, _ ->
            if (id.startsWith("p:")) onPointLongPress(id.substring(2).toLong())
            else if (id.startsWith("u:")) onPinClick(id.substring(2))
        }
        state.onLongPress { nx, ny ->
            val g = transform.fromNormalized(nx, ny)
            onMapLongPress(g.x, g.y)
        }
    }

    fun shutdown() { floorDecoder?.let { runCatching { it.recycle() } }; floorDecoder = null; state.shutdown() }

    // -------------------------------------------------------- focus / select
    suspend fun focusOn(xPos: Double, yPos: Double) {
        val n = transform.toNormalized(xPos, yPos)
        state.scrollTo(n.x, n.y, destScale = maxOf(state.scale, 0.6))
    }

    fun showSelection(xPos: Double?, yPos: Double?) {
        if (state.hasMarker(SELECTION)) state.removeMarker(SELECTION)
        if (xPos == null || yPos == null) return
        val n = transform.toNormalized(xPos, yPos)
        state.addMarker(
            SELECTION, n.x, n.y, relativeOffset = Offset(-0.5f, -0.5f), zIndex = 10f, clickable = false,
        ) {
            Box(Modifier.size(46.dp).border(3.dp, Gold, CircleShape))
        }
    }

    /**
     * Shows (or removes) the underground floor overlay as a tile layer above the base tiles: each 256-px tile is cut
     * from the overlay PNG with a [BitmapRegionDecoder] (the image is never held whole in memory). The surface is dimmed
     * while a floor is shown.
     */
    fun showFloorOverlay(floor: FloorEntity?, file: File?) {
        floorLayerId?.let { state.removeLayer(it) }
        floorLayerId = null
        floorDecoder?.let { d -> floorDecoder = null; runCatching { d.recycle() } }
        if (floor == null || file == null || !file.exists()) { state.setLayerOpacity(baseLayerId, 1f); return }
        val decoder = try { BitmapRegionDecoder.newInstance(file.path, false) } catch (_: Exception) { null }
        if (decoder == null) { state.setLayerOpacity(baseLayerId, 1f); return }
        floorDecoder = decoder
        val g = geom
        val ox0 = minOf(floor.lX, floor.rX) + g.originX
        val ox1 = maxOf(floor.lX, floor.rX) + g.originX
        val oy0 = minOf(floor.lY, floor.rY) + g.originY
        val oy1 = maxOf(floor.lY, floor.rY) + g.originY
        val imgW = decoder.width
        val imgH = decoder.height
        val sx = imgW / (ox1 - ox0)   // image px per zoom-0 px
        val sy = imgH / (oy1 - oy0)
        val ts = g.tileSize
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val provider = TileStreamProvider { row, col, level ->
            try {
                val z = transform.zoomOfLevel(level)
                val k = 2.0.pow(z)
                val tx0 = col * ts / k; val tx1 = tx0 + ts / k
                val ty0 = row * ts / k; val ty1 = ty0 + ts / k
                val ix0 = maxOf(tx0, ox0); val ix1 = minOf(tx1, ox1)
                val iy0 = maxOf(ty0, oy0); val iy1 = minOf(ty1, oy1)
                if (ix1 <= ix0 || iy1 <= iy0) return@TileStreamProvider null
                val src = Rect(
                    ((ix0 - ox0) * sx).toInt().coerceIn(0, imgW - 1), ((iy0 - oy0) * sy).toInt().coerceIn(0, imgH - 1),
                    ceil((ix1 - ox0) * sx).toInt().coerceIn(1, imgW), ceil((iy1 - oy0) * sy).toInt().coerceIn(1, imgH),
                )
                if (src.width() <= 0 || src.height() <= 0) return@TileStreamProvider null
                val dst = RectF(
                    ((ix0 - tx0) * k).toFloat(), ((iy0 - ty0) * k).toFloat(), ((ix1 - tx0) * k).toFloat(), ((iy1 - ty0) * k).toFloat(),
                )
                var sample = 1
                while (src.width() / (sample * 2f) >= dst.width() && src.height() / (sample * 2f) >= dst.height()) sample *= 2
                val part = decoder.decodeRegion(src, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@TileStreamProvider null
                val tile = Bitmap.createBitmap(ts, ts, Bitmap.Config.ARGB_8888)
                Canvas(tile).drawBitmap(part, null, dst, paint)
                part.recycle()
                val out = ByteArrayOutputStream(32 * 1024)
                tile.compress(Bitmap.CompressFormat.PNG, 100, out)
                tile.recycle()
                ByteArrayInputStream(out.toByteArray())
            } catch (_: Exception) {
                null
            }
        }
        floorLayerId = state.addLayer(provider)
        state.setLayerOpacity(baseLayerId, 0.55f)
    }

    // --------------------------------------------------------- marker sync
    /**
     * Keeps markers in sync with the visible area. Cancelled and restarted by the caller whenever the enabled
     * categories, the hide-found option or the active floor change.
     */
    @OptIn(FlowPreview::class)
    suspend fun syncMarkers(enabled: Set<Int>, hideFound: Boolean, showHidden: Boolean, floorId: Int?) {
        clearAllDataMarkers()
        // Re-query when the viewport settles and when marks change (a found point has to vanish under "hide found").
        combine(state.referentialSnapshotFlow().debounce(140), snapshotFlow { marks.value }.map { it.found.size to it.hidden.size }.distinctUntilChanged()) { _, _ -> }
            .collectLatest { sync(enabled, hideFound, showHidden, floorId) }
    }

    /** Draws the custom pins of this map/floor. Pins are never clustered. */
    fun syncPins(pins: List<CustomPinEntity>, floorId: Int?) {
        val desired = pins.filter { it.floorId == floorId }.associateBy { "u:${it.uuid}" }
        for ((id, old) in pinMarkers.entries.toList()) {
            if (desired[id] != old) { state.removeMarker(id); pinMarkers.remove(id) }
        }
        for ((id, p) in desired) {
            if (id in pinMarkers) continue
            val n = transform.toNormalized(p.x, p.y)
            state.addMarker(id, n.x, n.y, relativeOffset = Offset(-0.5f, -1f), zIndex = 5f) { PinMarker(p) }
            pinMarkers[id] = p
        }
    }

    private fun clearAllDataMarkers() {
        pointMarkers.keys.forEach { state.removeMarker(it) }
        pointMarkers.clear()
        clusterMarkers.forEach { state.removeMarker(it) }
        clusterMarkers.clear()
        entranceMarkers.forEach { state.removeMarker(it) }
        entranceMarkers.clear()
    }

    private suspend fun sync(enabled: Set<Int>, hideFound: Boolean, showHidden: Boolean, floorId: Int?) {
        val bb = state.visibleBoundingBox()
        val w = bb.xRight - bb.xLeft
        val h = bb.yBottom - bb.yTop
        val tl = transform.fromNormalized((bb.xLeft - w * 0.4).coerceAtLeast(0.0), (bb.yTop - h * 0.4).coerceAtLeast(0.0))
        val br = transform.fromNormalized((bb.xRight + w * 0.4).coerceAtMost(1.0), (bb.yBottom + h * 0.4).coerceAtMost(1.0))
        val (minX, maxX) = minOf(tl.x, br.x) to maxOf(tl.x, br.x)
        val (minY, maxY) = minOf(tl.y, br.y) to maxOf(tl.y, br.y)

        val count = repo.countInBounds(info.id, enabled, minX, maxX, minY, maxY, floorId, hideFound, showHidden)
        if (count <= MAX_POINT_MARKERS) {
            val pts = repo.pointsInBounds(info.id, enabled, minX, maxX, minY, maxY, floorId, hideFound, showHidden, MAX_POINT_MARKERS)
            pts.map { it.labelId }.toSet().forEach { icons.get(it) } // preload off the composition path
            // drop cluster markers, then diff the point markers
            clusterMarkers.forEach { state.removeMarker(it) }
            clusterMarkers.clear()
            val desired = pts.associateBy { "p:${it.id}" }
            val stale = pointMarkers.keys.filter { it !in desired }
            stale.forEach { state.removeMarker(it); pointMarkers.remove(it) }
            for ((id, p) in desired) {
                if (id in pointMarkers) continue
                val n = transform.toNormalized(p.x, p.y)
                val labelId = p.labelId
                val pid = p.id
                state.addMarker(
                    id, n.x, n.y, relativeOffset = Offset(-0.5f, -0.5f),
                    renderingStrategy = RenderingStrategy.Clustering(CLUSTERER),
                ) { PointPin(icons, labelId, marks.value.stateOf(pid)) }
                pointMarkers[id] = labelId
            }
        } else {
            pointMarkers.keys.forEach { state.removeMarker(it) }
            pointMarkers.clear()
            val cell = maxOf(maxX - minX, maxY - minY) / 9.0
            val rows = repo.clusters(info.id, enabled, minX, maxX, minY, maxY, floorId, hideFound, showHidden, cell)
            val desired = rows.associateBy { "c:${it.firstId}:${it.n}" }
            clusterMarkers.filter { it !in desired }.forEach { state.removeMarker(it); clusterMarkers.remove(it) }
            for ((id, r) in desired) {
                if (id in clusterMarkers) continue
                val n = transform.toNormalized(r.x, r.y)
                val total = r.n
                state.addMarker(id, n.x, n.y, relativeOffset = Offset(-0.5f, -0.5f), clickable = true) {
                    ClusterBubble(total)
                }
                clusterMarkers += id
            }
        }
        syncEntrances(minX, maxX, minY, maxY, floorId)
    }

    // ------------------------------------------------------------ layer entrances / player
    /** Entrance points get their own marker (ring + chevron): a tap shows where they lead (PLAN §6.2). */
    private suspend fun syncEntrances(minX: Double, maxX: Double, minY: Double, maxY: Double, floorId: Int?) {
        val rows = repo.entrancesInBounds(info.id, minX, maxX, minY, maxY, floorId)
        if (rows.size > MAX_ENTRANCE_MARKERS) {
            entranceMarkers.forEach { state.removeMarker(it) }
            entranceMarkers.clear()
            return
        }
        val desired = rows.associateBy { "e:${it.pointId}:${it.floorId}" }
        entranceMarkers.filter { it !in desired }.forEach { state.removeMarker(it); entranceMarkers.remove(it) }
        for ((id, r) in desired) {
            if (id in entranceMarkers) continue
            val n = transform.toNormalized(r.x, r.y)
            val down = floorId == null
            state.addMarker(id, n.x, n.y, relativeOffset = Offset(-0.5f, -0.5f), zIndex = 6f) { EntranceMarker(down) }
            entranceMarkers += id
        }
    }

    private val playerUi = mutableStateOf(PlayerUi())
    private var playerAdded = false

    /** Moves / restyles the player arrow. [pose] null removes it. */
    fun updatePlayer(pose: com.teyvatmap.thor.tracking.PlayerPose?, dimmed: Boolean) {
        if (pose == null || !pose.hasPosition || pose.mapId != info.id) {
            if (playerAdded) { state.removeMarker(PLAYER); playerAdded = false }
            return
        }
        playerUi.value = PlayerUi(pose.headingDeg, pose.confidence, (pose.state == com.teyvatmap.thor.tracking.cv.TrackState.TRACKING || pose.state == com.teyvatmap.thor.tracking.cv.TrackState.FIXED) && !dimmed)
        val n = transform.toNormalized(pose.xPos, pose.yPos)
        if (!playerAdded) {
            state.addMarker(PLAYER, n.x, n.y, relativeOffset = Offset(-0.5f, -0.5f), zIndex = 20f, clickable = false) { PlayerMarker(playerUi.value) }
            playerAdded = true
        } else {
            state.moveMarker(PLAYER, n.x, n.y)
        }
    }

    /** Keeps the player in the centre (follow mode); [zoomIn] (re-centre button) also zooms all the way in. */
    suspend fun followPlayer(xPos: Double, yPos: Double, zoomIn: Boolean = false) {
        val n = transform.toNormalized(xPos, yPos)
        state.scrollTo(n.x, n.y, destScale = if (zoomIn) MAX_SCALE else maxOf(state.scale, 0.45))
    }

    companion object {
        const val MAX_SCALE = 2.0
        const val PLAYER = "player"
        const val MAX_ENTRANCE_MARKERS = 160
        const val CLUSTERER = "points"
        const val SELECTION = "selection"
        const val FLOOR_OVERLAY = "floor_overlay"
        const val MAX_POINT_MARKERS = 1400
        val SEA_COLOR = Color(0xFF10171F)
        val FoundGreen = Color(0xFF7BD88F)
    }
}

@Composable
private fun PointPin(icons: IconCache, labelId: Int, mark: MarkState) {
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(30.dp)
                .alpha(if (mark == MarkState.NONE) 1f else 0.4f)
                .clip(CircleShape)
                .background(Color(0xCC000000))
                .border(1.dp, Color(0x88FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            LabelIcon(icons, labelId, 24.dp)
        }
        // Found = faded icon only; hidden keeps a small grey \u2715 badge.
        if (mark == MarkState.HIDDEN) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(15.dp).clip(CircleShape).background(Color(0xFF777777)),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u2715", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PinMarker(pin: CustomPinEntity) {
    val c = Color(pin.color)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(c).border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("\u2605", color = Color.White, fontSize = 14.sp) }
        if (pin.title.isNotBlank()) {
            Text(
                pin.title, color = Color.White, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.widthIn(max = 120.dp).background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
fun ClusterBubble(count: Int) {
    Box(
        Modifier
            .size(if (count >= 1000) 48.dp else 40.dp)
            .clip(CircleShape)
            .background(Gold.copy(alpha = 0.92f))
            .border(2.dp, Color(0xFF241A00), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count >= 10_000) "${count / 1000}k" else count.toString(),
            color = Color(0xFF241A00),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** What the player arrow shows. */
data class PlayerUi(val heading: Double? = null, val confidence: Double = 0.0, val active: Boolean = false)

/** Small gold dot with a white rim at the position (no heading: the detected facing isn't reliable); grey while stale. */
@Composable
fun PlayerMarker(ui: PlayerUi) {
    val color = if (ui.active) Gold else Color(0xFF9A9A9A)
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        drawCircle(Color(0x66000000), 7.5.dp.toPx(), center)
        drawCircle(Color.White, 6.dp.toPx(), center)
        drawCircle(color, 4.5.dp.toPx(), center)
    }
}

/** Layer entrance: a cyan ring with a chevron (down = leads underground, up = leads back to the surface). */
@Composable
fun EntranceMarker(down: Boolean) {
    val c = Color(0xFF4FC3F7)
    androidx.compose.foundation.Canvas(Modifier.size(46.dp)) {
        drawCircle(Color(0x99000000), radius = 21.dp.toPx(), center = center)
        drawCircle(c, radius = 21.dp.toPx(), center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 8f))))
        val s = if (down) 1f else -1f
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(center.x - 9.dp.toPx(), center.y - 5.dp.toPx() * s)
            lineTo(center.x, center.y + 5.dp.toPx() * s)
            lineTo(center.x + 9.dp.toPx(), center.y - 5.dp.toPx() * s)
        }
        drawPath(p, c, style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}
