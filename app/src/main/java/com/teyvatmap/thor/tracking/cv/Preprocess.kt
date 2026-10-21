package com.teyvatmap.thor.tracking.cv

import kotlinx.serialization.Serializable
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/** Minimap circle in the full frame (native px) plus scale `s` (minimap px per zoom-0 map px). */
@Serializable
data class Calib(
    val cx: Double = 210.5,       // real Thor, 1920x1080 (real_report.md: ring fit on the static HUD median; continuous convention)
    val cy: Double = 156.1,
    val r: Double = 133.4,
    val s: Double = 0.752,        // base scale (minimap px per zoom-0 px): Volkodlak Tundra; the region table knows the others
    /** Border-ring gray samples from calibration ([Screen.ringSignature]). */
    val ringRef: List<Float>? = null,
    val frameW: Int = 1920,
    val frameH: Int = 1080,
) {
    /** Side of the square minimap crop. */
    val cropSide: Int get() = 2 * ceil(r).toInt()
}

/**
 * Minimap crop + mask + icon suppression + local-contrast-normalised representation: the same function builds the
 * reference images (index builder) and the template (per frame), so both live in one feature space.
 * Port of tools/cv/preprocess.py (`lcn` representation only).
 */
object Preprocess {
    const val ENC = 42f

    private val circleCache = HashMap<Triple<Int, Long, Long>, Mat>()
    private val kernels = HashMap<Int, Mat>()

    @Synchronized
    private fun ellipseKernel(k: Int): Mat = kernels.getOrPut(k) { Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())) }

    /** float32 T x T mask: 1 where rIn <= radius <= rOut (pixel centres). Cached; never release the result. */
    @Synchronized
    fun circleMask(t: Int, rIn: Double, rOut: Double, north: DoubleArray? = null, northRect: DoubleArray? = null): Mat {
        val key = Triple(
            t,
            (rIn * 1000).toLong() * 1_000_003L + (north?.let { (it[0] * 100).toLong() * 7919L + (it[1] * 100).toLong() } ?: 0L) +
                (northRect?.let { (it[0] * 100).toLong() * 104729L + (it[1] * 100).toLong() * 1299709L } ?: 0L),
            (rOut * 1000).toLong(),
        )
        return circleCache.getOrPut(key) {
            val a = FloatArray(t * t)
            for (y in 0 until t) for (x in 0 until t) {
                val rr = hypot(x + 0.5f - t / 2f, y + 0.5f - t / 2f)
                var inside = rr >= rIn && rr <= rOut
                // the "N" label on the ring (top centre) is not map: mask a disc there
                if (inside && north != null && hypot(x + 0.5f - t / 2f, y + 0.5f - (t / 2f - north[0].toFloat())) < north[1]) inside = false
                // real Thor: the "N" glyph is a pale serif letter inside a rectangle above the centre
                if (inside && northRect != null && Math.abs(x + 0.5f - t / 2f) <= northRect[1] && Math.abs(y + 0.5f - (t / 2f - northRect[0].toFloat())) <= northRect[1]) inside = false
                a[y * t + x] = if (inside) 1f else 0f
            }
            Mat(t, t, CvType.CV_32F).also { it.put(0, 0, a) }
        }
    }

    // ---------------------------------------------------------------------------------------- icons
    private fun blobFilter(m: Mat, maxArea: Double, maxSide: Double, out: Mat, sc: Mats) {
        val labels = sc.mat()
        val stats = sc.mat()
        val cent = sc.mat()
        val n = Imgproc.connectedComponentsWithStats(m, labels, stats, cent, 8)
        val st = IntArray(n * 5)
        stats.get(0, 0, st)
        val keep = ByteArray(n)
        for (i in 1 until n) {
            val a = st[i * 5 + Imgproc.CC_STAT_AREA]
            val w = st[i * 5 + Imgproc.CC_STAT_WIDTH]
            val h = st[i * 5 + Imgproc.CC_STAT_HEIGHT]
            if (a <= maxArea && max(w, h) <= maxSide) keep[i] = 1
        }
        val lab = IntArray(m.rows() * m.cols())
        labels.get(0, 0, lab)
        val o = ByteArray(lab.size)
        for (i in lab.indices) o[i] = keep[lab[i]]
        out.create(m.rows(), m.cols(), CvType.CV_8UC1)
        out.put(0, 0, o)
    }

    /** uint8 mask (0/1), 1 where a pixel belongs to an overlaid icon (saturated or near-white blob). */
    fun iconMask(bgr: Mat, bigR: Double, p: TrackerParams, out: Mat) {
        mats { sc ->
            val hsv = sc.mat()
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV_FULL)
            val ch = ArrayList<Mat>(3)
            Core.split(hsv, ch)
            ch.forEach { sc.track(it) }
            val s = ch[1]
            val v = ch[2]
            val sHi = sc.mat(); val vHi = sc.mat(); val sat = sc.mat()
            Core.compare(s, Scalar(p.satThr.toDouble()), sHi, Core.CMP_GT)
            Core.compare(v, Scalar(p.valThr.toDouble()), vHi, Core.CMP_GT)
            Core.bitwise_and(sHi, vHi, sat)
            val vW = sc.mat(); val sLo = sc.mat(); val white = sc.mat()
            Core.compare(v, Scalar(p.whiteThr.toDouble()), vW, Core.CMP_GT)
            Core.compare(s, Scalar(p.whiteSat.toDouble()), sLo, Core.CMP_LT)
            Core.bitwise_and(vW, sLo, white)
            val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).also { sc.track(it) }
            val satC = sc.mat()
            Imgproc.morphologyEx(sat, satC, Imgproc.MORPH_CLOSE, k3)
            val maxArea = p.maxBlob * PI * bigR * bigR
            val maxSide = 0.3 * bigR
            val a = sc.mat(); val b = sc.mat()
            blobFilter(satC, maxArea, maxSide, a, sc)
            blobFilter(white, maxArea, maxSide, b, sc)
            val m = sc.mat()
            Core.bitwise_or(a, b, m)
            if (p.dilate > 0 && Core.countNonZero(m) > 0) {
                val k = 2 * p.dilate + 1
                Imgproc.dilate(m, out, ellipseKernel(k))
            } else {
                m.copyTo(out)
            }
        }
    }

    // --------------------------------------------------------------------------------------- repr
    /**
     * Replaces invalid (icon) pixels by a smooth masked-average estimate (float32 [g], uint8 0/1 [valid]).
     * Bug-compatible with tools/cv `fill_masked`: with a uint8 mask the weight image is blurred in uint8 (so it is 0
     * or 1) and the estimate is not normalised. The fill only influences the LCN of pixels a few px outside the icon
     * (the mask excludes the icon itself), so this is harmless, but parity with the Python reference is kept.
     */
    fun fillMasked(g: Mat, valid: Mat, out: Mat, sigma: Double = 4.0) {
        if (Core.countNonZero(valid) == valid.rows() * valid.cols()) {
            g.copyTo(out)
            return
        }
        mats { sc ->
            val vf = sc.mat()
            valid.convertTo(vf, CvType.CV_32F)
            val gm = sc.mat()
            Core.multiply(g, vf, gm)
            fun nconv(sg: Double, est: Mat, w: Mat) {
                val wu = sc.mat()
                Imgproc.GaussianBlur(valid, wu, Size(0.0, 0.0), sg, sg, Core.BORDER_REFLECT)
                wu.convertTo(w, CvType.CV_32F)
                val b = sc.mat()
                Imgproc.GaussianBlur(gm, b, Size(0.0, 0.0), sg, sg, Core.BORDER_REFLECT)
                val wc = sc.mat()
                Core.max(w, Scalar(1e-3), wc)
                Core.divide(b, wc, est)
            }
            val est1 = sc.mat(); val w1 = sc.mat(); val est2 = sc.mat(); val w2 = sc.mat()
            nconv(sigma, est1, w1)
            nconv(sigma * 3, est2, w2)
            val ok = sc.mat()
            Core.compare(w1, Scalar(0.05), ok, Core.CMP_GT)
            val est = sc.mat()
            est2.copyTo(est)
            est1.copyTo(est, ok)
            g.copyTo(out)
            val bad = sc.mat()
            Core.compare(valid, Scalar(0.0), bad, Core.CMP_EQ)
            est.copyTo(out, bad)
        }
    }

    /** LCN representation: (g - local_mean) / (local_std + c), softened by a 0.7 px blur. [g] is float32. */
    fun represent(g: Mat, sigma: Double, lcnC: Double, out: Mat) {
        mats { sc ->
            val mean = sc.mat()
            Imgproc.GaussianBlur(g, mean, Size(0.0, 0.0), sigma, sigma, Core.BORDER_REFLECT)
            val g2 = sc.mat()
            Core.multiply(g, g, g2)
            val ex2 = sc.mat()
            Imgproc.GaussianBlur(g2, ex2, Size(0.0, 0.0), sigma, sigma, Core.BORDER_REFLECT)
            val m2 = sc.mat()
            Core.multiply(mean, mean, m2)
            val v = sc.mat()
            Core.subtract(ex2, m2, v)
            Core.max(v, Scalar(0.0), v)
            Core.sqrt(v, v)
            Core.add(v, Scalar(lcnC), v)
            val num = sc.mat()
            Core.subtract(g, mean, num)
            val q = sc.mat()
            Core.divide(num, v, q)
            Imgproc.GaussianBlur(q, out, Size(0.0, 0.0), 0.7)
        }
    }

    /** float representation -> uint8 `128 + 42 * clip(x, -3, 3)` (truncating, like numpy's astype). */
    fun encode(x: Mat, out: Mat) {
        mats { sc ->
            val c = sc.mat()
            Core.min(x, Scalar(3.0), c)
            Core.max(c, Scalar(-3.0), c)
            val v = sc.mat()
            c.convertTo(v, CvType.CV_32F, ENC.toDouble(), 128.0 - 0.5)
            v.convertTo(out, CvType.CV_8U)
        }
    }
}

/** Template of one minimap at one working level. [img] and [mask] are float32 T x T. */
class Template(val img: Mat, val mask: Mat, val t: Int, val ft: Double) : AutoCloseable {
    override fun close() {
        img.release(); mask.release()
    }
}

/**
 * Per-frame products shared by every template of the frame: the square BGR crop, the gray image with icons filled
 * in and the validity mask. Created from a [FrameInput]; close it when the check is done.
 */
class FrameContext(val crop: Mat, val calib: Calib, val params: TrackerParams, val sigma: Double = params.sigma, val lcnC: Double = params.lcnC) :
    AutoCloseable {
    val side: Int = crop.rows()
    private val owned = Mats()
    private var gray: Mat? = null
    private var valid: Mat? = null
    private var validF: Mat? = null
    private val templates = HashMap<Pair<Int, Long>, Template>()

    private fun prepare() {
        if (gray != null) return
        val g = owned.mat()
        Imgproc.cvtColor(crop, g, Imgproc.COLOR_BGR2GRAY)
        val gf = owned.mat()
        g.convertTo(gf, CvType.CV_32F)
        val v = owned.mat()
        if (params.icons) {
            val im = owned.mat()
            Preprocess.iconMask(crop, calib.r, params, im)
            // valid = 1 - icon
            Core.compare(im, Scalar(0.0), v, Core.CMP_EQ)
            v.convertTo(v, CvType.CV_8U, 1.0 / 255.0)
        } else {
            v.create(crop.rows(), crop.cols(), CvType.CV_8UC1)
            v.setTo(Scalar(1.0))
        }
        val filled = owned.mat()
        Preprocess.fillMasked(gf, v, filled)
        val vf = owned.mat()
        v.convertTo(vf, CvType.CV_32F)
        gray = filled; valid = v; validF = vf
    }

    private var gray8: Mat? = null

    /** 8-bit gray of the crop (for the cheap screen tests). */
    fun gray8(): Mat = gray8 ?: owned.mat().also { Imgproc.cvtColor(crop, it, Imgproc.COLOR_BGR2GRAY); gray8 = it }

    /** Binary 0/1 icon-free mask of the crop (for the debug view). */
    fun validMask(): Mat { prepare(); return valid!! }

    /** Template for level [z] at scale [s]; cached per (z, s) for this frame. The context owns it. */
    fun template(z: Int, s: Double): Template {
        val key = z to java.lang.Double.doubleToLongBits(s)
        templates[key]?.let { return it }
        prepare()
        val d = side
        val ft = Math.pow(2.0, z.toDouble()) / s
        val t = max(8, Math.rint(d * ft / 2.0).toInt() * 2)
        val interp = if (t < d) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
        val tpl = mats { sc ->
            val gs = sc.mat()
            Imgproc.resize(gray, gs, Size(t.toDouble(), t.toDouble()), 0.0, 0.0, interp)
            val vs = sc.mat()
            Imgproc.resize(validF, vs, Size(t.toDouble(), t.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val ftEff = t.toDouble() / d
            val rep = sc.mat()
            Preprocess.represent(gs, sigma, lcnC, rep)
            val circ = Preprocess.circleMask(
                t, params.rIn * calib.r * ftEff, params.rOut * calib.r * ftEff,
                if (params.maskNorth) doubleArrayOf(params.northY * calib.r * ftEff, params.northR * calib.r * ftEff) else null,
                if (params.maskNorthRect) doubleArrayOf(params.northRectY * calib.r * ftEff, params.northRectHalf * calib.r * ftEff) else null,
            )
            val vOk = sc.mat()
            Core.compare(vs, Scalar(0.9), vOk, Core.CMP_GT)
            val vOkF = sc.mat()
            vOk.convertTo(vOkF, CvType.CV_32F, 1.0 / 255.0)
            val mask = Mat()
            Core.multiply(circ, vOkF, mask)
            val img = Mat()
            Core.multiply(rep, mask, img, Preprocess.ENC.toDouble())
            Template(img, mask, t, ftEff)
        }
        templates[key] = tpl
        return tpl
    }

    companion object {
        /** Builds the square minimap crop (sub-pixel centred, like `crop_minimap`) from a [FrameInput] and wraps it. */
        fun create(f: FrameInput, calib: Calib, params: TrackerParams, sigma: Double = params.sigma, lcnC: Double = params.lcnC): FrameContext {
            val d = calib.cropSide
            val crop = Mat()
            mats { sc ->
                val roi = sc.mat(f.roiH, f.roiW, CvType.CV_8UC3)
                roi.put(0, 0, f.roi)
                Imgproc.getRectSubPix(roi, Size(d.toDouble(), d.toDouble()), Point(calib.cx - f.roiX - 0.5, calib.cy - f.roiY - 0.5), crop)
            }
            return FrameContext(crop, calib, params, sigma, lcnC)
        }
    }

    override fun close() {
        templates.values.forEach { it.close() }
        templates.clear()
        owned.close()
        crop.release()
    }
}
