package com.teyvatmap.thor.tracking

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.teyvatmap.thor.tracking.cv.Calib
import com.teyvatmap.thor.tracking.cv.CvIndex
import com.teyvatmap.thor.tracking.cv.FrameContext
import com.teyvatmap.thor.tracking.cv.FrameInput
import com.teyvatmap.thor.tracking.cv.LayerIndex
import com.teyvatmap.thor.tracking.cv.Matcher
import com.teyvatmap.thor.tracking.cv.P2
import com.teyvatmap.thor.tracking.cv.RegionZoomTable
import com.teyvatmap.thor.tracking.cv.Screen
import com.teyvatmap.thor.tracking.cv.TrackPose
import com.teyvatmap.thor.tracking.cv.TrackState
import com.teyvatmap.thor.tracking.cv.Tracker
import com.teyvatmap.thor.tracking.cv.TrackerParams
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import com.teyvatmap.thor.tracking.banner.BannerReader
import com.teyvatmap.thor.tracking.cv.BigMapDetector
import com.teyvatmap.thor.tracking.cv.BigMapFix
import com.teyvatmap.thor.tracking.cv.BigMapLocator
import com.teyvatmap.thor.tracking.cv.Floor
import com.teyvatmap.thor.tracking.cv.FloorSelector
import com.teyvatmap.thor.tracking.cv.FloorSelectorRules
import com.teyvatmap.thor.tracking.cv.SelectedFloor
import com.teyvatmap.thor.tracking.cv.MapGeo
import com.teyvatmap.thor.tracking.cv.MapProvider
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Runs the [Tracker] on one dedicated background thread: at every tick it pulls a single frame (minimap region +
 * thumbnail) from the [FrameSource], updates the tracker and schedules the next tick after the tracker's own
 * `nextCheckDelay()` (PLAN §6.2 check rates). All OpenCV state (matcher scratch buffers, templates) lives on that
 * thread, so nothing is allocated per frame beyond the small per-check templates.
 */
class TrackingEngine(
    private val index: CvIndex,
    calib: Calib,
    params: TrackerParams,
    private val source: FrameSource,
    private val prior: P2?,
    zoom: RegionZoomTable = RegionZoomTable.EMPTY,
    /** Tile files of the pack of a map (its geo -> (z, tx, ty) -> file): enables the big-map registration; null disables it. */
    private val tileFileOf: ((MapGeo) -> ((Int, Int, Int) -> File))? = null,
    /** Overlay image of a floor (pack `overlays/{floorId}.png`): lets the big map in layer mode be registered against the floors. */
    private val overlayFileOf: ((Int) -> File)? = null,
    /** Optional area-name banner reader (PLAN §6.0) and the map its boxes / names belong to. */
    private val banner: BannerReader? = null,
    private val bannerMapId: Int = index.geo.mapId,
    /** The other maps (opened lazily): relocation searches them, big-map registration tries them. */
    maps: MapProvider? = null,
    /** Learned big-map selector mapping (tile index -> floor per group); null = kept in memory only. */
    private val selectorMapFile: File? = null,
    private val onPose: (PlayerPose, TrackPose) -> Unit,
    private val onDebug: (DebugSnapshot) -> Unit,
) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tracker").also { it.priority = Thread.NORM_PRIORITY - 1 } }
    private val matcher = Matcher(index, params)
    private val tracker = Tracker(matcher, calib, params, index.layers, index.targets, index.entrances, zoom, maps)
    @Volatile private var paused = false
    @Volatile private var stopped = false
    @Volatile var debugEnabled = false
    private var started = false
    private var next: ScheduledFuture<*>? = null
    private val bigDetector = BigMapDetector()
    private val bigLocators = HashMap<Int, BigMapLocator>()
    private var bigAttempt = 0
    private var lastBigThumb: ByteArray? = null
    private var nextBigTry = 0.0
    private var bigOpen = false
    /** A registration succeeded for the current view: no retry until the view changes (the next one tracks the view). */
    private var bigDone = false
    private var lastSelLog = ""
    private val selectorMap = FloorSelectorMapStore(selectorMapFile)
    /** The latest big-map registration (debug view / tests). */
    @Volatile var lastBigFix: BigMapFix? = null
        private set

    /** The map the tracker is on (follows the lock). */
    val mapId: Int get() = tracker.mapId
    val lastRing: Double get() = tracker.lastRing

    fun start() {
        exec.execute {
            tracker.start(prior)
            started = true
            tick()
        }
    }

    fun setParams(p: TrackerParams) = exec.execute { tracker.params = p }
    fun setCalib(c: Calib) = exec.execute { tracker.calib = c }

    fun pause() {
        paused = true
        onPose(PlayerPose(mapId, state = TrackState.OFF, timeMs = System.currentTimeMillis()), offPose())
    }

    fun resume() {
        if (!paused) return
        paused = false
        exec.execute { tracker.start(tracker.lastPosition) }
    }

    /** Drops the current lock and relocates from scratch (last position, targets of all maps, whole-map scans); safe from any thread. */
    fun retrack() {
        if (stopped) return
        paused = false
        exec.execute { if (!stopped) tracker.start(tracker.lastPosition) }
    }

    private fun offPose() = TrackPose(TrackState.OFF, 0.0, 0.0, 0.0, 0.0, null, 0.0, 0.0, "paused", 0.0, false, null, null)

    fun stop() {
        stopped = true
        exec.execute {
            tracker.close(); matcher.close(); bigLocators.values.forEach { it.close() }; bigLocators.clear(); index.close()
        }
        exec.shutdown()
        runCatching { exec.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun tick() {
        if (stopped) return
        val t0 = SystemClock.elapsedRealtimeNanos()
        var delayMs = 500L
        try {
            if (!paused) {
                val f = source.grab(tracker.calib)
                if (f == null) {
                    delayMs = 300L
                } else {
                    val tSec = SystemClock.elapsedRealtimeNanos() / 1e9
                    tracker.keepHeat = debugEnabled
                    val p = tracker.update(f, tSec)
                    publish(p, f)
                    bigMapStep(f, tSec)?.let { publish(it, f) }
                    bannerStep(tSec)
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                    // a long search slice is followed by the full pause (the check rate is the pause between searches, not their start-to-start time)
                    delayMs = if (elapsedMs > 250) (p.delay * 1000).toLong() else max(20L, (p.delay * 1000 - elapsedMs).toLong())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "tracker tick failed", e)
            delayMs = 1000L
        }
        if (!stopped) runCatching { next = exec.schedule({ tick() }, delayMs, TimeUnit.MILLISECONDS) }
    }

    /**
     * The minimap ring is gone and the screen is not a loading screen: if the bottom control bar of the big map is
     * on screen, register the map to the tiles and fix the position from the player arrow ([TrackState.FIXED]); the waypoint
     * under the cursor pin becomes the teleport-destination hint of the next relocation. Returns the new pose or null.
     */
    private fun bigMapStep(f: FrameInput, tSec: Double): TrackPose? {
        val tileOf = tileFileOf ?: return null
        if (tracker.state == TrackState.LOADING || tracker.state == TrackState.OFF || tracker.lastRing >= tracker.screen.ringThreshold()) return null
        val full = source.grabFullBgr() ?: return null
        try {
            if (!bigDetector.isBigMap(full)) {
                if (bigOpen) {                           // the map was closed: the next opening starts from scratch
                    bigOpen = false; bigDone = false; lastBigThumb = null
                    bigLocators.values.forEach { it.resetView() }
                }
                return null
            }
            bigOpen = true
            val prev = lastBigThumb
            val unchanged = prev != null && thumbDiff(prev, f.thumb) <= 1.5
            if (unchanged && (bigDone || tracker.state == TrackState.FIXED)) return null          // same view, nothing new to register
            if (tSec < nextBigTry) return null
            // the current map first; when it does not match (an underground map, another world) the other maps in turn
            val ids = tracker.mapIds
            val id = ids[bigAttempt % ids.size]
            val geo = tracker.ctxOf(id)?.geo
            val layers = tracker.ctxOf(id)?.layers ?: LayerIndex.EMPTY
            val loc = geo?.let { g -> bigLocators.getOrPut(id) { BigMapLocator(g, tileOf(g), layers, overlayFileOf) } }
            // layer mode (floor overlay over the darkened surface, floor selector on the right): the floors of the map are searched too
            val layerMode = bigDetector.layerSelectorVisible(full)
            val sel = if (layerMode) bigDetector.floorSelector(full) else null
            val fix = loc?.locate(full, tracker.bigMapHints(id), layerMode, sel)
            if (fix == null) {
                bigDone = false
                if (loc?.floorsPending == true) nextBigTry = tSec + 0.3          // more floor groups to try on this map: keep going before the other maps
                else {
                    bigAttempt++
                    nextBigTry = tSec + if (bigAttempt % ids.size == 0) 4.0 else 0.3
                }
            } else {
                bigAttempt = 0
                nextBigTry = tSec
                bigDone = true
            }
            lastBigThumb = f.thumb.copyOf()
            lastBigFix = fix
            if (fix == null) return null
            // the waypoint under the cursor pin is only a hint for the next relocation, whatever the arrow says
            tracker.nearestWaypoint(fix.center, forMap = fix.mapId)?.let { tracker.hintDestination(it, tSec, fix.mapId) }
            // a position needs the player arrow (a panned view without it says nothing about where the player is)
            val player = fix.player
            if (player == null) {
                Log.i(TAG, "big map view of map ${fix.mapId} registered (%s inliers=%d) but no player arrow (%s): hint only".format(fix.how, fix.inliers, fix.arrowNote))
                return null
            }
            val strong = fix.inliers >= STRONG_INLIERS
            if (!tracker.mapFixAcceptable(player, fix.mapId, strong)) {
                Log.i(TAG, "big map fix map ${fix.mapId} (%.0f, %.0f) inliers=%d rejected: too far from the tracked position (%s)".format(fix.xPos, fix.yPos, fix.inliers, tracker.mapFixGateInfo(player, fix.mapId)))
                return null
            }
            // floor: in layer mode the highlighted floor's art is on the screen: the overlay that fits is the floor (the selector is a hint and,
            // once learned, a shortcut); no selector = the surface
            var sf: SelectedFloor? = null
            var pin = false
            if (layerMode && sel != null) {
                val d = decideFloor(full, loc, layers, sel, fix, player)
                sf = d.first; pin = d.second
            }
            Log.i(TAG, "big map fix map ${fix.mapId} %s inliers=%d level=z%d %.0f ms player=(%.1f, %.1f) %s".format(fix.how, fix.inliers, fix.level, fix.ms, fix.xPos, fix.yPos, tracker.mapFixGateInfo(player, fix.mapId)))
            return tracker.mapFix(player, tSec, fix.headingDeg, fix.mapId, sf?.groupId ?: fix.groupId, sf?.floor, surface = !layerMode, pin = pin)
        } finally {
            full.release()
        }
    }

    /**
     * The floor of a layer-mode big-map frame: the candidates are the floors whose overlay holds the player; their art is scored against the
     * screenshot ([BigMapLocator.floorArt]). A confident winner is the floor (and teaches the selector mapping for its group); otherwise the learned
     * mapping decides, else only the group is known. Returns the selection and whether it is authoritative (the tracker pins it).
     */
    private fun decideFloor(full: Mat, loc: BigMapLocator?, layers: LayerIndex, sel: FloorSelector, fix: BigMapFix, player: P2): Pair<SelectedFloor, Boolean> {
        if (sel.index == 0) return SelectedFloor(null, null) to false          // the surface tile is lit: no floor art
        val cands = layers.floorsAt(player.x, player.y, ART_BOUNDS_MARGIN)
        val rules = FloorSelectorRules.resolve(layers, sel, listOf(player, fix.center), fix.groupId)
        val group = cands.map { it.groupId }.distinct().singleOrNull() ?: rules.groupId
        val art = if (loc != null && cands.isNotEmpty()) loc.floorArt(full, fix, cands) else emptyMap()
        val ranked = art.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull()
        val confident = best != null && best.value >= ART_MIN && (ranked.size < 2 || best.value - ranked[1].value >= ART_MARGIN)
        // the art shows the highlighted tile's floor; the player's floor is that one unless the badge sits on another tile
        val viewing = sel.highlighted > 0 && sel.player > 0 && sel.highlighted != sel.player
        var floor: Floor? = null
        var how = "no decision"
        if (confident) {
            val f = layers.byId.getValue(best!!.key)
            if (sel.highlighted > 0 && layers.groups[f.groupId]?.size == sel.n) selectorMap.put(f.groupId, sel.n, sel.highlighted, f.floorId)
            if (!viewing) { floor = f; how = "${f.floorId} (selector idx ${sel.highlighted})" }
        }
        if (floor == null && group != null) {
            val n = layers.groups[group]?.size ?: sel.n
            val learned = selectorMap.get(group, n, sel.index)?.let { layers.byId[it] }
            if (learned != null && (cands.isEmpty() || cands.any { it.floorId == learned.floorId })) { floor = learned; how = "${learned.floorId} (learned idx ${sel.index})" }
        }
        val msg = "BigMap floor art: %s -> %s [%s]".format(art.entries.joinToString(" ") { "${it.key}=%.2f".format(it.value) }.ifEmpty { "none" }, how, sel)
        if (msg != lastSelLog) { lastSelLog = msg; Log.i(TAG, msg) }
        return SelectedFloor(floor?.groupId ?: group, floor) to (floor != null)
    }

    private fun thumbDiff(a: ByteArray, b: ByteArray): Double {
        var s = 0L
        for (i in a.indices) s += Math.abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff))
        return s.toDouble() / a.size
    }

    /** Reads the area-name banner (rate-limited, only while the minimap is on screen) and narrows the next relocation. */
    private fun bannerStep(tSec: Double) {
        val b = banner ?: return
        val src = source
        b.step(tSec, src, tracker.lastRing >= tracker.screen.ringThreshold()) { box: DoubleArray?, anchorId: Int ->
            exec.execute { tracker.hintArea(box, anchorId, bannerMapId) }
        }
    }

    private fun publish(p: TrackPose, f: FrameInput?) {
        val has = tracker.lastPosition != null
        onPose(
            PlayerPose(
                tracker.mapId, p.xPos, p.yPos, p.heading, p.confidence, p.state, p.groupId, p.floorId, has, System.currentTimeMillis(), tracker.curS(),
            ),
            p,
        )
        if (debugEnabled && f != null) onDebug(snapshot(p, f))
    }

    // ------------------------------------------------------------------------------------ debug view
    private fun rgbBitmap(bgr: Mat): Bitmap {
        val rgb = Mat()
        Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB)
        val bmp = Bitmap.createBitmap(rgb.cols(), rgb.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgb, bmp)
        rgb.release()
        return bmp
    }

    private fun infoLines(): String {
        val sb = StringBuilder()
        sb.append("map: ${tracker.mapId} of ${tracker.mapIds}\n")
        val z = tracker.zoom
        val pos = tracker.lastPosition
        val g = pos?.let { tracker.geo.toPos(it.x, it.y) }
        val reg = g?.let { z.regionAt(it.x, it.y) }
        sb.append("region: ${reg?.name ?: "-"} (#${reg?.id ?: "-"}) zoom table ${reg?.let { z.entry(it.id)?.let { e -> "%.3f x%d".format(e.scale, e.samples) } } ?: "unknown"}\n")
        sb.append("table: ${z.all().joinToString { "#${it.anchorId}=%.3f/%d".format(it.scale, it.samples) }}\n")
        lastBigFix?.let { sb.append("big map: %s inl=%d z0/px=%.2f %.0f ms player=%s\n".format(it.how, it.inliers, it.z0PerPx, it.ms, it.xPos?.let { x -> "(%.1f, %.1f)".format(x, it.yPos) } ?: "-")) }
        banner?.let { b -> sb.append("banner: reads=${b.reads} skipped=${b.skippedByPrecheck} last=${b.last?.lines?.take(2)} -> ${b.last?.match?.name ?: "-"}\n") }
        return sb.toString().trimEnd()
    }

    private fun snapshot(p: TrackPose, f: FrameInput): DebugSnapshot {
        val ctx = tracker.lastContext
        var crop: Bitmap? = null
        var mask: Bitmap? = null
        var heat: Bitmap? = null
        if (ctx != null) {
            val params = tracker.params
            crop = rgbBitmap(ctx.crop)
            // template mask (what the matcher actually correlates): circle ring minus icons, upscaled to the crop
            val z = matcher.localLevel(tracker.calib.s)
            val tm = ctx.template(z, tracker.calib.s)
            val m8 = Mat(); val big = Mat(); val col = Mat()
            tm.mask.convertTo(m8, CvType.CV_8U, 255.0)
            Imgproc.resize(m8, big, Size(ctx.crop.cols().toDouble(), ctx.crop.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            val g = Mat(); Imgproc.cvtColor(ctx.crop, g, Imgproc.COLOR_BGR2GRAY)
            val dim = Mat(); g.convertTo(dim, CvType.CV_8U, 0.35)
            val outG = Mat(); Core.max(dim, big, outG)
            Imgproc.cvtColor(outG, col, Imgproc.COLOR_GRAY2BGR)
            mask = rgbBitmap(col)
            listOf(m8, big, col, g, dim, outG).forEach { it.release() }
        }
        tracker.lastHeat?.let { h ->
            val u = Mat(); val big = Mat(); val cm = Mat()
            h.convertTo(u, CvType.CV_8U, 255.0 / 1.2, 255.0 * 0.2 / 1.2)
            val scale = max(1.0, 160.0 / max(h.cols(), h.rows()))
            Imgproc.resize(u, big, Size(), scale, scale, Imgproc.INTER_NEAREST)
            Imgproc.applyColorMap(big, cm, Imgproc.COLORMAP_JET)
            heat = rgbBitmap(cm)
            listOf(u, big, cm).forEach { it.release() }
        }
        val screen = Screen(tracker.params)
        val (med, frac) = screen.loadingStats(f.thumb)
        val st = tracker.stats
        return DebugSnapshot(
            System.currentTimeMillis(), p.state, p.mode, p.score, tracker.lastMargin, p.confidence, p.delay, p.ms, matcher.times.take(),
            screen.ringValue(f, tracker.calib), med, frac, tracker.lastLayerTrigger, tracker.curS(), tracker.calib.s, tracker.lastScaleScores, p.floorId,
            linkedMapOf(
                "checks" to st.checks, "cheap" to st.cheap, "local" to st.local, "target" to st.target, "full" to st.full,
                "layer" to st.layerChecks, "cpu ms" to st.cpuMs.toLong(),
            ),
            crop, mask, heat, infoLines(),
        )
    }

    companion object {
        const val TAG = "TrackingEngine"
        /** Registrations with at least this many inliers and the player arrow override the jump gate. */
        const val STRONG_INLIERS = 30
        /** Big-map floor art: a floor wins with at least this NCC and this lead over the runner-up; overlay bounds margin (zoom-0 px) for the candidates. */
        const val ART_MIN = 0.25
        const val ART_MARGIN = 0.04
        const val ART_BOUNDS_MARGIN = 8.0
    }
}
