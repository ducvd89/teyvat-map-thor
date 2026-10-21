package com.teyvatmap.thor.cv

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teyvatmap.thor.data.store.PackParser
import com.teyvatmap.thor.tracking.cv.BigMapDetector
import com.teyvatmap.thor.tracking.cv.BigMapHint
import com.teyvatmap.thor.tracking.cv.BigMapLocator
import com.teyvatmap.thor.tracking.cv.Cv
import com.teyvatmap.thor.tracking.cv.MapGeo
import com.teyvatmap.thor.tracking.cv.P2
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.hypot

/** Big-map detection and the SIFT registration on the real captures (bm1, zoom1): the player must land within 3 z0 px. */
@RunWith(AndroidJUnit4::class)
class BigMapTest {
    private fun geo(): MapGeo = MapGeo.fromInfoJson(2, PackParser.json.parseToJsonElement(CvTestEnv.pack.mapFile(2, "info.json").readText()))!!

    private fun locator(): BigMapLocator {
        val g = geo()
        return BigMapLocator(g) { z, x, y -> CvTestEnv.pack.tileFile(2, g.version, z, x, y) }
    }

    private val truth: P2 get() {
        val o = CvTestEnv.json.parseToJsonElement(File(RealEnv.dir, "bigmap/truth.json").readText()).jsonObject
        return P2(o["x_pos"]!!.jsonPrimitive.double, o["y_pos"]!!.jsonPrimitive.double)
    }

    @Test
    fun detectorSeparatesTheBigMapFromGameplay() {
        assumeTrue(RealEnv.available)
        Cv.init()
        val det = BigMapDetector()
        assertTrue("glyph templates are in the APK", det.available)
        var tp = 0; var fp = 0; var fn = 0; val rows = ArrayList<String>()
        for (o in RealEnv.fullIndex()) {
            val i = o["i"]!!.jsonPrimitive.int
            val m = RealEnv.full("full/%03d.png".format(i))
            val n = det.glyphsFound(m)
            val big = n >= BigMapDetector.MIN_GLYPHS
            val truthBig = o["kind"]!!.jsonPrimitive.content == "bigmap"
            if (big && truthBig) tp++ else if (big) fp++ else if (truthBig) fn++
            rows += "$i:$n"
            m.release()
        }
        for (n in listOf("bm1", "zoom1")) {
            val m = RealEnv.full("bigmap/$n.png"); val c = det.glyphsFound(m); if (c >= BigMapDetector.MIN_GLYPHS) tp++ else fn++; rows += "$n:$c"; m.release()
        }
        Log.i(CvTestEnv.TAG, "BIGMAP detector tp=$tp fp=$fp fn=$fn glyphs: ${rows.joinToString(" ")}")
        assertEquals(0, fp); assertEquals(0, fn); assertEquals(4, tp)
    }

    @Test
    fun registrationPutsThePlayerWithin3Px() {
        assumeTrue(RealEnv.available)
        Cv.init()
        val t = truth
        val g = geo()
        val loc = locator()
        val tz0 = g.toPx0(t.x, t.y)
        val out = StringBuilder()
        var okAll = true
        for ((name, how) in listOf("bm1" to "hint", "zoom1" to "hint", "bm1" to "nohint", "zoom1" to "nohint")) {
            val m = RealEnv.full("bigmap/$name.png")
            // a deliberately poor prior: 600 px off, trusted to 700
            val hints = if (how == "hint") listOf(BigMapHint(P2(tz0.x + 400, tz0.y - 450), 700.0, "last")) else emptyList()
            val fix = loc.locate(m, hints)
            m.release()
            val err = fix?.let { f -> f.xPos?.let { hypot(it - t.x, f.yPos!! - t.y) } }
            out.append("$name/$how: fix=${fix != null} how=${fix?.how} inl=${fix?.inliers} z0/px=%.3f err=${err?.let { "%.2f".format(it) }} heading=${fix?.headingDeg?.let { "%.1f".format(it) }} arrow=${fix?.arrowScreen} ms=%.0f\n".format(fix?.z0PerPx ?: 0.0, fix?.ms ?: 0.0))
            // zoomed-in without any prior (zoom1/nohint) is a known gap: too few features at z-3, the whole land at z-2 does not fit in memory
            if ((how == "hint" || name == "bm1") && !(err != null && err <= 3.0)) okAll = false
        }
        Log.i(CvTestEnv.TAG, "BIGMAP registration\n$out")
        File(CvTestEnv.target.getExternalFilesDir(null), "cv_bigmap.txt").writeText(out.toString())
        loc.close()
        assertTrue("player within 3 z0 px of (${t.x}, ${t.y}) (with a prior; bm1 also without):\n$out", okAll)
    }
}
