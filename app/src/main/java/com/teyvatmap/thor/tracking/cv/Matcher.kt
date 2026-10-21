package com.teyvatmap.thor.tracking.cv

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.features.BFMatcher
import org.opencv.features.ORB
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One match: position in continuous zoom-0 px plus the peak and runner-up scores. */
class MatchResult(
    val ok: Boolean = false,
    val px0: Double = 0.0,
    val py0: Double = 0.0,
    val score: Double = 0.0,
    var second: Double = 0.0,
    val z: Int = 0,
    var ms: Double = 0.0,
    val s: Double? = null,
    var candidates: List<Triple<Double, Double, Double>> = emptyList(),
    /** Score map (CV_32F) when requested; the owner releases it. */
    var heat: Mat? = null,
    val extra: MutableMap<String, Any> = HashMap(),
) {
    val margin: Double get() = score - second

    /** 0..1: needs a high peak that clearly stands out from the runner-up. */
    val confidence: Double
        get() = ((score - 0.25) / 0.45).coerceIn(0.0, 1.0) * (margin / 0.08).coerceIn(0.0, 1.0)
}

/** Accumulated wall time per stage since the last [take] (shown in the debug view). */
class StageTimes {
    var localMs = 0.0
    var targetMs = 0.0
    var coarseMs = 0.0
    var refineMs = 0.0
    var featureMs = 0.0
    var layerMs = 0.0
    var headingMs = 0.0
    var templateMs = 0.0

    fun take(): Map<String, Double> {
        val m = linkedMapOf(
            "template" to templateMs, "local" to localMs, "layers" to layerMs, "targets" to targetMs,
            "coarse" to coarseMs, "refine" to refineMs, "features" to featureMs, "heading" to headingMs,
        )
        localMs = 0.0; targetMs = 0.0; coarseMs = 0.0; refineMs = 0.0; featureMs = 0.0
        layerMs = 0.0; headingMs = 0.0; templateMs = 0.0
        return m
    }
}

/**
 * Map matching: local tracking, whole-map relocation, teleport-target batch, feature fallback and scale
 * calibration. Port of tools/cv/match.py. All positions are zoom-0 px (px0, py0). Not thread-safe: call from the
 * tracker thread only (the whole-map search fans out internally over [TrackerParams.searchThreads]).
 */
class Matcher(val index: CvIndex, var params: TrackerParams) : AutoCloseable {
    val times = StageTimes()
    private val geo get() = index.geo
    private val pool: ExecutorService = Executors.newFixedThreadPool(params.searchThreads.coerceIn(1, 8)) { r ->
        Thread(r, "cv-search").also { it.isDaemon = true }
    }

    private class Scratch {
        val win = Mat()
        val res = Mat()
        val mask = Mat()
        fun release() { win.release(); res.release(); mask.release() }
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }
    private val mainScratch = Scratch()
    private val peakBuf = FloatArray(1)

    fun localLevel(s: Double): Int = levelForScale(s, -2, 0)

    /** Coarse whole-map level: z-2 normally, z-1 when the minimap is zoomed in (s >= 1.5: template 60 px instead of 30). */
    fun coarseLevel(s: Double): Int = if (s >= params.coarseZoomIn && index.hasLevel(params.coarseZ + 1)) params.coarseZ + 1 else params.coarseZ

    fun level(z: Int): Level = index.prep(z).level

    /** Level of the whole-map scale scan ([RelocPlan.scanLevel]), falling back to [coarseLevel] when that level was not built. */
    fun scanLevel(s: Double, cropSide: Int): Int =
        RelocPlan.scanLevel(s, cropSide, params.scanMinTemplate).takeIf { index.hasLevel(it) } ?: coarseLevel(s)

    // ---------------------------------------------------------------------------------------- helpers
    private fun px(res: Mat, r: Int, c: Int): Double {
        res.get(r, c, peakBuf)
        return peakBuf[0].toDouble()
    }

    private fun parabola(a: Double, b: Double, c: Double): Double {
        val d = a - 2 * b + c
        return if (abs(d) < 1e-9) 0.0 else (0.5 * (a - c) / d).coerceIn(-1.0, 1.0)
    }

    /** Sub-pixel peak via 1-D parabolas (clamped at borders). Returns (x, y) in result coordinates. */
    private fun refinePeak(res: Mat, iy: Int, ix: Int): P2 {
        val h = res.rows()
        val w = res.cols()
        var dx = 0.0
        var dy = 0.0
        val b = px(res, iy, ix)
        if (ix > 0 && ix < w - 1) dx = parabola(px(res, iy, ix - 1), b, px(res, iy, ix + 1))
        if (iy > 0 && iy < h - 1) dy = parabola(px(res, iy - 1, ix), b, px(res, iy + 1, ix))
        return P2(ix + dx, iy + dy)
    }

    /** Best score outside the disk of radius [rad] around the peak (the result map is restored afterwards). */
    private fun secondPeak(res: Mat, iy: Int, ix: Int, rad: Int): Double = mats { sc ->
        val y0 = max(0, iy - rad); val y1 = min(res.rows(), iy + rad + 1)
        val x0 = max(0, ix - rad); val x1 = min(res.cols(), ix + rad + 1)
        val sub = sc.track(res.submat(y0, y1, x0, x1))
        val saved = sc.mat()
        sub.copyTo(saved)
        val h = y1 - y0
        val w = x1 - x0
        val m = ByteArray(h * w)
        for (yy in 0 until h) for (xx in 0 until w) {
            val dy = yy + y0 - iy
            val dx = xx + x0 - ix
            if (dy * dy + dx * dx <= rad * rad) m[yy * w + xx] = 1
        }
        val mask = sc.mat(h, w, CvType.CV_8UC1)
        mask.put(0, 0, m)
        sub.setTo(Scalar(-1.0), mask)
        val v = Core.minMaxLoc(res).maxVal
        saved.copyTo(sub)
        max(v, -1.0)
    }

    /** Masked NCC of [tm] over the window (x0, y0, w, h) of [P]; null when the window is smaller than the template. */
    private fun nccWindow(P: Mat, tm: Template, x0: Int, y0: Int, w: Int, h: Int, sc: Scratch): IntArray? {
        val xa = max(0, x0); val ya = max(0, y0)
        val xb = min(P.cols(), x0 + w); val yb = min(P.rows(), y0 + h)
        if (xb - xa < tm.t || yb - ya < tm.t) return null
        val view = P.submat(ya, yb, xa, xb)
        view.convertTo(sc.win, CvType.CV_32F, 1.0, -128.0)
        view.release()
        Imgproc.matchTemplate(sc.win, tm.img, sc.res, Imgproc.TM_CCORR_NORMED, tm.mask)
        // like np.nan_to_num: flat windows give 0/0 (NaN) or x/0 (+-inf)
        Core.patchNaNs(sc.res, 0.0)
        Core.compare(sc.res, Scalar(1e30), sc.mask, Core.CMP_GT)
        sc.res.setTo(Scalar(0.0), sc.mask)
        Core.compare(sc.res, Scalar(-1e30), sc.mask, Core.CMP_LT)
        sc.res.setTo(Scalar(0.0), sc.mask)
        // float instability on (nearly) flat windows: a normalised correlation cannot exceed 1 (real_report.md item 4)
        Core.compare(sc.res, Scalar(MAX_NCC), sc.mask, Core.CMP_GT)
        sc.res.setTo(Scalar(0.0), sc.mask)
        return intArrayOf(xa, ya)
    }

    private fun finish(res: Mat, ox: Int, oy: Int, tm: Template, lv: Level, z: Int, t0: Long, keepHeat: Boolean, s: Double): MatchResult {
        val mm = Core.minMaxLoc(res)
        val ix = mm.maxLoc.x.toInt()
        val iy = mm.maxLoc.y.toInt()
        val score = mm.maxVal
        val nms = max(3, tm.t / 6)
        val sec = secondPeak(res, iy, ix, nms)
        val f = refinePeak(res, iy, ix)
        val u = ox + f.x + tm.t / 2.0
        val v = oy + f.y + tm.t / 2.0
        val p = lv.toZ0(u, v)
        val heat = if (keepHeat) Mat().also { res.copyTo(it) } else null
        return MatchResult(
            true, p.x, p.y, score, sec, z, ms(t0), s, listOf(Triple(p.x, p.y, score)), heat,
            hashMapOf<String, Any>("ox" to ox, "oy" to oy),
        )
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    // -------------------------------------------------------------------------------------- (a) local
    /**
     * Masked NCC in a window around [prior] (template-centre search radius in z0 px). [ref] matches against another
     * reference (a floor overlay) instead of the surface.
     */
    fun localMatch(
        ctx: FrameContext, prior: P2, radiusZ0: Double, s: Double = ctx.calib.s, tmpl: Template? = null, z: Int? = null,
        keepHeat: Boolean = false, ref: RefImage? = null,
    ): MatchResult {
        val t0 = System.nanoTime()
        val zz = z ?: localLevel(s)
        val tm = tmpl ?: timed { ctx.template(zz, s) }
        val r = ref ?: index.prep(zz)
        val lv = r.level
        val c = lv.fromZ0(prior.x, prior.y)
        val rad = ceil(radiusZ0 * lv.f).toInt()
        val x0 = floor(c.x - tm.t / 2.0).toInt() - rad
        val y0 = floor(c.y - tm.t / 2.0).toInt() - rad
        val n = tm.t + 2 * rad + 1
        val o = nccWindow(r.mat, tm, x0, y0, n, n, mainScratch)
        val out = if (o == null) MatchResult(false, z = zz, ms = ms(t0), s = s)
        else finish(mainScratch.res, o[0], o[1], tm, lv, zz, t0, keepHeat, s)
        times.localMs += ms(t0)
        return out
    }

    private inline fun <T> timed(block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        times.templateMs += ms(t0)
        return r
    }

    // ------------------------------------------------------------------------------------- (b) global
    private class Cand(val score: Double, val u: Double, val v: Double, val iy: Int, val ix: Int)

    /** Whole-land masked NCC at the coarse level -> up to [topk] (score, u, v) level-px template centres. */
    fun coarseSearch(tm: Template, topk: Int, threads: Int = params.searchThreads, z: Int = params.coarseZ, box: DoubleArray? = null): List<Triple<Double, Double, Double>> {
        val t0 = System.nanoTime()
        val ref = index.prep(z)
        val P = ref.mat
        val W = P.cols()
        val T = tm.t
        val rows = P.rows() - T + 1
        if (rows <= 0 || W < T) return emptyList()
        val nms = max(T * 0.6, 6.0).toInt()
        // optional search box (z0 px l, t, r, b: where the template CENTRE may lie): only that part of the map is correlated
        var xa0 = 0; var xw = W; var rowLo = 0; var rowHi = rows
        if (box != null) {
            val lv = ref.level
            val c0 = lv.fromZ0(box[0], box[1]); val c1 = lv.fromZ0(box[2], box[3])
            xa0 = max(0, floor(c0.x - T / 2.0).toInt()); xw = min(W - xa0, ceil(c1.x - c0.x).toInt() + T)
            rowLo = max(0, floor(c0.y - T / 2.0).toInt()); rowHi = min(rows, ceil(c1.y - T / 2.0).toInt() + 1)
            if (xw < T || rowHi <= rowLo) return emptyList()
        }
        val jobs = ArrayList<Callable<List<Cand>>>()
        var ya = rowLo
        while (ya < rowHi) {
            val a = ya
            val b = min(rowHi, ya + STRIPE)
            jobs += Callable { stripe(P, tm, a, b, rowHi, nms, topk + 2, xa0, xw) }
            ya = b
        }
        val all = if (threads <= 1 || jobs.size == 1) jobs.flatMap { it.call() } else pool.invokeAll(jobs).flatMap { it.get() }
        val picked = ArrayList<Cand>()
        for (c in all.sortedByDescending { it.score }) {
            if (picked.size >= topk) break
            if (picked.none { abs(it.iy - c.iy) <= nms && abs(it.ix - c.ix) <= nms }) picked += c
        }
        times.coarseMs += ms(t0)
        return picked.map { Triple(it.score, it.u, it.v) }
    }

    private fun stripe(P: Mat, tm: Template, ya: Int, yb: Int, rows: Int, nms: Int, k: Int, xa0: Int = 0, xw: Int = P.cols()): List<Cand> {
        val sc = scratch.get()!!
        val ra = max(0, ya - 1)
        val rb = min(rows, yb + 1)
        val T = tm.t
        val o = nccWindow(P, tm, xa0, ra, xw, rb - ra + T - 1, sc) ?: return emptyList()
        val res = sc.res
        val out = ArrayList<Cand>()
        val buf = FloatArray(1)
        fun at(r: Int, c: Int): Double { res.get(r, c, buf); return buf[0].toDouble() }
        var n = 0
        while (n < k + 4) {
            val mm = Core.minMaxLoc(res)
            val score = mm.maxVal
            if (score <= 0.05) break
            val ix = mm.maxLoc.x.toInt()
            val iy = mm.maxLoc.y.toInt()
            val gy = iy + ra
            if (gy in ya until yb) {
                var dx = 0.0
                var dy = 0.0
                if (ix > 0 && ix < res.cols() - 1) dx = parabola(at(iy, ix - 1), score, at(iy, ix + 1))
                if (iy > 0 && iy < res.rows() - 1) dy = parabola(at(iy - 1, ix), score, at(iy + 1, ix))
                out += Cand(score, o[0] + ix + dx + T / 2.0, gy + dy + T / 2.0, gy, o[0] + ix)
                if (out.size >= k) break
            }
            val view = res.submat(max(0, iy - nms), min(res.rows(), iy + nms + 1), max(0, ix - nms), min(res.cols(), ix + nms + 1))
            view.setTo(Scalar(-1.0))
            view.release()
            n++
        }
        return out
    }

    /** Whole-map search: coarse pass, then the top [topk] candidates refined at the working level. */
    fun globalMatch(
        ctx: FrameContext, s: Double = ctx.calib.s, topk: Int = params.globalTopK, refineRadiusZ0: Double = params.refineRadius, box: DoubleArray? = null,
        coarseZ: Int? = null,
    ): MatchResult {
        val t0 = System.nanoTime()
        val zc = coarseZ?.takeIf { index.hasLevel(it) } ?: coarseLevel(s)
        val zf = localLevel(s)
        val tmC = timed { ctx.template(zc, s) }
        val cands = coarseSearch(tmC, topk, z = zc, box = box)
        val lvc = level(zc)
        val tmF = timed { ctx.template(zf, s) }
        val tr = System.nanoTime()
        var best: MatchResult? = null
        val allc = ArrayList<Triple<Double, Double, Double>>()
        for ((sc, u, v) in cands) {
            val prior = lvc.toZ0(u, v)
            val rad = max(refineRadiusZ0, 2.5 * Math.pow(2.0, -zc.toDouble()))
            val r = localMatch(ctx, prior, rad, s, tmF, zf)
            if (!r.ok) continue
            r.extra["coarse_score"] = sc
            allc += Triple(r.px0, r.py0, r.score)
            if (best == null || r.score > best.score) best = r
        }
        times.refineMs += ms(tr)
        val b = best ?: return MatchResult(false, ms = ms(t0), s = s)
        val thr = 0.3 * tmF.t / Math.pow(2.0, zf.toDouble())
        val others = allc.filter { hypot(it.first - b.px0, it.second - b.py0) > thr }.map { it.third }
        if (others.isNotEmpty()) b.second = max(others.max(), 0.0)
        b.candidates = allc.sortedByDescending { it.third }
        b.ms = ms(t0)
        return b
    }

    // ------------------------------------------------------------------------- (b2) teleport targets
    /** Scores a tiny window (+-[winZ0] z0 px) around every target at the target level, refines the top [topk]. */
    fun targetMatch(
        ctx: FrameContext, targets: Targets, idx: IntArray, s: Double = ctx.calib.s, topk: Int = params.targetTopK,
        winZ0: Double = params.targetWin, zT: Int = params.targetZ, refineRadiusZ0: Double = params.refineRadius,
    ): MatchResult {
        val t0 = System.nanoTime()
        if (idx.isEmpty()) return MatchResult(false, s = s)
        val zf = localLevel(s)
        val tmT = timed { ctx.template(zT, s) }
        val ref = index.prep(zT)
        val lv = ref.level
        val T = tmT.t
        val rad = ceil(winZ0 * lv.f).toInt()
        val n = T + 2 * rad + 1

        class One(val score: Double, val u: Double, val v: Double)

        fun one(i: Int, sc: Scratch): One {
            val c = lv.fromZ0(targets.x(i), targets.y(i))
            val x0 = floor(c.x - T / 2.0).toInt() - rad
            val y0 = floor(c.y - T / 2.0).toInt() - rad
            val o = nccWindow(ref.mat, tmT, x0, y0, n, n, sc) ?: return One(-1.0, 0.0, 0.0)
            val mm = Core.minMaxLoc(sc.res)
            return One(mm.maxVal, o[0] + mm.maxLoc.x + T / 2.0, o[1] + mm.maxLoc.y + T / 2.0)
        }

        val out: List<One> = if (params.searchThreads > 1 && idx.size > 32) {
            val chunk = ceil(idx.size / params.searchThreads.toDouble()).toInt().coerceAtLeast(8)
            val jobs = idx.toList().chunked(chunk).map { part -> Callable { part.map { one(it, scratch.get()!!) } } }
            pool.invokeAll(jobs).flatMap { it.get() }
        } else {
            idx.map { one(it, mainScratch) }
        }
        val order = out.indices.sortedByDescending { out[it].score }.take(topk)
        val tmF = timed { ctx.template(zf, s) }
        val tr = System.nanoTime()
        var best: MatchResult? = null
        val allc = ArrayList<Triple<Double, Double, Double>>()
        for (i in order) {
            val o = out[i]
            if (o.score <= 0.05) continue
            val prior = lv.toZ0(o.u, o.v)
            val r = localMatch(ctx, prior, refineRadiusZ0, s, tmF, zf)
            if (!r.ok) continue
            r.extra["coarse_score"] = o.score
            r.extra["target"] = idx[i]
            allc += Triple(r.px0, r.py0, r.score)
            if (best == null || r.score > best.score) best = r
        }
        times.refineMs += ms(tr)
        val b = best ?: return MatchResult(false, ms = ms(t0), s = s).also { times.targetMs += ms(t0) }
        val thr = 0.3 * tmF.t / Math.pow(2.0, zf.toDouble())
        val others = allc.filter { hypot(it.first - b.px0, it.second - b.py0) > thr }.map { it.third }
        if (others.isNotEmpty()) b.second = others.max()
        b.candidates = allc.sortedByDescending { it.third }
        b.ms = ms(t0)
        times.targetMs += ms(t0)
        return b
    }

    /**
     * IMPROVEMENT over the Python reference: targets that lie inside a layer zone are also scored against that
     * zone's floor overlays, and the underground Teleport Waypoints (targets listed in a floor's `entrance_ids`)
     * against their linked floor first, so teleporting straight into a cave relocates. The result carries the
     * matched floor in `extra["floor"]`.
     */
    fun targetMatchFloors(
        ctx: FrameContext, targets: Targets, idx: IntArray, layers: LayerIndex, s: Double = ctx.calib.s,
        topk: Int = params.targetTopK, winZ0: Double = params.targetWin,
    ): MatchResult {
        val t0 = System.nanoTime()
        val zf = localLevel(s)
        class Pair2(val i: Int, val floor: Floor, val r: MatchResult)

        val results = ArrayList<Pair2>()
        val tm = timed { ctx.template(zf, s) }
        for (i in idx) {
            val linked = targets.linkedFloor(i).takeIf { it > 0 }?.let { layers.byId[it] }
            val fls = (listOfNotNull(linked) + layers.floorsAt(targets.x(i), targets.y(i), winZ0)).distinctBy { it.floorId }
            for (f in fls) {
                val ref = index.floorRef(f.floorId, zf) ?: continue
                val r = localMatch(ctx, P2(targets.x(i), targets.y(i)), winZ0, s, tm, zf, ref = ref)
                if (r.ok) results += Pair2(i, f, r)
            }
        }
        val top = results.sortedByDescending { it.r.score }
        val best = top.firstOrNull() ?: return MatchResult(false, ms = ms(t0), s = s).also { times.targetMs += ms(t0) }
        val thr = 0.3 * tm.t / Math.pow(2.0, zf.toDouble())
        val others = top.filter { hypot(it.r.px0 - best.r.px0, it.r.py0 - best.r.py0) > thr }.take(topk).map { it.r.score }
        best.r.second = others.maxOrNull() ?: best.r.second
        best.r.candidates = top.take(topk).map { Triple(it.r.px0, it.r.py0, it.r.score) }
        best.r.extra["floor"] = best.floor
        best.r.extra["target"] = best.i
        best.r.ms = ms(t0)
        times.targetMs += ms(t0)
        return best.r
    }

    /**
     * Scan of underground floors: every overlay is its own small map (the minimap inside a cave or fortress shows only that drawing, the
     * surface tiles never match it). [floors] are matched at scale [s] on the coarse floor level [zc] (z-2..0) inside their own bounds only
     * (not the padded surface filler: the player is on the drawing), the two best floors are refined at the working level. The result carries the
     * floor in `extra["floor"]`; its runner-up also counts the best floor of another group (a confusable drawing elsewhere).
     */
    fun floorScan(ctx: FrameContext, floors: List<Floor>, s: Double, zc: Int): MatchResult {
        val t0 = System.nanoTime()
        val zf = localLevel(s)
        val tmC = timed { ctx.template(zc, s) }
        class Hit(val floor: Floor, val r: MatchResult)

        val hits = ArrayList<Hit>(floors.size)
        for (f in floors) {
            val ref = index.floorRef(f.floorId, zc) ?: continue
            val c = P2((f.l + f.r) / 2, (f.t + f.b) / 2)
            val rad = max(f.r - f.l, f.b - f.t) / 2 + 4.0
            val r = localMatch(ctx, c, rad, s, tmC, zc, ref = ref)
            if (r.ok) hits += Hit(f, r)
        }
        val top = hits.sortedByDescending { it.r.score }
        val first = top.firstOrNull() ?: return MatchResult(false, ms = ms(t0), s = s)
        var best = first
        var bestR = first.r
        if (zf != zc) {
            val tmF = timed { ctx.template(zf, s) }
            val rad = max(params.refineRadius, 2.5 * Math.pow(2.0, -zc.toDouble()))
            var refined: Pair<Hit, MatchResult>? = null
            for (h in top.take(2)) {
                val ref = index.floorRef(h.floor.floorId, zf) ?: continue
                val r = localMatch(ctx, P2(h.r.px0, h.r.py0), rad, s, tmF, zf, ref = ref)
                if (r.ok && (refined == null || r.score > refined.second.score)) refined = h to r
            }
            refined?.let { best = it.first; bestR = it.second }
        }
        val others = top.filter { it.floor.groupId != best.floor.groupId }.maxOfOrNull { it.r.score } ?: 0.0
        bestR.second = max(bestR.second, max(best.r.second, others))
        bestR.candidates = top.take(params.targetTopK).map { Triple(it.r.px0, it.r.py0, it.r.score) }
        bestR.extra["floor"] = best.floor
        bestR.ms = ms(t0)
        return bestR
    }

    // ------------------------------------------------------------------------------------ (c) features
    /** ORB + RANSAC on candidate regions of the prepared map; returns the best by inliers. */
    fun featureMatch(ctx: FrameContext, cands: List<Triple<Double, Double, Double>>, s: Double = ctx.calib.s, z: Int? = null, margin: Double = 1.6): MatchResult {
        val t0 = System.nanoTime()
        val zz = z ?: localLevel(s)
        val ref = index.prep(zz)
        val lv = ref.level
        val P = ref.mat
        val tm = ctx.template(zz, s)
        var best = MatchResult(false, z = zz, s = s)
        mats { sc ->
            val timg = sc.mat()
            tm.img.convertTo(timg, CvType.CV_8U, 1.0, 128.0)
            val tmask = sc.mat()
            val m8 = sc.mat()
            tm.mask.convertTo(m8, CvType.CV_8U, 255.0)
            Imgproc.erode(m8, tmask, Mat.ones(5, 5, CvType.CV_8U).also { sc.track(it) })
            val det = ORB.create(1500, 1.2f, 4, 15, 0, 2, ORB.HARRIS_SCORE, 31, 15)
            val kt = MatOfKeyPoint().also { sc.track(it) }
            val dt = sc.mat()
            det.detectAndCompute(timg, tmask, kt, dt)
            if (dt.empty() || kt.rows() < 8) {
                best = MatchResult(false, z = zz, s = s, ms = ms(t0))
                return@mats
            }
            val bf = BFMatcher.create(Core.NORM_HAMMING)
            val ktl = kt.toArray()
            var bestInl = 0
            for ((cx, cy, _) in cands) {
                val c = lv.fromZ0(cx, cy)
                val half = (tm.t * margin / 2).toInt()
                val x0 = c.x.toInt() - half
                val y0 = c.y.toInt() - half
                val xa = max(0, x0); val ya = max(0, y0)
                val xb = min(P.cols(), x0 + 2 * half); val yb = min(P.rows(), y0 + 2 * half)
                if (xb - xa < tm.t || yb - ya < tm.t) continue
                val win = sc.track(P.submat(ya, yb, xa, xb))
                val kw = MatOfKeyPoint().also { sc.track(it) }
                val dw = sc.mat()
                det.detectAndCompute(win, Mat(), kw, dw)
                if (dw.empty() || kw.rows() < 8) continue
                val knn = ArrayList<MatOfDMatch>()
                bf.knnMatch(dt, dw, knn, 2)
                val good = ArrayList<DMatch>()
                for (mm in knn) {
                    val a = mm.toArray()
                    if (a.size == 2 && a[0].distance < 0.8f * a[1].distance) good += a[0]
                }
                knn.forEach { it.release() }
                if (good.size < 8) continue
                val kwl = kw.toArray()
                val src = MatOfPoint2f(*good.map { ktl[it.queryIdx].pt }.toTypedArray()).also { sc.track(it) }
                val dst = MatOfPoint2f(*good.map { kwl[it.trainIdx].pt }.toTypedArray()).also { sc.track(it) }
                val inl = sc.mat()
                val A = Geometry.estimateAffinePartial2D(src, dst, inl, Geometry.RANSAC, 3.0)
                sc.track(A)
                if (A.empty()) continue
                val a = DoubleArray(6)
                A.get(0, 0, a)
                val scl = hypot(a[0], a[3])
                val rot = Math.toDegrees(atan2(a[3], a[0]))
                if (!(scl > 0.9 && scl < 1.1) || abs(rot) > 6) continue
                val nIn = Core.countNonZero(inl)
                if (nIn > bestInl) {
                    bestInl = nIn
                    val ccx = a[0] * (tm.t / 2.0) + a[1] * (tm.t / 2.0) + a[2]
                    val ccy = a[3] * (tm.t / 2.0) + a[4] * (tm.t / 2.0) + a[5]
                    val pz = lv.toZ0(xa + ccx, ya + ccy)
                    best = MatchResult(
                        nIn >= 10, pz.x, pz.y, min(1.0, nIn / 40.0), 0.0, zz, 0.0, s,
                        listOf(Triple(pz.x, pz.y, nIn.toDouble())), null, hashMapOf<String, Any>("inliers" to nIn),
                    )
                }
            }
        }
        best.ms = ms(t0)
        times.featureMs += best.ms
        return best
    }

    // ------------------------------------------------------------------------------ (d) scale calibration
    class ScaleEstimate(val s: Double, val match: MatchResult, val table: List<Pair<Double, Double>>)

    private fun refineScale(ctx: FrameContext, s0: Double, pos0: P2, rad0: Double, step0: Double, z: Int?, iters: Int = 3): Pair<Double, MatchResult> {
        var cur = s0
        var pos = pos0
        var step = step0
        var best: MatchResult? = null
        for (it in 0 until iters) {
            val fine = DoubleArray(7) { k ->
                val a = ln(cur) - ln(step) * 0.9
                val b = ln(cur) + ln(step) * 0.9
                exp(a + (b - a) * k / 6.0)
            }
            val zz0 = z ?: localLevel(cur)
            val rs = fine.map { s -> s to localMatch(ctx, pos, if (it == 0) rad0 else 10.0, s, z = zz0) }
            val sc = rs.map { if (it.second.ok) it.second.score else 0.0 }
            val k = sc.indices.maxBy { sc[it] }
            cur = if (k in 1 until rs.size - 1) {
                val ls0 = ln(rs[0].first)
                val ls1 = ln(rs[1].first)
                exp(ln(rs[k].first) + parabola(sc[k - 1], sc[k], sc[k + 1]) * (ls1 - ls0))
            } else rs[k].first
            best = rs[k].second
            pos = P2(best.px0, best.py0)
            step = Math.pow(step, 0.35)
        }
        val zz = z ?: localLevel(cur)
        val final = localMatch(ctx, pos, 12.0, cur, z = zz)
        return cur to final
    }

    /**
     * Multi-scale search. Without [hint]: coarse whole-map search per scale, the best few distinct hypotheses are
     * refined at the working level and the highest fine score wins. With [hint] (px0, py0): a local scan over the grid.
     */
    fun estimateScale(
        base: FrameContext, sRange: Pair<Double, Double> = 0.3 to 1.5, nCoarse: Int = 14,
        hint: P2? = null, hintRadiusZ0: Double = 400.0, nVerify: Int = 5, progress: (String) -> Unit = {},
    ): ScaleEstimate? {
        val grid = DoubleArray(nCoarse) { exp(ln(sRange.first) + (ln(sRange.second) - ln(sRange.first)) * it / (nCoarse - 1)) }
        val step = grid[1] / grid[0]
        if (hint != null) {
            val table = grid.map { s ->
                val r = localMatch(base, hint, hintRadiusZ0, s, z = localLevel(s))
                s to (if (r.ok) r.score else 0.0)
            }
            val s0 = table.maxBy { it.second }.first
            val (cur, final) = refineScale(base, s0, hint, hintRadiusZ0, step, null)
            return ScaleEstimate(cur, final, table)
        }
        val zc = params.coarseZ
        val hyps = ArrayList<Triple<Double, Double, P2>>() // score, s, level px
        val table = ArrayList<Pair<Double, Double>>()
        val lvc = level(zc)
        for ((i, s) in grid.withIndex()) {
            progress("scale ${i + 1}/${grid.size}")
            val tm = base.template(zc, s)
            val c = coarseSearch(tm, 3, threads = params.searchThreads)
            table += s to (c.firstOrNull()?.first ?: 0.0)
            c.forEach { hyps += Triple(it.first, s, P2(it.second, it.third)) }
        }
        val picked = ArrayList<Triple<Double, Double, P2>>()
        for (h in hyps.sortedByDescending { it.first }) {
            val pz = lvc.toZ0(h.third.x, h.third.y)
            if (picked.all { hypot(pz.x - lvc.toZ0(it.third.x, it.third.y).x, pz.y - lvc.toZ0(it.third.x, it.third.y).y) > 30 }) picked += h
            if (picked.size >= nVerify) break
        }
        val rad0 = 2.5 * Math.pow(2.0, -zc.toDouble()) + 6
        var best: Pair<Double, MatchResult>? = null
        for ((i, h) in picked.withIndex()) {
            progress("verify ${i + 1}/${picked.size}")
            val pz = lvc.toZ0(h.third.x, h.third.y)
            val (cur, final) = refineScale(base, h.second, pz, rad0, step, null)
            if (final.ok && (best == null || final.score > best.second.score)) best = cur to final
        }
        val b = best ?: return picked.firstOrNull()?.let { ScaleEstimate(it.second, MatchResult(false), table) }
        return ScaleEstimate(b.first, b.second, table)
    }

    override fun close() {
        pool.shutdownNow()
        mainScratch.release()
    }

    companion object {
        const val STRIPE = 512
        const val MAX_NCC = 1.001
    }
}
