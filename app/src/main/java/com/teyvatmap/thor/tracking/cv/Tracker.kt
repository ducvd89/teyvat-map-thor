package com.teyvatmap.thor.tracking.cv

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** [FIXED]: the position comes from the open big map (exact), the minimap is hidden; the minimap takes over when it returns. */
enum class TrackState { TRACKING, LOADING, RELOCATING, DOMAIN, HIDDEN, LOST, OFF, FIXED }

/** Result of one tracker check. */
class TrackPose(
    val state: TrackState,
    val px0: Double,
    val py0: Double,
    val xPos: Double,
    val yPos: Double,
    val heading: Double?,
    val confidence: Double,
    val score: Double,
    /** local / still / coast / flat / confirm / last / targets / full / features / none */
    val mode: String,
    var ms: Double = 0.0,
    val accepted: Boolean,
    val groupId: Int?,
    val floorId: Int?,
    val path: String = "",
    var delay: Double = 1.0,
    /** Current minimap scale (minimap px per zoom-0 map px) of the zoom ladder. */
    val scale: Double = 0.0,
)

class TrackStats {
    var checks = 0
    var full = 0
    var target = 0
    var local = 0
    var layerChecks = 0
    var cheap = 0
    var cpuMs = 0.0
    var cheapMs = 0.0
    val byState = HashMap<TrackState, Int>()
}

/**
 * The PLAN §6.2 state machine (TRACKING / LOADING / RELOCATING / DOMAIN / HIDDEN / LOST / OFF), a line-by-line port
 * of `tools/cv/track.py` plus two improvements (marked IMPROVEMENT): relocation step 2 also scores the floor
 * overlays of targets that lie inside a layer zone, and step 1 also tries the last layer. Drive it with
 * `update(frame, t)` every `nextCheckDelay()` seconds. Not thread-safe: use one thread.
 */
class Tracker(
    matcher: Matcher,
    var calib: Calib,
    params: TrackerParams,
    layers: LayerIndex,
    targets: Targets,
    entrances: EntranceIndex = EntranceIndex.EMPTY,
    /** Minimap zoom per region (PLAN §6.0) of the primary map; the scale prior of every search. */
    zoom: RegionZoomTable = RegionZoomTable.EMPTY,
    /** The other maps (opened lazily): RELOCATING / LOST search them too, the current map follows the lock. */
    private val maps: MapProvider? = null,
) {
    var params: TrackerParams = params
        set(v) { field = v; primary.matcher.params = v; loaded.values.forEach { it?.matcher?.params = v }; screen.params = v }
    val screen = Screen(params)
    val stats = TrackStats()

    // ---- maps: tracking is local to [cur]; the other contexts are loaded on first use
    private val primary = MapCtx(matcher, layers, targets, entrances, zoom)
    private val loaded = HashMap<Int, MapCtx?>()
    @Volatile private var cur = primary
    private val matcher get() = cur.matcher
    private val layers get() = cur.layers
    private val targets get() = cur.targets
    private val entrances get() = cur.entrances
    /** Zoom table of the current map. */
    val zoom: RegionZoomTable get() = cur.zoom
    /** The map the player is on (PlayerPose.mapId). */
    val mapId: Int get() = cur.mapId
    val geo: MapGeo get() = cur.geo
    /** Ids of every map a search covers (the current one first). */
    val mapIds: List<Int> get() = (listOf(cur.mapId) + listOf(primary.mapId) + (maps?.ids ?: emptyList())).distinct()

    /** The context of [id] (the primary map or one opened by the provider), or null when that map has no index. */
    fun ctxOf(id: Int): MapCtx? {
        if (id == primary.mapId) return primary
        return loaded.getOrPut(id) { maps?.takeIf { id in it.ids }?.load(id)?.also { it.matcher.params = params } }
    }

    // ---- state
    var state = TrackState.OFF
        private set
    private var pos: P2? = null
    private var vx = 0.0
    private var vy = 0.0
    private var tPos: Double? = null
    private var miss = 0
    private var nUni = 0
    private var nBlack = 0
    private var pending: Pending? = null
    private var pendingT = 0.0
    private var nFail = 0
    private var tReloc: Double? = null
    private var tLastFull = -1e9
    private var hiddenRet = "track"
    private var preLoading = TrackState.TRACKING
    private var relocFrom = "LOADING"
    private var refSig: FloatArray? = null
    private var tChanged = 0.0
    private var stillN = 0
    private val hs = HeadingSmoother(0.5)
    private var heading: Double? = null
    var layer: Floor? = null
        private set
    private var layerPending: Pair<Floor?, Int>? = null
    /** Floor decided by the big map (authoritative): kept after the map closes; leaving it needs an entrance or a clear minimap margin ([PIN_MARGIN], [PIN_AGREE] checks). */
    private var pinnedFloor: Floor? = null
    /** The last big-map floor decision and where it was made (zoom-0 px): breaks near-ties of the minimap floor scores while the player stays around there. */
    private var bigDecision: Pair<Floor, P2>? = null
    private var wasZone = false
    private var tLayer = 0.0
    private var avg: Double? = null
    private var layerAfterLock = false
    private var stillNow = false
    // ---- dynamic minimap zoom: the current scale (a region-table value or a ladder rung of s0 * (1 + step)^k)
    private var sCurV = 0.0
    private var scalePending: Pair<Double, Int>? = null
    private var scoreAvg: Double? = null
    private var learnN = 0
    private var ladderCursor = 0
    // ---- hints for the next relocation: teleport destination (big-map capture) and the area-name banner
    private var destHint: P2? = null
    private var destHintT = 0.0
    private var areaBox: DoubleArray? = null          // z0 px l, t, r, b of the banner's anchor
    private var areaAnchor: Int? = null
    private var areaSetT = 0.0
    private var learnAfterFix: P2? = null
    private var fixGroup: Int? = null                 // big-map fix in layer mode: the floor group whose overlays matched
    private var fixFloor: Floor? = null               // big-map fix with the floor selector read: the floor the player is on (seeds the first local search)
    private var fixTries = 0                          // seeded local searches done since the minimap came back
    private var preloadScale: Double? = null
    // ---- search plan of the current relocation episode (time-sliced) and its cancel flag
    private var plan: SearchRun? = null
    private var planFallback: Triple<MatchResult, Double, MapCtx>? = null
    @Volatile private var cancelFlag = false
    private var destMap = 0
    private var areaMap = 0

    /** (scale, best score) of the latest scale test / sweep, for the debug view's score curve. */
    var lastScaleScores: List<Pair<Double, Double>> = emptyList()
        private set

    /** Debug hooks: when set, the last local score map is kept as [lastHeat] (owner: the tracker). */
    var keepHeat = false
    var lastHeat: Mat? = null
        private set
    var lastMargin = 0.0
        private set
    var lastLayerTrigger = ""
        private set
    /** Ring statistic of the latest check (contrast in gray levels, or the legacy correlation, see [TrackerParams.ringMode]). */
    var lastRing = 0.0
        private set
    var lastContext: FrameContext? = null
        private set

    private companion object { const val ENTRANCE_FLOOR_MARGIN = 200.0; const val DEST_HINT_MAX = 25.0; const val BIG_HINT_RADIUS = 800.0; const val FIX_RADIUS = 40.0; const val FIX_FLOOR_RADIUS = 200.0; const val FLOOR_SCALE_LO = 1.2; const val FLOOR_SCALE_HI = 2.4; const val FIX_FLOOR_TRIES = 4; const val AREA_HINT_MAX = 120.0
        /** Floor scores within this of the best are a tie (no switch on minimap evidence alone). */
        const val FLOOR_TIE = 0.03
        /** Leaving a floor pinned by the big map away from an entrance: this much better for [PIN_AGREE] checks in a row. */
        const val PIN_MARGIN = 0.05; const val PIN_AGREE = 3
        const val BIG_DECISION_RADIUS = 400.0 }

    private class Pending(val x: Double, val y: Double, val path: String, val floor: Floor?, val s: Double, val map: MapCtx)

    /** Targets inside a layer zone of a map (IMPROVEMENT: relocation step 2b), cached per map. */
    private val zoneCache = HashMap<Int, IntArray>()
    private fun zoneTargetsOf(m: MapCtx): IntArray = zoneCache.getOrPut(m.mapId) {
        val tg = m.targets
        (0 until tg.size).filter {
            tg.linkedFloor(it) > 0 || m.layers.floorsAt(tg.x(it), tg.y(it), params.targetWin).isNotEmpty()
        }.toIntArray()
    }

    /** Runs [f] with [m] as the current map (the helpers read [cur]) and restores the previous one. */
    private inline fun <T> on(m: MapCtx, f: () -> T): T {
        val old = cur
        cur = m
        try { return f() } finally { cur = old }
    }

    /** Makes [m] the tracked map; the layer state belongs to the old map and is dropped. */
    private fun setMap(m: MapCtx) {
        if (m === cur) return
        cur = m
        layer = null; layerPending = null; wasZone = false; avg = null; layerAfterLock = false
        learnAfterFix = null; fixGroup = null; fixFloor = null; fixTries = 0; preloadScale = null; destHint = null
    }

    fun reset() {
        state = TrackState.OFF; pos = null; vx = 0.0; vy = 0.0; tPos = null; miss = 0; nUni = 0; nBlack = 0
        pending = null; pendingT = 0.0; nFail = 0; tReloc = null; tLastFull = -1e9; hiddenRet = "track"
        preLoading = TrackState.TRACKING; relocFrom = "LOADING"; refSig = null; tChanged = 0.0; stillN = 0
        hs.reset(); heading = null; layer = null; layerPending = null; wasZone = false; tLayer = 0.0; avg = null
        layerAfterLock = false; stillNow = false; sCurV = 0.0; scalePending = null; scoreAvg = null; lastScaleScores = emptyList()
        learnN = 0; ladderCursor = 0; learnAfterFix = null; fixGroup = null; fixFloor = null; fixTries = 0; preloadScale = null
        plan = null; planFallback = null; cancelFlag = false; destMap = 0
    }

    /**
     * Starts tracking. With a [prior] the position is only used as the "last known position" of the first
     * relocation (step 1); the state is RELOCATING until a lock is confirmed.
     */
    fun start(prior: P2? = null, trusted: Boolean = false) {
        reset()
        if (prior != null) {
            setPos(prior, 0.0)
            if (trusted) state = TrackState.TRACKING
        }
        if (state != TrackState.TRACKING) state = TrackState.RELOCATING
        tReloc = null
    }

    /** Last known position (zoom-0 px) or null. */
    val lastPosition: P2? get() = pos

    /** Where the big-map registration of map [forMap] should look first: the banner's area, then the last position (PLAN §6.0). */
    fun bigMapHints(forMap: Int = cur.mapId): List<BigMapHint> {
        val out = ArrayList<BigMapHint>()
        areaBoxLive(forMap)?.let { b -> out += BigMapHint(P2((b[0] + b[2]) / 2, (b[1] + b[3]) / 2), hypot(b[2] - b[0], b[3] - b[1]) / 2, "area") }
        if (forMap == cur.mapId) pos?.let { out += BigMapHint(it, BIG_HINT_RADIUS, "last") }
        if (forMap == destMap) destHint?.let { out += BigMapHint(it, 200.0, "destination") }
        return out
    }

    /** The teleport waypoint of map [forMap] nearest to a big-map cursor position (zoom-0 px) within [maxDist], or null. */
    fun nearestWaypoint(p: P2, maxDist: Double = 40.0, forMap: Int = cur.mapId): P2? {
        val tg = ctxOf(forMap)?.targets ?: return null
        var best: P2? = null; var bd = maxDist
        for (i in 0 until tg.size) {
            val d = hypot(tg.x(i) - p.x, tg.y(i) - p.y)
            if (d <= bd) { bd = d; best = P2(tg.x(i), tg.y(i)) }
        }
        return best
    }

    /** True while the tracker is waiting for the second agreeing result of a relocation. */
    val hasPending: Boolean get() = pending != null

    // ------------------------------------------------------------------------------------- helpers
    private fun setPos(p: P2, t: Double) {
        pos = p; tPos = t; vx = 0.0; vy = 0.0; miss = 0
    }

    private fun speed() = hypot(vx, vy)

    // ------------------------------------------------------------------------------- scale ladder
    private val kMin: Int get() = if (params.dynamicScale) Math.ceil(Math.log(params.scaleMin) / Math.log1p(params.scaleStep) - 1e-9).toInt() else 0
    private val kMax: Int get() = if (params.dynamicScale) Math.floor(Math.log(params.scaleMax) / Math.log1p(params.scaleStep) + 1e-9).toInt() else 0
    private val stepF: Double get() = 1.0 + params.scaleStep

    /** Scale of rung [k]: k = 0 is the calibrated base scale s0. */
    fun scaleOf(k: Int): Double = calib.s * Math.pow(stepF, k.toDouble())

    /** The current live minimap scale. */
    fun curS(): Double = if (sCurV > 0.0) sCurV else calib.s

    private fun setScale(s: Double) {
        if (abs(ln(s / curS())) > 1e-6) learnN = 0
        sCurV = s
    }

    private fun close(a: Double, b: Double, tol: Double = 0.012) = abs(ln(a / b)) < tol

    private fun inLadder(s: Double) = s >= calib.s * params.scaleMin / 1.03 && s <= calib.s * params.scaleMax * 1.03

    /** x_pos / y_pos of a zoom-0 position. */
    private fun gamePos(p: P2): P2 = geo.toPos(p.x, p.y)

    /** Region-table scales for a position of the current map (own region first, then the neighbours near a border). */
    private fun regionScales(p: P2?): List<Double> = regionScalesIn(cur, p)

    private fun regionScalesIn(m: MapCtx, p: P2?): List<Double> {
        p ?: return emptyList()
        val g = m.geo.toPos(p.x, p.y)
        val base = m.zoom.candidates(g.x, g.y, params.zoomBorderMargin)
        // on a floor: the zoom learned for its group comes first (indoor zooms differ from the surface region's)
        val fl = if (m === cur) layer?.let { m.zoom.floorScale(it.groupId) } else null
        return if (fl != null) mergeScales(listOf(fl), base) else base
    }

    /** Ladder rungs ordered by closeness (log scale) to [around]. */
    private fun ladderAround(around: Double): List<Double> =
        (kMin..kMax).map { scaleOf(it) }.sortedBy { abs(ln(it / around)) }

    private fun mergeScales(vararg lists: List<Double>): List<Double> {
        val out = ArrayList<Double>()
        for (l in lists) for (s in l) if (out.none { close(it, s, 0.004) }) out += s
        return out
    }

    /** Scale order of a ladder sweep at [p]: region scales first, then the ladder around the current (or base) scale. */
    private fun sweepScales(p: P2?): List<Double> {
        val fast = speed() > params.fastSpeedFrac * calib.r / curS()
        val around = when {
            fast -> minOf(curS(), calib.s * 0.85)
            stillNow || speed() < 1.0 -> curS()
            else -> curS()
        }
        return mergeScales(regionScales(p), listOf(curS()), ladderAround(around))
    }

    private class ScaleHit(val s: Double, val res: MatchResult)

    /** Local match at [center] for each scale of [ss] (each at its own working level); stops at the first good one when [stopAt] / [budgetMs] say so. */
    private fun scaleTest(ctx: FrameContext, center: P2, rad: Double, ss: List<Double>, floor: Floor?, stopAt: Double? = null, budgetMs: Double = 1e9): List<ScaleHit> {
        val out = ArrayList<ScaleHit>(ss.size)
        val t0 = System.nanoTime()
        for (sk in ss) {
            val z = matcher.localLevel(sk)
            val ref = refOf(floor, z)
            stats.local++
            val r = matcher.localMatch(ctx, center, rad, sk, ctx.template(z, sk), z, keepHeat = keepHeat, ref = ref)
            out += ScaleHit(sk, r)
            if (stopAt != null && r.ok && r.score >= stopAt && r.margin >= params.tauMargin) break
            if ((System.nanoTime() - t0) / 1e6 > budgetMs) break
        }
        return out
    }

    private fun hitScore(h: ScaleHit) = (if (h.res.ok) h.res.score else 0.0) + (if (close(h.s, curS(), 0.003)) params.scaleBias else 0.0)

    private fun sigOf(ctx: FrameContext) = screen.minimapSig(ctx.gray8())

    private fun lock(p: P2, t: Double, ctx: FrameContext, floor: Floor?) {
        setPos(p, t)
        state = TrackState.TRACKING
        pending = null
        refSig = sigOf(ctx)
        tChanged = t
        stillN = 0
        layer = floor
        if (pinnedFloor?.floorId != floor?.floorId) pinnedFloor = null
        layerPending = null
        avg = null
        scalePending = null
        scoreAvg = null
        destHint = null
        areaBox = null; areaAnchor = null
        layerAfterLock = params.layersOn && floor == null && layers.floorsAt(p.x, p.y).isNotEmpty()
        wasZone = false
    }

    private fun enterReloc(t: Double, src: String) {
        state = TrackState.RELOCATING
        tReloc = t
        pending = null
        plan = null
        nFail = 0
        relocFrom = src
    }

    private fun updateHeading(ctx: FrameContext) {
        val t0 = System.nanoTime()
        val h = (if (params.arrowTemplate) ArrowHeading.estimate(ctx.crop) else null) ?: Heading.estimate(ctx.crop)
        heading = hs.update(h.deg)
        matcher.times.headingMs += (System.nanoTime() - t0) / 1e6
    }

    private fun refOf(l: Floor?, z: Int): RefImage? = if (l == null) null else matcher.index.floorRef(l.floorId, z)

    fun nextCheckDelay(): Double {
        val c = params
        if (nUni == 1 || nBlack == 1) return c.dPending
        return when (state) {
            TrackState.TRACKING -> when {
                layerPending != null || layerAfterLock -> c.dLayerPending
                stillNow -> c.dStill
                speed() > c.fastSpeedFrac * calib.r / curS() -> c.dFast
                else -> c.dTracking
            }
            TrackState.LOADING -> c.dLoading
            TrackState.RELOCATING -> c.dRelocating
            TrackState.DOMAIN -> c.dDomain
            TrackState.FIXED -> if (fixTries > 0) c.dRelocating else c.dHidden        // seeded verification after a big-map fix: quick retries
            TrackState.HIDDEN -> c.dHidden
            TrackState.LOST -> if (plan != null) c.dLostSlice else c.dLost
            TrackState.OFF -> 1.0
        }
    }

    // ------------------------------------------------------------------------------------ entry
    /** One check. [t] is a monotonic clock in seconds. */
    fun update(f: FrameInput, t: Double): TrackPose {
        val w0 = System.nanoTime()
        val local0 = stats.local; val full0 = stats.full; val tgt0 = stats.target
        val state0 = state
        var ctx: FrameContext? = null
        val pose = try {
            val (p, c) = innerUpdate(f, t)
            ctx = c
            p
        } finally {
            // the context of this check stays alive until the next one (the debug view reads it), then it is closed
        }
        if (lastContext !== ctx) { lastContext?.close(); lastContext = ctx }
        stats.checks++
        stats.byState[state0] = (stats.byState[state0] ?: 0) + 1
        if (stats.local == local0 && stats.full == full0 && stats.target == tgt0) stats.cheap++
        val wall = (System.nanoTime() - w0) / 1e6
        stats.cpuMs += wall
        pose.ms = wall
        pose.delay = nextCheckDelay()
        return pose
    }

    private fun innerUpdate(f: FrameInput, t: Double): Pair<TrackPose, FrameContext?> {
        val cfg = params
        val th0 = System.nanoTime()
        if (state == TrackState.OFF || (state == TrackState.RELOCATING && tReloc == null)) enterReloc(t, "START")
        // ---- 1. thumbnail (a uniform thumbnail with the ring on screen is a dark/bright HUD scene, not a loading screen)
        lastRing = screen.ringValue(f, calib)
        val ringOk = lastRing >= screen.ringThreshold()
        if (screen.isLoadingScreen(f.thumb) && !ringOk) {
            nUni++
            nBlack = 0
            stats.cheapMs += (System.nanoTime() - th0) / 1e6
            if (nUni >= cfg.uniformChecks && state != TrackState.LOADING) {
                preLoading = if (state != TrackState.RELOCATING) state else when (relocFrom) {
                    "DOMAIN" -> TrackState.DOMAIN
                    "TRACKING" -> TrackState.TRACKING
                    else -> TrackState.LOST
                }
                state = TrackState.LOADING
                pending = null
                plan = null
            }
            return pose("none", 0.0, false) to null
        }
        nUni = 0
        if (state == TrackState.LOADING) {
            val src = when (preLoading) {
                TrackState.DOMAIN -> "DOMAIN"
                TrackState.TRACKING -> "TRACKING"
                else -> "LOADING"
            }
            enterReloc(t, src)
        }
        // ---- 2. ring
        if (!ringOk) {
            nBlack = 0
            stats.cheapMs += (System.nanoTime() - th0) / 1e6
            if (state == TrackState.TRACKING || state == TrackState.DOMAIN || state == TrackState.LOST) {   // FIXED stays FIXED
                hiddenRet = when (state) {
                    TrackState.TRACKING -> "track"
                    TrackState.DOMAIN -> "relocate"
                    else -> "lost"
                }
                state = TrackState.HIDDEN
            }
            return pose("none", 0.0, false) to null
        }
        // ---- 3. black interior (domain)
        val ctx = FrameContext.create(f, calib, params, matcher.index.meta.sigma, matcher.index.meta.lcnC)
        if (screen.minimapIsBlack(ctx.gray8())) {
            nBlack++
            stats.cheapMs += (System.nanoTime() - th0) / 1e6
            if (nBlack >= cfg.blackChecks && state != TrackState.DOMAIN) {
                state = TrackState.DOMAIN
                pending = null
            }
            return pose("none", 0.0, false) to ctx
        }
        nBlack = 0
        stats.cheapMs += (System.nanoTime() - th0) / 1e6
        // ---- 4. matching, as much as the state needs
        var s = state
        if (s == TrackState.DOMAIN) {                    // interior no longer black without a loading screen seen: re-locate
            enterReloc(t, "DOMAIN")
            s = TrackState.RELOCATING
        } else if (s == TrackState.HIDDEN || s == TrackState.FIXED) {
            val ret = if (s == TrackState.FIXED) "track" else hiddenRet
            if (ret == "track" && pos != null) return hiddenReturn(ctx, t) to ctx
            if (ret == "lost") {
                state = TrackState.LOST; s = TrackState.LOST
            } else {
                enterReloc(t, "HIDDEN"); s = TrackState.RELOCATING
            }
        }
        if (s == TrackState.TRACKING) return track(ctx, t) to ctx
        if (s == TrackState.RELOCATING) {
            val tr = tReloc
            if (tr != null && t - tr >= cfg.relocMax && pending == null) {
                state = TrackState.LOST
                relocFrom = "LOST"
                return pose("none", 0.0, false) to ctx
            }
            return relocate(ctx, t) to ctx
        }
        if (s == TrackState.LOST) {
            if (t - tLastFull < (if (plan != null) cfg.dLostSlice else cfg.dLost) - 1e-6) return pose("none", 0.0, false) to ctx
            return relocate(ctx, t) to ctx
        }
        return pose("none", 0.0, false) to ctx
    }

    // --------------------------------------------------------------------------------------- pose
    private fun pose(mode: String, score: Double, accepted: Boolean, conf: Double = 0.0, path: String = ""): TrackPose {
        val p = pos ?: P2(0.0, 0.0)
        val g = geo.toPos(p.x, p.y)
        val l = layer
        return TrackPose(state, p.x, p.y, g.x, g.y, heading, conf, score, mode, 0.0, accepted, l?.groupId, l?.floorId, path, 1.0, curS())
    }

    // ----------------------------------------------------------------------- HIDDEN -> minimap back
    /** Local search around the last position (wide); on a miss -> RELOCATING (search order incl. whole map). */
    private fun hiddenReturn(ctx: FrameContext, t: Double): TrackPose {
        val cfg = params
        learnAfterFix?.let { fix ->
            // the minimap is back after a big-map fix: the player is at the fixed spot, so sweep the whole ladder there
            // (region scales first) and record the winner for the region. A fix in layer mode (the floor group is known, the floor is not):
            // the group's floors that hold the spot compete, each at the group's learned zoom first, and the best one becomes the layer.
            // A fix with the floor selector read (the floor is known): only that floor's overlay, at the group's learned zoom (else the
            // floor ladder), within FIX_FLOOR_RADIUS; one good match locks (the position is authoritative), a few misses fall back to the relocation.
            val grp = fixGroup
            val seed = fixFloor
            val floors: List<Floor?> = when {
                seed != null -> listOf(seed)
                grp != null -> layers.floorsAt(fix.x, fix.y, 4.0).filter { it.groupId == grp }
                else -> listOf(layer)
            }
            val ss = if (seed != null) mergeScales(
                zoom.floorScale(seed.groupId)?.let { listOf(it) }.orEmpty(), listOf(curS()).filter { it in FLOOR_SCALE_LO..FLOOR_SCALE_HI },
                RelocPlan.scanRungs(calib.s, calib.s * cfg.scaleMin, calib.s * cfg.scaleMax, cfg.floorScanStep, cfg.floorScaleAround).filter { it in FLOOR_SCALE_LO..FLOOR_SCALE_HI },
            ) else mergeScales(
                grp?.let { g -> zoom.floorScale(g)?.let { listOf(it) } }.orEmpty(), regionScales(fix), listOf(curS()), if (cfg.dynamicScale) ladderAround(curS()) else emptyList(),
            )
            val rad = if (seed != null) FIX_FLOOR_RADIUS else FIX_RADIUS
            var bestH: ScaleHit? = null
            var bestFloor: Floor? = null
            var allHits = emptyList<ScaleHit>()
            for (fl in floors) {
                val hits = scaleTest(ctx, fix, rad, ss, fl, stopAt = if (seed != null) 0.6 else 0.7, budgetMs = cfg.sweepBudgetMs * 2 / max(1, floors.size))
                val h = hits.maxByOrNull { if (it.res.ok) it.res.score else 0.0 } ?: continue
                if (bestH == null || h.res.score > bestH.res.score) { bestH = h; bestFloor = fl; allHits = hits }
            }
            val best = bestH
            val good = best != null && best.res.ok && best.res.margin >= cfg.tauMargin &&
                (if (seed != null) best.res.score >= cfg.tauFloor else best.res.score >= cfg.zoomLearnScore)
            if (good && best != null) {
                learnAfterFix = null; fixGroup = null; fixFloor = null; fixTries = 0
                setScale(best.s)
                val g = gamePos(fix)
                if (bestFloor != null) {
                    zoom.observeFloor(bestFloor.groupId, best.s)
                    layer = bestFloor; layerPending = null; avg = null
                } else {
                    layer = null
                    val own = zoom.regionAt(g.x, g.y)
                    val old = own?.let { zoom.entry(it.id) }
                    // an exact position makes this the most reliable observation: a new entry, or a nudge of an existing one
                    if (own != null && old == null) zoom.put(ZoomEntry(own.id, best.s, 3, System.currentTimeMillis())) else zoom.observe(g.x, g.y, best.s)
                }
                state = TrackState.TRACKING
                setPos(P2(best.res.px0, best.res.py0), t)
                refSig = sigOf(ctx)
                tChanged = t
                updateHeading(ctx)
                lastScaleScores = allHits.sortedBy { it.s }.map { it.s to (if (it.res.ok) it.res.score else 0.0) }
                return pose("local", best.res.score, true, best.res.confidence, "mapfix")
            }
            if (seed != null && ++fixTries < FIX_FLOOR_TRIES) {
                // still the authoritative fix (state FIXED, floor shown): try again on the next frame
                lastScaleScores = allHits.sortedBy { it.s }.map { it.s to (if (it.res.ok) it.res.score else 0.0) }
                return pose("none", best?.res?.score ?: 0.0, true, 1.0, "mapfix")
            }
            learnAfterFix = null; fixGroup = null; fixFloor = null; fixTries = 0
        }
        val s = curS()
        val z = matcher.localLevel(s)
        val ref = refOf(layer, z)
        stats.local++
        val r = matcher.localMatch(ctx, pos!!, cfg.localMaxRadius * 1.5, s, ref = ref)
        if (r.ok && r.score >= cfg.tauLocal && r.margin >= cfg.tauMargin) {
            state = TrackState.TRACKING
            setPos(P2(r.px0, r.py0), t)
            refSig = sigOf(ctx)
            tChanged = t
            updateHeading(ctx)
            return pose("local", r.score, true, r.confidence)
        }
        enterReloc(t, "HIDDEN")
        return relocate(ctx, t)
    }

    // --------------------------------------------------------------------------------------- layers
    private class LayerOutcome(val key: Int?, val layer: Floor?, val res: MatchResult, val results: Map<Int?, Pair<Floor?, MatchResult>>, val trigger: String)

    /** Floors of the entrances in [ents] that can hold the position (their padded reference covers it). */
    private fun linkedFloors(ents: List<Entrance>, p: P2): List<Floor> =
        ents.flatMap { e -> e.floorIds.mapNotNull { layers.byId[it] } }.filter { it.contains(p.x, p.y, ENTRANCE_FLOOR_MARGIN) }.distinctBy { it.floorId }

    /**
     * Candidate order of a layer check (PLAN §6.2): near an entrance, that entrance's linked floor first, then the
     * surface, then the rest of the zone; walking out (the current floor belongs to the entrance) the surface and the
     * floor on the other side come first. Without an entrance: surface, then the zone's floors.
     */
    private fun layerOrder(p: P2, zone: List<Floor>, near: List<Entrance>, drop: Boolean): List<Floor?> {
        val cfg = params
        var ents = near
        if (ents.isEmpty() && drop && cfg.useEntrances) ents = listOfNotNull(entrances.nearest(p.x, p.y, cfg.entranceNearest))
        val first = ents.firstOrNull()
        val order = ArrayList<Floor?>()
        if (first != null) {
            val linked = linkedFloors(listOf(first), p)
            val cur = layer
            if (cur != null && linked.any { it.floorId == cur.floorId }) {
                order += null
                order += linked.filter { it.floorId != cur.floorId }
            } else {
                order += linked
                order += null
            }
            order += linkedFloors(ents.drop(1), p)
        } else {
            order += null
        }
        for (f in zone) if (order.none { it?.floorId == f.floorId }) order += f
        if (null !in order) order += null
        return order
    }

    /** Local match at [center] against the candidates in priority order; stops early on a clear winner. */
    private fun layerCheck(ctx: FrameContext, center: P2, rad: Double, order: List<Floor?>, curRes: MatchResult?, s: Double, z: Int, trigger: String): LayerOutcome {
        val cfg = params
        val t0 = System.nanoTime()
        val tm = ctx.template(z, s)
        val results = LinkedHashMap<Int?, Pair<Floor?, MatchResult>>()
        val curId = layer?.floorId
        val cands = ArrayList(order)
        if (cands.none { it?.floorId == curId }) cands += layer
        val curScore = if (curRes != null && curRes.ok) curRes.score else 0.0
        for (l in cands) {
            val key = l?.floorId
            if (key == curId) {
                if (curRes != null) results[key] = l to curRes
                else {
                    stats.local++
                    val ref = refOf(l, z)
                    if (l == null || ref != null) results[key] = l to matcher.localMatch(ctx, center, rad, s, tm, z, ref = ref)
                }
                continue
            }
            val ref = refOf(l, z)
            if (l != null && ref == null) continue
            stats.local++
            val r = matcher.localMatch(ctx, center, rad, s, tm, z, ref = ref)
            results[key] = l to r
            if (r.ok && r.score >= cfg.layerStopScore && r.score > curScore + cfg.layerBias && r.margin >= cfg.tauMargin) break
        }
        var bestKey: Int? = null
        var best: Triple<Double, Floor?, MatchResult>? = null
        for ((key, v) in results) {
            val (l, r) = v
            val sc = (if (r.ok) r.score else 0.0) + (if (key == curId) cfg.layerBias else 0.0)
            if (best == null || sc > best.first) { bestKey = key; best = Triple(sc, l, r) }
        }
        matcher.times.layerMs += (System.nanoTime() - t0) / 1e6
        return LayerOutcome(bestKey, best!!.second, best.third, results, trigger)
    }

    // -------------------------------------------------------------------------------------- TRACKING
    private fun track(ctx: FrameContext, t: Double): TrackPose {
        val cfg = params
        val s = curS()
        val p0 = pos!!
        val dt = max(1e-3, t - (tPos ?: (t - 1.0)))
        val pred = P2(p0.x + vx * dt, p0.y + vy * dt)
        // ---- unchanged minimap: skip matching (cheap-only), verify every Nth
        val sig = sigOf(ctx)
        val rs = refSig
        val changed = rs == null || screen.sigDiff(rs, sig) > cfg.chgThr
        if (changed) {
            tChanged = t; stillN = 0
        } else {
            stillN++
        }
        stillNow = (t - tChanged) >= cfg.stillAfter
        if (!changed && miss == 0 && stillN % cfg.stillVerifyEvery != 0 && !layerAfterLock) {
            tPos = t
            vx = 0.0; vy = 0.0
            updateHeading(ctx)
            return pose("still", 0.0, true)
        }
        val rad = (cfg.vmax * dt + 10.0 + 8.0 * miss).coerceIn(cfg.localMinRadius, cfg.localMaxRadius)
        val gate = min(cfg.vmax * dt + 10.0 + 8.0 * miss, rad)
        lastHeat?.release(); lastHeat = null
        // ---- dynamic zoom: the current scale and one ladder step either side (plus the region table's scales near a border), best (position, scale) wins
        val inRegion = regionScales(pred)
        val near3 = ArrayList<Double>()
        near3 += s
        if (cfg.dynamicScale) {
            for (c in listOf(s / stepF, s * stepF)) if (inLadder(c)) near3 += c
            for (c in inRegion.take(2)) if (near3.none { close(it, c, 0.012) }) near3 += c
            preloadScale?.let { c -> if (near3.none { close(it, c, 0.012) }) near3 += c; preloadScale = null }
        }
        var hits = scaleTest(ctx, pred, rad, near3, layer)
        var bestHit = hits.maxBy { hitScore(it) }
        var res = bestHit.res
        var sRes = bestHit.s
        fun accepts(r: MatchResult) = r.ok && r.score >= cfg.tauLocal && r.margin >= cfg.tauMargin &&
            hypot(r.px0 - pred.x, r.py0 - pred.y) <= gate        // a far match in TRACKING is a miss, never a teleport
        var ok = accepts(res)
        val avgS = scoreAvg
        val dropped = res.ok && avgS != null && res.score < avgS - cfg.scaleDrop
        if (cfg.dynamicScale && (!ok || dropped) && kMax > kMin) {
            // a sudden zoom change looks exactly like a miss: sweep the whole ladder (region scales first) before counting one
            val curScore = hits.firstOrNull { close(it.s, s, 0.003) }?.res?.takeIf { it.ok }?.score ?: 0.0
            val rest = sweepScales(pred).filter { k -> hits.none { close(it.s, k, 0.003) } }
            val extra = scaleTest(ctx, pred, rad, rest, layer, stopAt = cfg.scaleStopScore, budgetMs = cfg.sweepBudgetMs)
            hits = hits + extra
            val sw = hits.maxBy { hitScore(it) }
            if (accepts(sw.res) && !close(sw.s, s, 0.003) && sw.res.score >= max(cfg.tauLocal, curScore + cfg.scaleSweepGain)) {
                setScale(sw.s); scalePending = null           // the sweep is a whole-ladder test: switch at once
                bestHit = sw; res = sw.res; sRes = sw.s; ok = true
            } else if (accepts(sw.res) && !ok) {
                bestHit = sw; res = sw.res; sRes = sw.s; ok = true
            }
        } else if (ok && !close(sRes, s, 0.003)) {
            // a neighbouring scale beats the current one: needs `scaleAgree` checks in a row (same hysteresis as layers)
            val sp = scalePending
            val cnt = if (sp != null && close(sp.first, sRes, 0.012)) sp.second + 1 else 1
            if (cnt >= cfg.scaleAgree) { setScale(sRes); scalePending = null } else scalePending = sRes to cnt
        } else {
            scalePending = null
        }
        val z = matcher.localLevel(sRes)
        lastScaleScores = hits.sortedBy { it.s }.map { it.s to (if (it.res.ok) it.res.score else 0.0) }
        if (keepHeat) {
            lastHeat = res.heat
            hits.forEach { if (it.res !== res) it.res.heat?.release() }
        }
        val posEst = if (ok) P2(res.px0, res.py0) else pred
        var measured: P2? = if (ok) P2(res.px0, res.py0) else null
        var score = if (res.ok) res.score else 0.0
        val inBounds = if (cfg.layersOn) layers.floorsAt(posEst.x, posEst.y) else emptyList()
        val near = if (cfg.layersOn && cfg.useEntrances) entrances.near(posEst.x, posEst.y, cfg.entranceRadius) else emptyList()
        val nearFloors = linkedFloors(near, posEst)
        val zone = (inBounds + nearFloors).distinctBy { it.floorId }
        if (zone.isEmpty()) {
            layer = null; layerPending = null; avg = null; wasZone = false; pinnedFloor = null
            layerAfterLock = false
        } else {
            val entered = !wasZone
            val a = avg
            val drop = !ok || (a != null && score < a - cfg.tauLayerDrop)
            val periodic = (t - tLayer) >= cfg.layerPeriod
            val atEntrance = nearFloors.isNotEmpty()             // floor changes happen here: recheck every tick
            val forced = layerPending != null || layerAfterLock || atEntrance
            if (entered || drop || periodic || forced) {
                val trigger = when {
                    atEntrance -> "entrance"; layerAfterLock -> "lock"; layerPending != null -> "pending"
                    entered -> "entered"; drop -> "drop"; else -> "periodic"
                }
                layerAfterLock = false
                tLayer = t
                stats.layerChecks++
                val order = layerOrder(pred, zone, near, drop)
                val o = layerCheck(ctx, pred, rad, order, if (res.ok) res else null, sRes, z, trigger)
                lastLayerTrigger = trigger
                val curId = layer?.floorId
                val curSc = o.results[curId]?.second?.takeIf { it.ok }?.score ?: 0.0
                // near-tie of floor scores (the zooms of neighbouring floors differ, the art is alike): minimap evidence alone does not switch;
                // the pinned floor, else the last big-map decision here, else the current one is kept
                var oKey = o.key; var oLayer = o.layer; var r = o.res
                val bestSc = if (r.ok) r.score else 0.0
                val tied = o.results.filter { (_, v) -> v.second.ok && v.second.score >= bestSc - FLOOR_TIE }
                if (tied.size > 1) {
                    val bd = bigDecision?.takeIf { hypot(it.second.x - pred.x, it.second.y - pred.y) <= BIG_DECISION_RADIUS }?.first
                    val pref = pinnedFloor?.let { tied[it.floorId] } ?: bd?.let { tied[it.floorId] } ?: tied[curId]
                    if (pref != null) { oLayer = pref.first; oKey = oLayer?.floorId; r = pref.second }
                }
                val rOk = r.ok && r.score >= cfg.tauLocal && r.margin >= cfg.tauMargin &&
                    hypot(r.px0 - pred.x, r.py0 - pred.y) <= gate
                val pin = pinnedFloor
                val pinSc = pin?.let { o.results[it.floorId]?.second?.takeIf { m -> m.ok }?.score } ?: 0.0
                val gated = pin != null && oKey != pin.floorId && !atEntrance          // leaving a pinned floor away from an entrance
                if (oKey == curId || !rOk || (gated && r.score < pinSc + PIN_MARGIN)) {
                    layerPending = null
                } else {
                    val lp = layerPending
                    val cnt = if (lp != null && lp.first === oLayer) lp.second + 1 else 1
                    if (cnt >= (if (gated) PIN_AGREE else cfg.layerAgree)) {                      // switch
                        layer = oLayer; layerPending = null; avg = null
                        if (oKey != pin?.floorId) pinnedFloor = null
                        ok = true; res = r; score = r.score
                        measured = P2(r.px0, r.py0)
                    } else {
                        layerPending = oLayer to cnt
                        if (r.score >= curSc + cfg.layerOverride) {   // current layer is clearly worse: don't trust it
                            ok = false; measured = null
                        }
                    }
                }
            }
            wasZone = true
        }
        // ---- filter update
        lastMargin = res.margin
        if (ok) {
            miss = 0
            scoreAvg = scoreAvg.let { if (it == null) score else (1 - cfg.avgAlpha) * it + cfg.avgAlpha * score }
            avg = avg.let { if (it == null) score else (1 - cfg.avgAlpha) * it + cfg.avgAlpha * score }
            val m = measured!!
            val rx = m.x - pred.x
            val ry = m.y - pred.y
            val np = P2(pred.x + cfg.alpha * rx, pred.y + cfg.alpha * ry)
            vx += cfg.beta * rx / dt
            vy += cfg.beta * ry / dt
            val sp = hypot(vx, vy)
            if (sp > cfg.vmax) { vx *= cfg.vmax / sp; vy *= cfg.vmax / sp }
            pos = np; tPos = t
            refSig = sig
            updateHeading(ctx)
            learnZoom(np, score, t)
            return pose("local", score, true, res.confidence)
        }
        if (layerPending == null) miss++
        learnN = 0
        pos = pred; tPos = t                                   // coast on the prediction
        if (miss >= cfg.maxMiss) {
            state = TrackState.LOST
            relocFrom = "LOST"
            pending = null
        }
        return pose("coast", score, false)
    }

    // ------------------------------------------------------------------------- RELOCATING / LOST
    private fun acceptLocal(r: MatchResult, strict: Boolean = true, floor: Boolean = false): Boolean {
        val cfg = params
        if (!r.ok) return false
        if (floor) return r.score >= cfg.tauFloor && r.margin >= cfg.tauFloorMargin
        return if (strict) r.score >= cfg.tauRelocLocal && r.margin >= cfg.tauRelocLocalMargin
        else r.score >= cfg.tauGlobal && r.margin >= cfg.tauGlobalMargin
    }

    private class Cand(val x: Double, val y: Double, val path: String, val score: Double, val floor: Floor?, val s: Double, val map: MapCtx)

    /**
     * Likely minimap scales on map [m], most likely first (PLAN §6.3): the zoom of the teleport destination's region, the
     * banner's region and the last position's region, the current scale, every scale learned on that map, then the base
     * scale and rungs around it (a map without learned zoom tries [TrackerParams.relocUnknownOffsets]). The sliced whole-map
     * scan of [RelocPlan] covers the rest of the ladder.
     */
    private fun priorScales(m: MapCtx): List<Double> {
        if (!params.dynamicScale) return listOf(curS())
        val home = m === cur
        val dest = if (destMap == m.mapId) destHint?.let { regionScalesIn(m, it) }.orEmpty() else emptyList()
        val area = areaBoxLive(m.mapId)?.let { b -> regionScalesIn(m, P2((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)) }.orEmpty()
        val last = if (home) regionScalesIn(m, pos) else emptyList()
        val known = m.zoom.knownScales()
        val unknown = dest.isEmpty() && area.isEmpty() && last.isEmpty() && known.isEmpty()
        val offs = (if (unknown) params.relocUnknownOffsets else params.relocScaleOffsets).filter { it in kMin..kMax }.map { scaleOf(it) }
        return mergeScales(dest, area, last, listOf(if (home) curS() else calib.s), known, offs)
    }

    /** The banner's box while it is fresh and belongs to map [forMap] (a banner older than 2 minutes says nothing about where the player is now). */
    private fun areaBoxLive(forMap: Int): DoubleArray? =
        areaBox?.takeIf { areaMap == forMap && System.nanoTime() / 1e9 - areaSetT < AREA_HINT_MAX }

    /** Targets of [m] inside the banner's box (priority of relocation step 2), as indices into its targets. */
    private fun targetsInArea(m: MapCtx): IntArray {
        val b = areaBoxLive(m.mapId) ?: return IntArray(0)
        val tg = m.targets
        return (0 until tg.size).filter { tg.x(it) in b[0]..b[2] && tg.y(it) in b[1]..b[3] }.toIntArray()
    }

    /** The job list of a new search episode: last position -> targets of all maps -> whole maps (see [RelocPlan]). */
    private fun newPlan(t: Double): SearchRun {
        val cfg = params
        val order = cfg.relocOrderTargets
        val ctxs = mapIds.mapNotNull { ctxOf(it) }
        val pm = ctxs.map { m ->
            PlanMap(
                m.mapId, m.cost, priorScales(m), m.targets.size > 0, zoneTargetsOf(m).isNotEmpty(), areaBoxLive(m.mapId) != null,
                floors = cfg.layersOn && m.layers.floors.isNotEmpty(), floorScales = floorScalesOf(m),
            )
        }
        val dm = if (destHint != null && t - destHintT < DEST_HINT_MAX && ctxOf(destMap) != null) destMap else null
        val ladder = if (cfg.dynamicScale) RelocPlan.scanRungs(calib.s, calib.s * cfg.scaleMin, calib.s * cfg.scaleMax, cfg.scanStep, curS()) else emptyList()
        // floors: their own rungs, indoor zooms first (the surface ladder is ordered around the current scale, which is a field zoom)
        val floorLadder = if (cfg.dynamicScale && pm.any { it.floors }) RelocPlan.scanRungs(calib.s, calib.s * cfg.scaleMin, calib.s * cfg.scaleMax, cfg.floorScanStep, cfg.floorScaleAround) else emptyList()
        return SearchRun(RelocPlan.build(cur.mapId, dm, pm, ladder, hasLast = order && pos != null, withTargets = order, floorLadder = floorLadder))
    }

    /** Zooms learned on floor groups of [m]: the current floor's group first, then the most sampled. */
    private fun floorScalesOf(m: MapCtx): List<Double> =
        mergeScales(listOfNotNull(if (m === cur) layer?.let { m.zoom.floorScale(it.groupId) } else null), m.zoom.floorScales())

    /** Ids of the floors that a teleport waypoint of [m] enters (their drawings are the likeliest right after a teleport underground). */
    private val linkedCache = HashMap<Int, Set<Int>>()
    private fun linkedFloorIds(m: MapCtx): Set<Int> = linkedCache.getOrPut(m.mapId) { (0 until m.targets.size).map { m.targets.linkedFloor(it) }.filter { it > 0 }.toSet() }

    /**
     * Floors of [m] that can hold a minimap of scale [s] (their longest side is at least [TrackerParams.floorMinVisible] of the visible diameter),
     * most likely first: near the last position / destination / banner area, floors of underground waypoints ahead.
     */
    private fun floorsFor(m: MapCtx, s: Double, side: Int): List<Floor> {
        val minSide = params.floorMinVisible * side / s
        val pts = ArrayList<Triple<Double, Double, Double>>()
        if (m === cur) pos?.let { pts += Triple(it.x, it.y, 0.0) }
        if (destMap == m.mapId) destHint?.let { pts += Triple(it.x, it.y, 0.0) }
        areaBoxLive(m.mapId)?.let { b -> pts += Triple((b[0] + b[2]) / 2, (b[1] + b[3]) / 2, hypot(b[2] - b[0], b[3] - b[1]) / 2) }
        val linked = linkedFloorIds(m)
        fun key(f: Floor): Double {
            val cx = (f.l + f.r) / 2; val cy = (f.t + f.b) / 2
            val d = pts.minOfOrNull { max(0.0, hypot(cx - it.first, cy - it.second) - it.third) } ?: 0.0
            return d - if (f.floorId in linked) 3000.0 else 0.0
        }
        return m.layers.floors.filter { max(it.r - it.l, it.b - it.t) >= minSide }.sortedBy { key(it) }
    }

    /** Coarse level of a floor scan: the whole-map scan's level, but the floors are prepared only at z-2..0. */
    private fun floorLevel(s: Double, side: Int): Int = RelocPlan.scanLevel(s, side, params.scanMinTemplate).coerceIn(-2, 0)

    /** Work of scanning [fs] at scale [s]: megapixels of the correlation windows (template + floor side) at the coarse level. */
    private fun floorUnits(fs: List<Floor>, s: Double, side: Int): Double {
        val f = Math.pow(2.0, floorLevel(s, side).toDouble())
        val t = side * f / s
        return fs.sumOf { val n = t + max(it.r - it.l, it.b - it.t) * f; n * n } / 1e6
    }

    /** The [j]'s share of the priority-ordered floor list (contiguous pieces: the likeliest floors stay in the first piece). */
    private fun floorChunk(all: List<Floor>, j: SearchJob): List<Floor> {
        if (j.parts <= 1) return all
        val n = Math.ceil(all.size / j.parts.toDouble()).toInt()
        return all.drop(j.part * n).take(n)
    }

    /** Scale refinement of a coarse whole-map hit: the scan ladder is [TrackerParams.scanStep] wide, so test the neighbours at the hit. */
    private fun refineHit(ctx: FrameContext, g: MatchResult, sk: Double): Pair<MatchResult, Double> {
        var best = g to sk
        for (c in listOf(sk / 1.03, sk * 1.03)) {
            stats.local++
            val r = matcher.localMatch(ctx, P2(g.px0, g.py0), 10.0, c, z = matcher.localLevel(c))
            if (r.ok && r.score > best.first.score) best = r to c
        }
        return best
    }

    /** Scale refinement of a floor hit (the floor ladder is [TrackerParams.floorScanStep] wide). */
    private fun refineFloorHit(ctx: FrameContext, g: MatchResult, fl: Floor, sk: Double): Pair<MatchResult, Double> {
        var best = g to sk
        for (c in listOf(sk / 1.02, sk * 1.02)) {
            val z = matcher.localLevel(c)
            val ref = refOf(fl, z) ?: continue
            stats.local++
            val r = matcher.localMatch(ctx, P2(g.px0, g.py0), 8.0, c, z = z, ref = ref)
            if (r.ok && r.score > best.first.score) best = r.also { it.second = max(it.second, g.second) } to c
        }
        return best
    }

    /** Phone-pessimistic start values (ms per Mpx of the coarse level, ms per teleport target); replaced by what the device measures. */
    private val cost = CostModel { k -> if (k.startsWith("W") || k == "F") 250.0 else if (k == "G") 12.0 else 1.0 }

    /** Cost key and work units of the splittable job [j] (a whole-map scan or a target batch), or null for the always-small jobs. */
    private fun jobUnits(j: SearchJob, ctx: FrameContext): Pair<String, Double>? {
        val m = ctxOf(j.mapId) ?: return null
        return when (j.kind) {
            JobKind.WHOLE -> on(m) {
                val zc = matcher.scanLevel(j.scale, ctx.side)
                val lv = matcher.level(zc)
                var mpx = lv.w * lv.h / 1e6
                if (j.boxed) areaBoxLive(m.mapId)?.let { b -> mpx *= ((b[2] - b[0]) * (b[3] - b[1]) / (lv.w / lv.f * lv.h / lv.f)).coerceIn(0.0, 1.0) }
                "W$zc" to mpx
            }
            JobKind.TARGETS -> on(m) { if (targets.size == 0) null else "T" to targets.size.toDouble() }
            // floor targets: one or two floor matches per waypoint inside a zone (they blew the slice: all of them at once, uncounted)
            JobKind.FLOOR_TARGETS -> zoneTargetsOf(m).size.takeIf { it > 0 }?.let { "G" to it.toDouble() }
            JobKind.FLOORS -> floorsFor(m, j.scale, ctx.side).takeIf { it.isNotEmpty() }?.let { "F" to floorUnits(it, j.scale, ctx.side) }
            else -> null
        }
    }

    /** Cuts a job that would take more than half a slice into pieces (an uninterruptible job of 3-5 s blew the 1.2 s budget on the phone). */
    private fun splitJob(j: SearchJob, ctx: FrameContext): List<SearchJob>? {
        if (j.parts > 1) return null
        val (key, units) = jobUnits(j, ctx) ?: return null
        val n = cost.parts(key, units, params.relocSliceMs * 0.5)
        return if (n > 1) List(n) { j.copy(part = it, parts = n) } else null
    }

    private fun estimateJob(j: SearchJob, ctx: FrameContext): Double {
        val (key, units) = jobUnits(j, ctx) ?: return 0.0
        return units / j.parts * cost.msPerUnit(key)
    }

    /** One unit of a search (PLAN §6.2): runs on the job's map; a candidate still needs the second agreeing frame. */
    private fun runJob(j: SearchJob, ctx: FrameContext, home: MapCtx): Cand? {
        val m = ctxOf(j.mapId) ?: return null
        val cfg = params
        val sk = j.scale
        return on(m) {
            val z = matcher.localLevel(sk)
            when (j.kind) {
                // 1a. the teleport destination captured on the big map: the waypoint nearest the cursor pin
                JobKind.DEST -> destHint?.let { d ->
                    stats.local++
                    val r = matcher.localMatch(ctx, d, cfg.targetWin + 40.0, sk)
                    if (acceptLocal(r, strict = false)) Cand(r.px0, r.py0, "destination", r.score, null, sk, m) else null
                }
                // 1. last known position (IMPROVEMENT: last floor first, then the surface, then floors of a nearby entrance)
                JobKind.LAST -> pos?.let { last ->
                    val tries = ArrayList<Floor?>()
                    layer?.let { tries += it }
                    tries += null
                    if (cfg.useEntrances) {
                        for (f in linkedFloors(entrances.near(last.x, last.y, cfg.entranceRadius), last)) if (tries.none { it?.floorId == f.floorId }) tries += f
                    }
                    // the floors that hold the last position (a map fix / lock inside a zone)
                    for (f in layers.floorsAt(last.x, last.y)) if (tries.none { it?.floorId == f.floorId }) tries += f
                    var found: Cand? = null
                    for (l in tries) {
                        val ref = refOf(l, z)
                        if (l != null && ref == null) continue
                        stats.local++
                        val r = matcher.localMatch(ctx, last, cfg.relocLastRadius, sk, ref = ref)
                        if (acceptLocal(r, strict = true, floor = l != null)) { found = Cand(r.px0, r.py0, "last", r.score, l, sk, m); break }
                    }
                    found
                }
                // 2. teleport targets of this map (the banner's area first; statues / domains first on the home map)
                JobKind.TARGETS -> if (targets.size == 0) null else {
                    val same = m === home
                    val first = if (!same) null else when (relocFrom) { "DOMAIN" -> "domain"; "TRACKING" -> "statue"; else -> null }
                    val (prio, rest) = targets.order(if (same) pos else null, first, cfg.domainNear)
                    val inArea = targetsInArea(m)
                    val all = if (inArea.isNotEmpty()) {
                        val taken = inArea.toHashSet()
                        listOf(inArea, prio.filter { it !in taken }.toIntArray(), rest.filter { it !in taken }.toIntArray())
                    } else listOf(prio, rest)
                    // a cut job takes every parts-th target of each list (the priority order stays roughly intact)
                    val lists = if (j.parts > 1) all.map { l -> l.filterIndexed { i, _ -> i % j.parts == j.part }.toIntArray() } else all
                    var found: Cand? = null
                    for (idx in lists) {
                        if (idx.isEmpty()) continue
                        stats.target++
                        val tt = System.nanoTime()
                        val r = matcher.targetMatch(ctx, targets, idx, sk, cfg.targetTopK, cfg.targetWin)
                        cost.observe("T", idx.size.toDouble(), (System.nanoTime() - tt) / 1e6)
                        if (acceptLocal(r, strict = false)) { found = Cand(r.px0, r.py0, "targets", r.score, null, sk, m); break }
                    }
                    found
                }
                // IMPROVEMENT 2b: underground waypoints are only visible against a floor overlay
                JobKind.FLOOR_TARGETS -> zoneTargetsOf(m).takeIf { it.isNotEmpty() }?.let { all ->
                    val zt = if (j.parts > 1) all.filterIndexed { i, _ -> i % j.parts == j.part }.toIntArray() else all
                    stats.target++
                    val tt = System.nanoTime()
                    val r = matcher.targetMatchFloors(ctx, targets, zt, layers, sk, cfg.targetTopK, cfg.targetWin)
                    cost.observe("G", zt.size.toDouble(), (System.nanoTime() - tt) / 1e6)
                    if (acceptLocal(r, strict = false, floor = true)) Cand(r.px0, r.py0, "targets", r.score, r.extra["floor"] as? Floor, sk, m) else null
                }
                // every floor overlay as a reference of its own (an underground minimap never matches the surface), scale refined at the hit
                JobKind.FLOORS -> {
                    val chunk = floorChunk(floorsFor(m, sk, ctx.side), j)
                    if (chunk.isEmpty()) null else {
                        stats.full++
                        val tw = System.nanoTime()
                        val r = matcher.floorScan(ctx, chunk, sk, floorLevel(sk, ctx.side))
                        cost.observe("F", floorUnits(chunk, sk, ctx.side), (System.nanoTime() - tw) / 1e6)
                        val fl = r.extra["floor"] as? Floor
                        if (r.ok && fl != null && acceptLocal(r, strict = false, floor = true)) {
                            val (r2, s2) = refineFloorHit(ctx, r, fl, sk)
                            Cand(r2.px0, r2.py0, "floors", max(r2.score, r.score), fl, s2, m)
                        } else null
                    }
                }
                // 3. whole map: coarse scan at the cheapest level that still holds a template, top candidates refined
                JobKind.WHOLE -> {
                    stats.full++
                    val tw = System.nanoTime()
                    var box = if (j.boxed) areaBoxLive(m.mapId) else null
                    val zc = matcher.scanLevel(sk, ctx.side)
                    val lv = matcher.level(zc)
                    var mpx = lv.w * lv.h / 1e6
                    if (j.parts > 1) {
                        // one band of the cut job: the centre rows [y0, y1] of the box (or of the whole map), split evenly
                        val b = box ?: lv.toZ0(0.0, 0.0).let { a -> lv.toZ0(lv.w.toDouble(), lv.h.toDouble()).let { e -> doubleArrayOf(a.x, a.y, e.x, e.y) } }
                        val h = (b[3] - b[1]) / j.parts
                        box = doubleArrayOf(b[0], b[1] + j.part * h, b[2], b[1] + (j.part + 1) * h)
                    }
                    box?.let { mpx *= ((it[2] - it[0]) * (it[3] - it[1]) / (lv.w / lv.f * lv.h / lv.f)).coerceIn(0.0, 1.0) }
                    val g = if (box != null) matcher.globalMatch(ctx, sk, box = box, coarseZ = zc) else matcher.globalMatch(ctx, sk, coarseZ = zc)
                    cost.observe("W$zc", mpx, (System.nanoTime() - tw) / 1e6)
                    if (g.ok && g.score >= cfg.tauGlobal && g.margin >= cfg.tauGlobalMargin) {
                        val (r, s2) = refineHit(ctx, g, sk)
                        Cand(r.px0, r.py0, "full", maxOf(r.score, g.score), null, s2, m)
                    } else {
                        val fb = planFallback
                        if (g.ok && g.candidates.isNotEmpty() && (fb == null || g.score > fb.first.score)) planFallback = Triple(g, sk, m)
                        null
                    }
                }
            }
        }
    }

    private fun relocate(ctx: FrameContext, t: Double): TrackPose {
        val cfg = params
        if (state == TrackState.LOST) tLastFull = t
        if (cfg.skipFlat && screen.minimapIsFlat(ctx.gray8())) {
            pending = null
            return pose("flat", 0.0, false)
        }
        // --- 0. confirm a pending candidate (second agreeing result), at the scale and on the map it was found
        pending?.let { pd ->
            val s = pd.s
            val z = on(pd.map) { matcher.localLevel(s) }
            val slack = 0.6 * cfg.vmax * max(0.0, t - pendingT)           // the player keeps moving between the two results
            stats.local++
            val agree = cfg.relocAgree + slack
            if (pd.path == "features") {
                val r = on(pd.map) { matcher.featureMatch(ctx, listOf(Triple(pd.x, pd.y, 0.0)), s) }
                if (r.ok && hypot(r.px0 - pd.x, r.py0 - pd.y) <= agree) {
                    setMap(pd.map)
                    setScale(pd.s)
                    lock(P2(r.px0, r.py0), t, ctx, null)
                    updateHeading(ctx)
                    return pose("confirm", r.score, true, 0.5, pd.path)
                }
            } else {
                val r = on(pd.map) { matcher.localMatch(ctx, P2(pd.x, pd.y), cfg.confirmRadius + slack, s, ref = refOf(pd.floor, z)) }
                if (acceptLocal(r, strict = false, floor = pd.floor != null) && hypot(r.px0 - pd.x, r.py0 - pd.y) <= agree) {
                    setMap(pd.map)
                    setScale(pd.s)
                    lock(P2(r.px0, r.py0), t, ctx, pd.floor)
                    updateHeading(ctx)
                    if (r.score >= cfg.zoomLearnScore) {
                        val fl = pd.floor
                        if (fl == null) gamePos(P2(r.px0, r.py0)).also { g -> zoom.observe(g.x, g.y, pd.s) }
                        else zoom.observeFloor(fl.groupId, pd.s)                    // the floor group's own zoom, found by the floor scan
                    }
                    return pose("confirm", r.score, true, r.confidence, pd.path)
                }
            }
            pending = null
        }
        // --- 1-3. the search plan, one time-boxed slice per check (resumes on the next frame; cancelled by a new loading screen)
        val run = plan ?: newPlan(t).also { plan = it; planFallback = null }
        val home = cur
        var found: Cand? = null
        SearchSlice.run(run, cfg.relocSliceMs, System::nanoTime, { cancelFlag }, { j -> estimateJob(j, ctx) }, { j -> splitJob(j, ctx) }) { j -> runJob(j, ctx, home).also { found = it } != null }
        if (cancelFlag) {
            plan = null; cancelFlag = false
            return pose("none", 0.0, false)
        }
        var cand = found
        if (cand == null && run.done) {
            plan = null
            nFail++
            val fb = planFallback
            planFallback = null
            if (cfg.featureFallback && fb != null) {
                val (g, sk, fm) = fb
                val f = on(fm) { matcher.featureMatch(ctx, g.candidates.take(cfg.globalTopK), sk) }
                if (f.ok) cand = Cand(f.px0, f.py0, "features", f.score, null, sk, fm)
            }
        }
        val c = cand ?: return pose("none", 0.0, false)
        plan = null
        pending = Pending(c.x, c.y, c.path, c.floor, c.s, c.map)
        pendingT = t
        return pose(c.path, c.score, false)           // first result only: wait for the second frame to agree
    }

    // ----------------------------------------------------------------------------- zoom learning, hints, map fix
    /** An accepted local check: after [TrackerParams.zoomLearnChecks] good ones in a row the scale is recorded for the position's region (EMA). */
    private fun learnZoom(p: P2, score: Double, t: Double) {
        if (!params.dynamicScale || scalePending != null || score < params.zoomLearnScore) { learnN = 0; return }
        learnN++
        if (learnN >= params.zoomLearnChecks) {
            val fl = layer
            if (fl != null) zoom.observeFloor(fl.groupId, curS())
            else {
                val g = gamePos(p)
                zoom.observe(g.x, g.y, curS())
            }
        }
    }

    /** The waypoint the user picked on the big map (zoom-0 px). Consumed by the next relocation after a loading screen (valid for [DEST_HINT_MAX] s after the loading). */
    fun hintDestination(p: P2?, t: Double, forMap: Int = cur.mapId) { destHint = p; destHintT = t; destMap = forMap }

    /** Area-name banner matched an anchor: relocation / lost searches start inside [box] (z0 px l, t, r, b) and use that region's zoom. */
    fun hintArea(box: DoubleArray?, anchorId: Int?, forMap: Int = cur.mapId) {
        areaBox = box; areaAnchor = anchorId; areaMap = forMap; areaSetT = System.nanoTime() / 1e9
        // preload the region's stored zoom: the next local check tests it besides the current scale
        if (anchorId != null && forMap == cur.mapId) zoom.entry(anchorId)?.scale?.let { if (!close(it, curS(), 0.015)) preloadScale = it }
    }

    /**
     * Exact position from the open big map (PLAN §6.0): state FIXED until the minimap returns, then a local scale sweep at this
     * exact spot learns the region's zoom ([learnAfterFix]).
     */
    fun mapFix(p: P2, t: Double, headingDeg: Double? = null, forMap: Int = cur.mapId, floorGroup: Int? = null, floor: Floor? = null, surface: Boolean = false, pin: Boolean = false): TrackPose {
        ctxOf(forMap)?.let { setMap(it) }
        setPos(p, t)
        fixGroup = floor?.groupId ?: floorGroup
        fixFloor = floor
        fixTries = 0
        state = TrackState.FIXED
        hiddenRet = "track"
        pending = null
        plan = null; planFallback = null
        nFail = 0
        miss = 0
        learnAfterFix = p
        when {
            floor != null -> {                           // the selector told the floor: it is the layer from now on, at the group's learned zoom
                layer = floor; layerPending = null; avg = null; layerAfterLock = false
                if (pin) { pinnedFloor = floor; bigDecision = floor to p }
                zoom.floorScale(floor.groupId)?.let { setScale(it) }
            }
            floorGroup != null -> layer = null           // decided when the minimap is back (the fix knows the group, not the floor)
            surface -> { layer = null; layerPending = null; avg = null; pinnedFloor = null; bigDecision = null }
        }
        if (headingDeg != null) heading = hs.update(headingDeg)
        return pose("map", 1.0, true, 1.0, "bigmap")
    }

    /**
     * May a big-map fix at [p] (zoom-0 px of map [forMap]; the caller found the player arrow) become the position? See [BigFixRules]:
     * panning the big map must not move the player, so far fixes need a loading screen (RELOCATING / LOST) first.
     */
    fun mapFixAcceptable(p: P2, forMap: Int, strong: Boolean = false): Boolean {
        if (strong) return true           // a many-inlier registration with the player arrow is authoritative: the arrow is the player, panning does not move it
        val here = pos
        val same = forMap == cur.mapId
        val d = if (here != null && same) hypot(p.x - here.x, p.y - here.y) else Double.MAX_VALUE
        return BigFixRules.accept(true, state, hiddenRet, here != null, same, d, params.bigFixMaxJump)
    }

    /** State of the fix gate for the log: why a registration at [p] was (not) accepted. */
    fun mapFixGateInfo(p: P2, forMap: Int): String {
        val here = pos
        val d = if (here != null && forMap == cur.mapId) hypot(p.x - here.x, p.y - here.y) else -1.0
        return "state=$state hiddenRet=$hiddenRet hasPos=${here != null} sameMap=${forMap == cur.mapId} dist=%.0f max=%.0f".format(d, params.bigFixMaxJump)
    }

    fun close() {
        lastHeat?.release(); lastHeat = null
        lastContext?.close(); lastContext = null
        loaded.values.forEach { it?.close() }; loaded.clear()
    }
}
