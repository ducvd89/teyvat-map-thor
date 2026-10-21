package com.teyvatmap.thor.cv

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teyvatmap.thor.cv.CvTestEnv.arr
import com.teyvatmap.thor.cv.CvTestEnv.dbl
import com.teyvatmap.thor.cv.CvTestEnv.intv
import com.teyvatmap.thor.cv.CvTestEnv.obj
import com.teyvatmap.thor.tracking.cv.Calib
import com.teyvatmap.thor.tracking.cv.FrameContext
import com.teyvatmap.thor.tracking.cv.Heading
import com.teyvatmap.thor.tracking.cv.Matcher
import com.teyvatmap.thor.tracking.cv.P2
import com.teyvatmap.thor.tracking.cv.TrackerParams
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/** Kotlin matcher vs ground truth and vs the Python reference on ~40 synthetic frames (tools/cv/export_fixtures.py). */
@RunWith(AndroidJUnit4::class)
class ParityTest {
    private fun med(v: List<Double>) = v.sorted().let { if (it.isEmpty()) Double.NaN else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    private fun p90(v: List<Double>) = v.sorted().let { it[((it.size - 1) * 0.9).toInt()] }

    @Test
    fun singleFramesMatchPythonAndTruth() {
        val index = CvTestEnv.index(2)
        val params = TrackerParams().synthetic().copy(maskNorth = false)   // the Python reference has no "N" label mask: exact parity
        val matcher = Matcher(index, params)
        val idx = CvTestEnv.jsonAsset("singles/index.json")
        val roi = idx.arr("roi").map { it.intv() }
        val base = CvTestEnv.calibOf(CvTestEnv.jsonAsset("calib.json"))
        val rows = idx.arr("frames")
        val eg = ArrayList<Double>(); val el = ArrayList<Double>(); val dgPy = ArrayList<Double>(); val dlPy = ArrayList<Double>()
        val he = ArrayList<Double>(); val msG = ArrayList<Double>(); val msL = ArrayList<Double>()
        var wrong = 0; var accepted = 0; var fails = 0
        val sb = StringBuilder()
        for (r in rows) {
            val o = r as JsonObject
            val fi = CvTestEnv.frameInput(o["file"]!!.jsonPrimitive.content, null, roi)
            val sCal = o["sCalib"]!!.dbl()
            val calib = base.copy(s = sCal)
            val ctx = FrameContext.create(fi, calib, params, index.meta.sigma, index.meta.lcnC)
            val px0 = o["px0"]!!.dbl(); val py0 = o["py0"]!!.dbl()
            val prior = o.arr("localPrior")
            val t0 = System.nanoTime()
            val g = matcher.globalMatch(ctx, sCal)
            msG += (System.nanoTime() - t0) / 1e6
            val t1 = System.nanoTime()
            val l = matcher.localMatch(ctx, P2(prior[0].dbl(), prior[1].dbl()), 50.0, sCal)
            msL += (System.nanoTime() - t1) / 1e6
            val gErr = if (g.ok) hypot(g.px0 - px0, g.py0 - py0) else 1e9
            val lErr = if (l.ok) hypot(l.px0 - px0, l.py0 - py0) else 1e9
            eg += gErr; el += lErr
            if (gErr > 25) fails++
            val conf = g.ok && g.score >= 0.5 && g.margin >= 0.08
            if (conf) { accepted++; if (gErr > 25) wrong++ }
            val py = o.obj("py")
            val pg = py.obj("global_"); val pl = py.obj("local")
            if (pg["ok"]!!.jsonPrimitive.content == "true" && g.ok) dgPy += hypot(g.px0 - pg["px0"]!!.dbl(), g.py0 - pg["py0"]!!.dbl())
            if (pl["ok"]!!.jsonPrimitive.content == "true" && l.ok) dlPy += hypot(l.px0 - pl["px0"]!!.dbl(), l.py0 - pl["py0"]!!.dbl())
            val hd = Heading.estimate(ctx.crop)
            val pyH = py["heading"]
            if (hd.deg != null && pyH != null && pyH !is JsonNull) he += abs(Heading.angDiff(hd.deg, pyH.dbl()))
            sb.append("id=${o["id"]!!.intv()} lvl=${o["level"]!!.jsonPrimitive.content} s=%.2f gErr=%.2f lErr=%.2f gScore=%.2f/%.2f (py %.2f)\n".format(sCal, gErr, lErr, g.score, g.margin, pg["score"]!!.dbl()))
            ctx.close()
        }
        val report = "PARITY n=${rows.size} global: median %.2f p90 %.2f fail %d | local: median %.2f p90 %.2f | accepted %d wrong %d | |K-Py| global median %.3f max %.3f local median %.3f max %.3f | heading |K-Py| median %.2f (n=%d) | ms local mean %.1f global mean %.0f p95 %.0f".format(
            med(eg), p90(eg), fails, med(el), p90(el), accepted, wrong, med(dgPy), dgPy.maxOrNull() ?: 0.0, med(dlPy), dlPy.maxOrNull() ?: 0.0,
            med(he), he.size, msL.average(), msG.average(), msG.sorted()[(msG.size * 0.95).toInt().coerceAtMost(msG.size - 1)])
        Log.i(CvTestEnv.TAG, report)
        File(CvTestEnv.target.getExternalFilesDir(null), "cv_parity.txt").writeText(report + "\n" + sb)
        assertEquals("wrong accepted", 0, wrong)
        assertTrue("global median error ${med(eg)}", med(eg) <= 1.0)
        assertTrue("local median error ${med(el)}", med(el) <= 1.0)
        assertTrue("global failures $fails", fails <= 2)
        assertTrue("Kotlin and Python agree (local median ${med(dlPy)})", med(dlPy) <= 0.25)
        matcher.close(); index.close()
    }

    /** Multi-scale calibration search on fixture frames, with and without a position hint. */
    @Test
    fun scaleEstimationFindsTheScale() {
        val index = CvTestEnv.index(2)
        val params = TrackerParams().synthetic()
        val matcher = Matcher(index, params)
        val idx = CvTestEnv.jsonAsset("singles/index.json")
        val roi = idx.arr("roi").map { it.intv() }
        val base = CvTestEnv.calibOf(CvTestEnv.jsonAsset("calib.json"))
        val rows = idx.arr("frames").filter { (it as JsonObject)["level"]!!.jsonPrimitive.content != "hard" }.take(8)
        var okHint = 0; var okNo = 0; var n = 0
        val sb = StringBuilder()
        for (r in rows) {
            val o = r as JsonObject
            val fi = CvTestEnv.frameInput(o["file"]!!.jsonPrimitive.content, null, roi)
            val sTrue = o["sTrue"]!!.dbl()
            val px0 = o["px0"]!!.dbl(); val py0 = o["py0"]!!.dbl()
            val ctx = FrameContext.create(fi, base.copy(s = 0.8), params, index.meta.sigma, index.meta.lcnC)
            val t0 = System.nanoTime()
            val e1 = matcher.estimateScale(ctx, hint = P2(px0 + 40, py0 - 30), hintRadiusZ0 = 120.0)
            val t1 = System.nanoTime()
            val e2 = matcher.estimateScale(ctx)
            val t2 = System.nanoTime()
            n++
            val h = e1 != null && abs(e1.s / sTrue - 1) < 0.03
            val g = e2 != null && abs(e2.s / sTrue - 1) < 0.03
            if (h) okHint++; if (g) okNo++
            sb.append("id=${o["id"]!!.intv()} sTrue=%.3f hint=%.3f(%.2f, %d ms) global=%.3f(%.2f, %d ms)\n".format(sTrue, e1?.s ?: 0.0, e1?.match?.score ?: 0.0, (t1 - t0) / 1000000, e2?.s ?: 0.0, e2?.match?.score ?: 0.0, (t2 - t1) / 1000000))
            ctx.close()
        }
        Log.i(CvTestEnv.TAG, "SCALE within 3%%: hint %d/%d, no hint %d/%d\n%s".format(okHint, n, okNo, n, sb))
        File(CvTestEnv.target.getExternalFilesDir(null), "cv_scale.txt").writeText("hint $okHint/$n, no hint $okNo/$n\n$sb")
        assertTrue("hint scale ok $okHint/$n", okHint >= n - 1)
        assertTrue("global scale ok $okNo/$n", okNo >= n - 2)
        matcher.close(); index.close()
    }
}
