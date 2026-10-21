package com.teyvatmap.thor.tracking.cv

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What one tick delivers to the tracker: the BGR region of interest around the minimap (the rest of the frame is
 * never read) and a 64x36 gray thumbnail of the whole screen.
 */
class FrameInput(
    /** BGR, row-major, 3 bytes per pixel. */
    val roi: ByteArray,
    val roiW: Int,
    val roiH: Int,
    /** Position of the ROI's top-left corner in the full frame. */
    val roiX: Int,
    val roiY: Int,
    /** 64x36 gray, row-major. */
    val thumb: ByteArray,
    val timeNanos: Long = 0,
)

/**
 * Cheap per-frame tests (PLAN §6.2); each is well under 1 ms. Port of tools/cv/screen.py. Thresholds come from
 * [TrackerParams]; set [params] when they change.
 */
class Screen(var params: TrackerParams) {
    private val annuli = HashMap<Triple<Int, Long, Long>, BooleanArray>()
    private var ringRefCache: Pair<List<Float>, Pair<FloatArray, Double>>? = null

    // ------------------------------------------------------------------------------ loading screen
    /** (median, fraction of pixels within +-loadBand of the median) of the thumbnail. */
    fun loadingStats(thumb: ByteArray): Pair<Double, Double> {
        val hist = IntArray(256)
        for (b in thumb) hist[b.toInt() and 0xff]++
        val med = median(hist, thumb.size)
        var n = 0
        for (b in thumb) if (abs((b.toInt() and 0xff) - med) <= params.loadBand) n++
        return med to n.toDouble() / thumb.size
    }

    fun isLoadingScreen(thumb: ByteArray): Boolean {
        val (med, frac) = loadingStats(thumb)
        return (med >= params.loadBright || med <= params.loadDark) && frac >= params.loadFrac
    }

    // ---------------------------------------------------------------------------------- minimap ring
    private val ringIdxCache = HashMap<List<Number>, IntArray>()

    /** Sample indices (y * roiW + x) of the ring annulus for a centre offset (ox, oy) and radius offset dr, in px. */
    private fun idxFor(calib: Calib, f: FrameInput, ox: Int = 0, oy: Int = 0, dr: Int = 0): IntArray {
        val key = listOf(calib.cx, calib.cy, calib.r, params.ringAngles, params.ringR0, params.ringR1, params.ringDr, f.roiX, f.roiY, f.roiW, f.roiH, ox, oy, dr)
        return ringIdxCache.getOrPut(key) {
            if (ringIdxCache.size > 200) ringIdxCache.clear()
            val nr = Math.floor((params.ringR1 - params.ringR0) / params.ringDr + 1e-9).toInt() + 1
            val out = IntArray(nr * params.ringAngles)
            var k = 0
            for (ri in 0 until nr) {
                val rad = calib.r + dr + params.ringR0 + ri * params.ringDr
                for (a in 0 until params.ringAngles) {
                    val th = 2 * Math.PI * a / params.ringAngles
                    val x = Math.rint(calib.cx + ox + rad * cos(th)).toInt() - f.roiX
                    val y = Math.rint(calib.cy + oy + rad * sin(th)).toInt() - f.roiY
                    out[k++] = y.coerceIn(0, f.roiH - 1) * f.roiW + x.coerceIn(0, f.roiW - 1)
                }
            }
            out
        }
    }

    /** Gray samples of the ring annulus at fixed polar positions (ordered radius-major, like the Python reference). */
    fun ringSignature(f: FrameInput, calib: Calib, ox: Int = 0, oy: Int = 0, dr: Int = 0): FloatArray {
        val idx = idxFor(calib, f, ox, oy, dr)
        val o = FloatArray(idx.size)
        for (i in idx.indices) {
            val p = idx[i] * 3
            val b = (f.roi[p].toInt() and 0xff).toFloat()
            val g = (f.roi[p + 1].toInt() and 0xff).toFloat()
            val r = (f.roi[p + 2].toInt() and 0xff).toFloat()
            o[i] = b * 0.114f + g * 0.587f + r * 0.299f
        }
        return o
    }

    /**
     * Correlation of the ring with the calibrated reference. The sampling pattern is also tried shifted by up to
     * [TrackerParams.ringTol] px (centre) and +-1 px (radius) and the best correlation wins: a hand- or Hough-placed
     * circle is rarely exact to the pixel and the ring is only a few px thick.
     */
    fun ringNcc(f: FrameInput, calib: Calib): Double {
        val ref = calib.ringRef ?: return 1.0
        var c = ringRefCache
        if (c == null || c.first !== ref) {
            val a = FloatArray(ref.size) { ref[it] }
            val m = a.average().toFloat()
            var n = 0.0
            for (i in a.indices) { a[i] -= m; n += a[i] * a[i].toDouble() }
            c = ref to (a to sqrt(n))
            ringRefCache = c
        }
        val a = c.second.first
        val na = c.second.second
        val t = params.ringTol
        var best = -1.0
        for (oy in -t..t) for (ox in -t..t) for (dr in (if (t > 0) -1..1 else 0..0)) {
            val v = ringSignature(f, calib, ox, oy, dr)
            if (v.size != a.size) return 0.0
            val m = v.average().toFloat()
            var dot = 0.0
            var nv = 0.0
            for (i in v.indices) {
                val d = v[i] - m
                dot += d * a[i].toDouble()
                nv += d * d.toDouble()
            }
            nv = sqrt(nv)
            val r = if (nv < 1e-3 || na < 1e-3) 0.0 else dot / (nv * na)
            if (r > best) best = r
        }
        return best
    }

    fun ringPresent(f: FrameInput, calib: Calib): Boolean = ringValue(f, calib) >= ringThreshold()

    /** Threshold that goes with [ringValue] for the configured [TrackerParams.ringMode]. */
    fun ringThreshold(): Double = if (params.ringMode == "ncc") params.ringThr else params.ringContrastThr

    /** The ring statistic of the configured mode (contrast in gray levels, or the legacy correlation). */
    fun ringValue(f: FrameInput, calib: Calib): Double = if (params.ringMode == "ncc") ringNcc(f, calib) else ringContrast(f, calib)

    private val ringCos = DoubleArray(RING_ANGLES) { cos(2 * Math.PI * it / RING_ANGLES) }
    private val ringSin = DoubleArray(RING_ANGLES) { sin(2 * Math.PI * it / RING_ANGLES) }

    private fun grayAt(f: FrameInput, x: Double, y: Double): Double {
        val xi = Math.floor(x).toInt(); val yi = Math.floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        fun g(px: Int, py: Int): Double {
            val xx = px.coerceIn(0, f.roiW - 1); val yy = py.coerceIn(0, f.roiH - 1)
            val p = (yy * f.roiW + xx) * 3
            return (f.roi[p].toInt() and 0xff) * 0.114 + (f.roi[p + 1].toInt() and 0xff) * 0.587 + (f.roi[p + 2].toInt() and 0xff) * 0.299
        }
        return (g(xi, yi) * (1 - fx) + g(xi + 1, yi) * fx) * (1 - fy) + (g(xi, yi + 1) * (1 - fx) + g(xi + 1, yi + 1) * fx) * fy
    }

    /**
     * Real-Thor ring test (real_report.md section 4): the ring is a 1-2 px translucent light line that takes the colour
     * of whatever is behind it, so a correlation with a reference fails. Median over 360 angles of
     * `max(I(R-0.5), I(R+0.5)) - 0.5 * (I(R-4.5) + I(R+4.5))` on gray: 11..21 with the HUD, ~0 on loading / big map.
     * Costs 1440 bilinear samples (~0.05 ms).
     */
    fun ringContrast(f: FrameInput, calib: Calib): Double {
        val cx = calib.cx - 0.5 - f.roiX   // the Python reference uses the pixel-index convention (pixel centres at integers)
        val cy = calib.cy - 0.5 - f.roiY
        val r = calib.r
        val v = DoubleArray(RING_ANGLES)
        for (i in 0 until RING_ANGLES) {
            val c = ringCos[i]; val s = ringSin[i]
            fun at(rr: Double) = grayAt(f, cx + rr * c, cy + rr * s)
            v[i] = maxOf(at(r - 0.5), at(r + 0.5)) - 0.5 * (at(r - 4.5) + at(r + 4.5))
        }
        v.sort()
        return 0.5 * (v[RING_ANGLES / 2 - 1] + v[RING_ANGLES / 2])
    }

    // ------------------------------------------------------------------------- black minimap (domain)
    fun annulus(d: Int, rIn: Double, rOut: Double): BooleanArray =
        annuli.getOrPut(Triple(d, (rIn * 1e4).toLong(), (rOut * 1e4).toLong())) {
            val m = BooleanArray(d * d)
            val h = d / 2.0f
            for (y in 0 until d) for (x in 0 until d) {
                val rr = hypot(x + 0.5f - h, y + 0.5f - h) / h
                m[y * d + x] = rr >= rIn && rr <= rOut
            }
            m
        }

    private fun gray8Bytes(g: Mat): ByteArray = ByteArray(g.rows() * g.cols()).also { g.get(0, 0, it) }

    /** Fraction of pixels with |sobel_x| + |sobel_y| above [thr] inside [mask]. */
    private fun edgeFraction(g: Mat, mask: BooleanArray, thr: Int): Double = mats { sc ->
        val gx = sc.mat(); val gy = sc.mat()
        Imgproc.Sobel(g, gx, CvType.CV_16S, 1, 0, 3)
        Imgproc.Sobel(g, gy, CvType.CV_16S, 0, 1, 3)
        val ax = sc.mat(); val ay = sc.mat(); val sum = sc.mat(); val e = sc.mat()
        Core.convertScaleAbs(gx, ax)
        Core.convertScaleAbs(gy, ay)
        Core.add(ax, ay, sum)
        Core.compare(sum, Scalar(thr.toDouble()), e, Core.CMP_GT)
        val b = gray8Bytes(e)
        var n = 0
        var tot = 0
        for (i in mask.indices) if (mask[i]) { tot++; if (b[i].toInt() != 0) n++ }
        n.toDouble() / tot
    }

    /** (median, p90, edge fraction) of the outer annulus. */
    fun blackStats(g: Mat): Triple<Double, Double, Double> {
        val d = g.rows()
        val m = annulus(d, params.blackRIn, params.blackROut)
        val b = gray8Bytes(g)
        val hist = IntArray(256)
        var n = 0
        for (i in m.indices) if (m[i]) { hist[b[i].toInt() and 0xff]++; n++ }
        val k = (0.9 * n).toInt()
        val p90 = kth(hist, k).toDouble()
        return Triple(median(hist, n), p90, edgeFraction(g, m, params.blackEdgeThr))
    }

    /** Domain minimap: dark, flat, no edges. Call only when the ring is present. */
    fun minimapIsBlack(g: Mat): Boolean {
        val (med, p90, edge) = blackStats(g)
        return med <= params.blackMedian && p90 <= params.blackP90 && edge <= params.blackEdgeFrac
    }

    /** Textureless minimap (open sea). Only used to skip whole-map / target searches, never to decide a state. */
    fun minimapIsFlat(g: Mat): Boolean {
        val d = g.rows()
        val m = annulus(d, params.blackRIn, params.blackROut)
        val b = gray8Bytes(g)
        val hist = IntArray(256)
        var n = 0
        for (i in m.indices) if (m[i]) { hist[b[i].toInt() and 0xff]++; n++ }
        val k0 = (0.1 * n).toInt()
        val k1 = (0.9 * n).toInt()
        if (kth(hist, k1) - kth(hist, k0) > params.flatSpread) return false
        return edgeFraction(g, m, params.blackEdgeThr) <= params.flatEdgeFrac
    }

    // ------------------------------------------------------------------------------------ frame change
    /** 32x32 gray signature of the crop. */
    fun minimapSig(g: Mat): FloatArray = mats { sc ->
        val s = sc.mat()
        Imgproc.resize(g, s, Size(params.chgSize.toDouble(), params.chgSize.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val b = gray8Bytes(s)
        FloatArray(b.size) { (b[it].toInt() and 0xff).toFloat() }
    }

    fun sigDiff(a: FloatArray, b: FloatArray): Double {
        val m = annulus(params.chgSize, params.chgRIn, params.chgROut)
        var sum = 0.0
        var n = 0
        for (i in m.indices) if (m[i]) { sum += abs(a[i] - b[i]); n++ }
        return sum / n
    }

    // --------------------------------------------------------------------------------------- helpers
    private companion object { const val RING_ANGLES = 360 }

    private fun kth(hist: IntArray, k: Int): Int {
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc > k) return v
        }
        return 255
    }

    /** numpy-style median (mean of the two middle values for an even count). */
    private fun median(hist: IntArray, n: Int): Double {
        if (n == 0) return 0.0
        return if (n % 2 == 1) kth(hist, n / 2).toDouble() else (kth(hist, n / 2 - 1) + kth(hist, n / 2)) / 2.0
    }
}
