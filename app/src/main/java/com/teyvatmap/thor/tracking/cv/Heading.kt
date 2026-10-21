package com.teyvatmap.thor.tracking.cv

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Player-arrow heading from the gold arrow in the centre of the minimap (port of tools/cv/heading.py). */
object Heading {
    class Result(val deg: Double?, val confidence: Double)

    private val none = Result(null, 0.0)

    /** HSV (OpenCV hue 0..180) seeds: strict and loose thresholds of one arrow colour. */
    private class Hue(val strictLo: Scalar, val strictHi: Scalar, val looseLo: Scalar, val looseHi: Scalar)

    // the arrow was gold in the synthetic frames and is light blue / cyan on the real Thor; the last entry accepts any
    // strongly saturated bright blob in the centre, whatever its hue
    private val hues = listOf(
        Hue(Scalar(12.0, 135.0, 165.0), Scalar(36.0, 255.0, 255.0), Scalar(8.0, 115.0, 130.0), Scalar(38.0, 255.0, 255.0)),
        Hue(Scalar(80.0, 70.0, 170.0), Scalar(110.0, 255.0, 255.0), Scalar(76.0, 50.0, 140.0), Scalar(114.0, 255.0, 255.0)),
        Hue(Scalar(0.0, 135.0, 165.0), Scalar(180.0, 255.0, 255.0), Scalar(0.0, 115.0, 130.0), Scalar(180.0, 255.0, 255.0)),
    )

    /** 0 = north/up, clockwise; tries gold, then cyan, then any saturated hue. [crop] is the square BGR minimap crop. */
    fun estimate(crop: Mat, radFrac: Double = 0.22): Result {
        for (h in hues) {
            val r = estimate(crop, radFrac, h)
            if (r.deg != null) return r
        }
        return none
    }

    private fun estimate(crop: Mat, radFrac: Double, hue: Hue): Result = mats { sc ->
        val d = crop.rows()
        val bigR = d / 2.0
        val c = bigR.toInt()
        val h = (radFrac * bigR).toInt()
        if (h < 2) return@mats none
        val up = 4
        val roi0 = sc.track(crop.submat(Rect(c - h, c - h, 2 * h, 2 * h)))
        val roi = sc.mat()
        Imgproc.resize(roi0, roi, Size(), up.toDouble(), up.toDouble(), Imgproc.INTER_CUBIC)
        val hsv = sc.mat()
        Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
        val strict = sc.mat(); val loose = sc.mat()
        Core.inRange(hsv, hue.strictLo, hue.strictHi, strict)
        Core.inRange(hsv, hue.looseLo, hue.looseHi, loose)
        // drop gold regions touching the ROI border: that is terrain (the arrow is enclosed by its outline)
        val lab = sc.mat()
        val n0 = Imgproc.connectedComponents(strict, lab)
        val w = lab.cols(); val hh = lab.rows()
        val li = IntArray(w * hh)
        lab.get(0, 0, li)
        val border = BooleanArray(n0)
        for (x in 0 until w) { border[li[x]] = true; border[li[(hh - 1) * w + x]] = true }
        for (y in 0 until hh) { border[li[y * w]] = true; border[li[y * w + w - 1]] = true }
        val sb = ByteArray(w * hh)
        strict.get(0, 0, sb)
        for (i in li.indices) if (li[i] > 0 && border[li[i]]) sb[i] = 0
        strict.put(0, 0, sb)
        val k3 = Mat.ones(3, 3, CvType.CV_8U).also { sc.track(it) }
        val grow = sc.mat()
        Imgproc.dilate(strict, grow, k3, org.opencv.core.Point(-1.0, -1.0), 2)
        val both = sc.mat()
        Core.bitwise_and(loose, grow, both)
        val m0 = sc.mat()
        Core.bitwise_or(both, strict, m0)
        val m = sc.mat()
        Imgproc.morphologyEx(m0, m, Imgproc.MORPH_OPEN, k3)
        // connected blobs (external-contour equivalent): keep big ones near the centre, take the most central
        val labels = sc.mat(); val stats = sc.mat(); val cent = sc.mat()
        val n = Imgproc.connectedComponentsWithStats(m, labels, stats, cent, 8)
        if (n <= 1) return@mats none
        val st = IntArray(n * 5); stats.get(0, 0, st)
        val ce = DoubleArray(n * 2); cent.get(0, 0, ce)
        val ctr = (h * up).toDouble()
        val idx = (1 until n).filter { hypot(ce[it * 2] - ctr, ce[it * 2 + 1] - ctr) < 0.55 * ctr && st[it * 5 + 4] > 0 }
        if (idx.isEmpty()) return@mats none
        val amax = idx.maxOf { st[it * 5 + 4] }
        val big = idx.filter { st[it * 5 + 4] >= 0.3 * amax }
        val best = big.minBy { hypot(ce[it * 2] - ctr, ce[it * 2 + 1] - ctr) }
        val area = st[best * 5 + 4] / (up * up).toDouble()
        if (area < 10) return@mats none
        // pixels of the blob
        val lb = IntArray(w * hh); labels.get(0, 0, lb)
        var cnt = 0
        var sx = 0.0; var sy = 0.0
        for (y in 0 until hh) for (x in 0 until w) if (lb[y * w + x] == best) { cnt++; sx += x; sy += y }
        val mx = sx / cnt; val my = sy / cnt
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (y in 0 until hh) for (x in 0 until w) if (lb[y * w + x] == best) {
            val dx = x - mx; val dy = y - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        sxx /= (cnt - 1); syy /= (cnt - 1); sxy /= (cnt - 1)
        // eigen decomposition of [[sxx, sxy], [sxy, syy]]
        val tr = (sxx + syy) / 2
        val disc = sqrt(((sxx - syy) / 2) * ((sxx - syy) / 2) + sxy * sxy)
        val l1 = tr + disc          // largest
        val l0 = tr - disc
        if (l1 < 1.08 * l0) return@mats none
        var ux: Double; var uy: Double
        if (abs(sxy) > 1e-12) { ux = l1 - syy; uy = sxy } else if (sxx >= syy) { ux = 1.0; uy = 0.0 } else { ux = 0.0; uy = 1.0 }
        val nu = hypot(ux, uy); ux /= nu; uy /= nu
        var t3 = 0.0
        for (y in 0 until hh) for (x in 0 until w) if (lb[y * w + x] == best) {
            val t = (x - mx) * ux + (y - my) * uy
            t3 += t * t * t
        }
        if (t3 / cnt < 0) { ux = -ux; uy = -uy }
        val ang = (Math.toDegrees(atan2(ux, -uy)) % 360.0 + 360.0) % 360.0
        val conf = (1.0 - sqrt(l0 / l1)).coerceIn(0.0, 1.0)
        Result(ang, conf)
    }

    fun angDiff(a: Double, b: Double): Double = ((a - b + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}

/** Circular exponential smoothing of the heading. */
class HeadingSmoother(private val alpha: Double = 0.5) {
    var value: Double? = null
        private set

    fun update(ang: Double?): Double? {
        if (ang == null) return value
        val v = value
        value = if (v == null) ang else ((v + alpha * Heading.angDiff(ang, v)) % 360.0 + 360.0) % 360.0
        return value
    }

    fun reset() { value = null }
}

/**
 * Heading of the real Thor's cyan player arrow (real_report.md section 1, "Player arrow"): segmentation by HSV
 * (core H 88..102, S 115..175, V >= 235 grown into H 86..104, S >= 100, V >= 190; the snow background reaches V 229 so the
 * core must be brighter) and a rotation search of a reference arrow (V channel NCC over 3 deg steps, parabola refined).
 * The tip is the vertex opposite the notch; a centroid-to-farthest-point rule picks the wrong vertex. Port of
 * `tools/cv/real/arrow.py` (`heading_v`), the reference arrow is `src/main/resources/cv/arrow_ref_{v,m}.png` (from
 * data/cv/real/arrow_ref_v.npz). Returns null when no arrow blob is found (not a real-Thor minimap).
 */
object ArrowHeading {
    private const val HALF = 24
    private const val UP = 4
    private const val S = 2 * HALF * UP          // 192
    private const val SD = S / 2                 // rotation search runs at 96 px

    private class Ref(val v: Mat, val m: Mat)

    private val ref: Ref? by lazy {
        Cv.init()
        fun load(name: String): Mat? {
            val b = ArrowHeading::class.java.classLoader?.getResourceAsStream("cv/$name")?.use { it.readBytes() } ?: return null
            val raw = org.opencv.core.MatOfByte(*b)
            val m = org.opencv.imgcodecs.Imgcodecs.imdecode(raw, org.opencv.imgcodecs.Imgcodecs.IMREAD_GRAYSCALE)
            raw.release()
            return m.takeIf { !it.empty() }
        }
        val v = load("arrow_ref_v.png")
        val m = load("arrow_ref_m.png")
        if (v == null || m == null) null else {
            val vf = Mat(); val mf = Mat(); val vs = Mat(); val ms = Mat()
            v.convertTo(vf, CvType.CV_32F); m.convertTo(mf, CvType.CV_32F, 1.0 / 255.0)
            Imgproc.resize(vf, vs, Size(SD.toDouble(), SD.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.resize(mf, ms, Size(SD.toDouble(), SD.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            listOf(v, m, vf, mf).forEach { it.release() }
            Ref(vs, ms)
        }
    }

    /** True when the reference arrow is available. */
    val available: Boolean get() = ref != null

    private val k3 = lazy { Mat.ones(3, 3, CvType.CV_8U) }

    /** [crop]: square BGR minimap crop, the player at its centre. */
    fun estimate(crop: Mat): Heading.Result? {
        val rf = ref ?: return null
        val d = crop.rows()
        val c0 = d / 2 - HALF
        if (c0 < 0 || d / 2 + HALF > d) return null
        return mats { sc ->
            val roi0 = sc.track(crop.submat(Rect(c0, c0, 2 * HALF, 2 * HALF)))
            val roi = sc.mat()
            Imgproc.resize(roi0, roi, Size(), UP.toDouble(), UP.toDouble(), Imgproc.INTER_CUBIC)
            val hsv = sc.mat()
            Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
            val core = sc.mat(); val loose = sc.mat()
            Core.inRange(hsv, Scalar(88.0, 115.0, 235.0), Scalar(102.0, 175.0, 255.0), core)
            Core.inRange(hsv, Scalar(86.0, 100.0, 190.0), Scalar(104.0, 255.0, 255.0), loose)
            val grow = sc.mat()
            Imgproc.dilate(core, grow, k3.value, org.opencv.core.Point(-1.0, -1.0), 3)
            val both = sc.mat(); val m0 = sc.mat(); val m = sc.mat()
            Core.bitwise_and(loose, grow, both)
            Core.bitwise_or(both, core, m0)
            Imgproc.morphologyEx(m0, m, Imgproc.MORPH_OPEN, k3.value)
            val labels = sc.mat(); val stats = sc.mat(); val cent = sc.mat()
            val n = Imgproc.connectedComponentsWithStats(m, labels, stats, cent, 8)
            if (n <= 1) return@mats null
            val st = IntArray(n * 5); stats.get(0, 0, st)
            val ce = DoubleArray(n * 2); cent.get(0, 0, ce)
            val ctr = (HALF * UP).toDouble()
            var best = -1; var bd = 1e9
            for (i in 1 until n) {
                if (st[i * 5 + Imgproc.CC_STAT_AREA] < 40 * UP * UP / 4) continue
                val dd = hypot(ce[i * 2] - ctr, ce[i * 2 + 1] - ctr)
                if (dd < bd) { best = i; bd = dd }
            }
            if (best < 0 || bd > 10 * UP) return@mats null
            // arrow-centred V channel and mask at the search resolution
            val msk = sc.mat(); val mk8 = sc.mat()
            Core.compare(labels, Scalar(best.toDouble()), mk8, Core.CMP_EQ)
            mk8.convertTo(msk, CvType.CV_32F, 1.0 / 255.0)
            val ch = ArrayList<Mat>(3); Core.split(hsv, ch); ch.forEach { sc.track(it) }
            val vv = sc.mat(); ch[2].convertTo(vv, CvType.CV_32F)
            val tr = sc.mat(2, 3, CvType.CV_32F)
            tr.put(0, 0, floatArrayOf(1f, 0f, (S / 2 - ce[best * 2]).toFloat(), 0f, 1f, (S / 2 - ce[best * 2 + 1]).toFloat()))
            val vc = sc.mat(); val mc = sc.mat()
            Imgproc.warpAffine(vv, vc, tr, Size(S.toDouble(), S.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            Imgproc.warpAffine(msk, mc, tr, Size(S.toDouble(), S.toDouble()), Imgproc.INTER_LINEAR)
            val vd = sc.mat(); val md = sc.mat()
            Imgproc.resize(vc, vd, Size(SD.toDouble(), SD.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.resize(mc, md, Size(SD.toDouble(), SD.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val vArr = FloatArray(SD * SD); vd.get(0, 0, vArr)
            val mArr = FloatArray(SD * SD); md.get(0, 0, mArr)
            fun ncc(a: Double): Double {
                val rot = org.opencv.geometry.Geometry.getRotationMatrix2D(org.opencv.core.Point(SD / 2.0, SD / 2.0), -a, 1.0)
                val tv = Mat(); val tm = Mat()
                Imgproc.warpAffine(rf.v, tv, rot, Size(SD.toDouble(), SD.toDouble()))
                Imgproc.warpAffine(rf.m, tm, rot, Size(SD.toDouble(), SD.toDouble()))
                val tva = FloatArray(SD * SD); tv.get(0, 0, tva)
                val tma = FloatArray(SD * SD); tm.get(0, 0, tma)
                tv.release(); tm.release(); rot.release()
                var n = 0; var sx = 0.0; var sy = 0.0
                for (i in tva.indices) if (maxOf(tma[i], mArr[i]) > 0.5f) { n++; sx += tva[i]; sy += vArr[i] }
                if (n < 8) return -1.0
                val mx = sx / n; val my = sy / n
                var sxy = 0.0; var sxx = 0.0; var syy = 0.0
                for (i in tva.indices) if (maxOf(tma[i], mArr[i]) > 0.5f) {
                    val x = tva[i] - mx; val y = vArr[i] - my
                    sxy += x * y; sxx += x * x; syy += y * y
                }
                return sxy / (sqrt(sxx * syy) + 1e-6)
            }
            val coarse = DoubleArray(60) { ncc(it * 6.0) }
            var k = 0
            for (i in coarse.indices) if (coarse[i] > coarse[k]) k = i
            // refine around the coarse maximum in 1.5 deg steps, then a parabola
            var bestA = k * 6.0; var bestS = coarse[k]
            val fine = DoubleArray(9) { j -> ncc(k * 6.0 + (j - 4) * 1.5) }
            var kf = 4
            for (j in fine.indices) if (fine[j] > fine[kf]) kf = j
            bestA = k * 6.0 + (kf - 4) * 1.5; bestS = fine[kf]
            if (kf in 1..7) {
                val a = fine[kf - 1]; val b = fine[kf]; val c = fine[kf + 1]
                val dd = a - 2 * b + c
                if (abs(dd) > 1e-9) bestA += (0.5 * (a - c) / dd).coerceIn(-1.0, 1.0) * 1.5
            }
            val sorted = coarse.sortedDescending()
            val margin = bestS - sorted[5]
            Heading.Result(((bestA % 360.0) + 360.0) % 360.0, (bestS.coerceIn(0.0, 1.0) * (margin / 0.2).coerceIn(0.3, 1.0)))
        }
    }
}
