package com.teyvatmap.thor.cv

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teyvatmap.thor.tracking.cv.Matcher
import com.teyvatmap.thor.tracking.cv.P2
import com.teyvatmap.thor.tracking.cv.RegionZoomTable
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import com.teyvatmap.thor.tracking.cv.Screen
import com.teyvatmap.thor.tracking.cv.TrackState
import com.teyvatmap.thor.tracking.cv.Tracker
import com.teyvatmap.thor.tracking.cv.TrackerParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.hypot

/** The Kotlin tracker on the real Thor sequence (450 frames, 2 s apart): the numbers of data/cv/real_report.md. */
@RunWith(AndroidJUnit4::class)
class RealSequenceTest {
    private fun median(v: List<Double>) = v.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }

    @Test
    fun trackerOnRealSequence() {
        assumeTrue("real fixtures not pushed to ${RealEnv.dir}", RealEnv.available)
        val index = CvTestEnv.index(2)
        val params = TrackerParams()
        val seq = RealEnv.sequence()
        val matcher = Matcher(index, params)
        val tracker = Tracker(matcher, seq.calib, params, index.layers, index.targets, index.entrances, RealEnv.zoomTable())
        tracker.start()
        class Row(val f: RealEnv.Frame, val state: TrackState, val accepted: Boolean, val err: Double?, val ms: Double, val mode: String, val scale: Double, val heading: Double?)
        val rows = ArrayList<Row>()
        for (f in seq.frames) {
            val fi = RealEnv.frameInput(seq, f)
            val p = tracker.update(fi, f.t)
            val err = if (f.labelled) hypot(p.xPos - f.x!!, p.yPos - f.y!!) else null
            rows += Row(f, p.state, p.accepted, err, p.ms, p.mode, p.scale, p.heading)
        }
        val visible = rows.filter { it.f.labelled }                 // frames with a minimap on screen
        val tracking = visible.filter { it.state == TrackState.TRACKING }
        val frac = tracking.size / visible.size.toDouble()
        val wrong = rows.count { it.accepted && it.err != null && it.err > 25 }
        val errs = tracking.filter { it.accepted }.mapNotNull { it.err }
        val falseHidden = visible.count { it.state == TrackState.HIDDEN }
        val byMode = rows.groupBy { it.mode }.mapValues { (_, v) -> v.map { it.ms }.average() }
        val scaleErr = tracking.filter { it.f.scale != null }.map { Math.abs(it.scale / it.f.scale!! - 1) }
        val nonTracking = visible.filter { it.state != TrackState.TRACKING }.joinToString { "${it.f.i}:${it.state.name.take(3)}/${it.mode}" }
        val line = "REAL SEQUENCE visible=${visible.size} TRACKING=${tracking.size} (%.1f%%) wrong=$wrong err median %.2f p90 %.2f max %.2f falseHIDDEN=$falseHidden scaleRelErr median %.4f max %.4f | ms by mode ${byMode.mapValues { "%.1f".format(it.value) }} | non-tracking [$nonTracking]".format(
            100 * frac, median(errs), errs.sorted().getOrElse((errs.size * 0.9).toInt()) { 0.0 }, errs.maxOrNull() ?: 0.0, median(scaleErr), scaleErr.maxOrNull() ?: 0.0)
        Log.i(CvTestEnv.TAG, line)
        val hd = rows.filter { it.heading != null }.map { it.heading!! }
        File(CvTestEnv.target.getExternalFilesDir(null), "cv_real_sequence.txt").writeText(line + "\nheadings(first 40): " + hd.take(40).joinToString { "%.0f".format(it) } + "\n" +
            rows.joinToString("\n") { "${it.f.i} ${it.state} ${it.mode} acc=${it.accepted} err=${it.err?.let { e -> "%.2f".format(e) }} s=%.3f ms=%.0f hd=${it.heading?.let { h -> "%.0f".format(h) }}".format(it.scale, it.ms) })
        tracker.close(); matcher.close(); index.close()
        assertEquals("wrong positions accepted", 0, wrong)
        assertTrue("TRACKING fraction $frac", frac >= 0.98)
        assertTrue("median error ${median(errs)}", median(errs) <= 2.0)
        assertEquals("ring test: HUD frames judged hidden", 0, falseHidden)
    }

    @Test
    fun ringAndLoadingTestsOnRealFrames() {
        assumeTrue("real fixtures not pushed", RealEnv.available)
        val seq = RealEnv.sequence()
        val screen = Screen(TrackerParams())
        var falseHidden = 0; var minRing = 1e9; var maxOff = -1e9
        val loadingFlags = HashMap<Int, Boolean>()
        for (f in seq.frames) {
            val fi = RealEnv.frameInput(seq, f)
            val ring = screen.ringContrast(fi, seq.calib)
            if (f.labelled) { if (ring < screen.ringThreshold()) falseHidden++; minRing = minOf(minRing, ring) }
            else maxOff = maxOf(maxOff, ring)
            loadingFlags[f.i] = screen.isLoadingScreen(fi.thumb)
        }
        val loadingFrames = seq.frames.filter { it.kind == "loading" }.map { it.i }
        val falseLoading = loadingFlags.filter { (i, v) -> v && i !in loadingFrames }.keys
        Log.i(CvTestEnv.TAG, "REAL ring: HUD min %.1f, big map/loading max %.2f, false HIDDEN $falseHidden | loading detected on $loadingFrames: ${loadingFrames.map { loadingFlags[it] }}, false loading $falseLoading".format(minRing, maxOff))
        assertEquals(0, falseHidden)
        assertTrue("ring absent on big map / loading ($maxOff)", maxOff < 6.0)
        assertTrue("both white loading frames detected", loadingFrames.all { loadingFlags[it] == true })
        assertTrue("no loading false positive $falseLoading", falseLoading.isEmpty())
    }

    /**
     * Unknown regions: the table has the anchors but no zoom values. The tracker must find the zoom by itself (sliced ladder
     * search), then LEARN it for the region (EMA) -- the entries must end near the measured 0.752 (tundra) and 2.222 (town).
     */
    @Test
    fun learnsTheRegionZoomWhenNothingIsKnown() {
        assumeTrue("real fixtures not pushed", RealEnv.available)
        val index = CvTestEnv.index(2)
        val params = TrackerParams()
        val seq = RealEnv.sequence()
        val matcher = Matcher(index, params)
        val table = RealEnv.zoomTable(seeded = false)
        val tracker = Tracker(matcher, seq.calib, params, index.layers, index.targets, index.entrances, table)
        tracker.start()
        var firstLock = -1; var tracking = 0; var wrong = 0; var visible = 0
        val log = StringBuilder()
        for (f in seq.frames) {
            val p = tracker.update(RealEnv.frameInput(seq, f), f.t)
            if (p.state == TrackState.TRACKING && firstLock < 0) firstLock = f.i
            if (f.labelled && f.i >= 100) { visible++; if (p.state == TrackState.TRACKING) tracking++ }
            if (p.accepted && f.labelled && hypot(p.xPos - f.x!!, p.yPos - f.y!!) > 25) wrong++
            if (f.i % 25 == 0 || p.mode in setOf("full", "confirm", "targets")) log.append("${f.i}:${p.state.name.take(3)}/${p.mode}/s=%.3f ".format(p.scale))
        }
        val town = table.entry(RegionZoomTable.ANCHOR_SRETOMOROZSK)
        val tundra = table.entry(RegionZoomTable.ANCHOR_VOLKODLAK_TUNDRA)
        val line = "REAL LEARN first lock at frame $firstLock; frames>=100 tracking $tracking/$visible wrong=$wrong; learned town=${town?.scale}x${town?.samples} tundra=${tundra?.scale}x${tundra?.samples}\n$log"
        Log.i(CvTestEnv.TAG, line)
        File(CvTestEnv.target.getExternalFilesDir(null), "cv_real_learn.txt").writeText(line)
        tracker.close(); matcher.close(); index.close()
        assertEquals(0, wrong)
        assertTrue("tundra zoom learned (${tundra?.scale})", tundra != null && Math.abs(tundra.scale / 0.752 - 1) <= 0.03)
        assertTrue("tracking after the lock $tracking/$visible", tracking >= 0.9 * visible)
    }

    /** Big-map fix, then the minimap: the exact position makes the scale sweep trivial and the region's zoom is learned (PLAN §6.0). */
    @Test
    fun bigMapFixThenMinimapLearnsTheRegionZoom() {
        assumeTrue("real fixtures not pushed", RealEnv.available)
        val index = CvTestEnv.index(2)
        val params = TrackerParams()
        val seq = RealEnv.sequence()
        val matcher = Matcher(index, params)
        val table = RealEnv.zoomTable(seeded = false)
        val tracker = Tracker(matcher, seq.calib, params, index.layers, index.targets, index.entrances, table)
        tracker.start()
        val truth = index.geo.toPx0(-8223.4, -6098.1)          // the big-map fix of bm1 (real_report.md)
        val fixed = tracker.mapFix(P2(truth.x, truth.y), 0.0, 328.0)
        assertEquals(TrackState.FIXED, fixed.state)
        val f = seq.frames[440]
        val p = tracker.update(RealEnv.frameInput(seq, f), 2.0)
        val e = table.entry(RegionZoomTable.ANCHOR_VOLKODLAK_TUNDRA)
        Log.i(CvTestEnv.TAG, "REAL MAPFIX then minimap: state=${p.state} mode=${p.mode} path=${p.path} scale=${p.scale} err=%.2f entry=${e?.scale}x${e?.samples} ms=%.0f".format(hypot(p.xPos - f.x!!, p.yPos - f.y!!), p.ms))
        tracker.close(); matcher.close(); index.close()
        assertEquals(TrackState.TRACKING, p.state)
        assertEquals("mapfix", p.path)
        assertTrue("scale ${p.scale}", Math.abs(p.scale / 0.752 - 1) <= 0.03)
        assertTrue("learned ${e?.scale}", e != null && Math.abs(e.scale / 0.752 - 1) <= 0.03)
        assertTrue("error ${hypot(p.xPos - f.x!!, p.yPos - f.y!!)}", hypot(p.xPos - f.x!!, p.yPos - f.y!!) <= 2.0)
    }

    /** The waypoint picked on the big map is the first thing RELOCATING checks after the loading screen ("destination" path). */
    @Test
    fun teleportDestinationIsCheckedFirst() {
        assumeTrue("real fixtures not pushed", RealEnv.available)
        val index = CvTestEnv.index(2)
        val params = TrackerParams()
        val seq = RealEnv.sequence()
        val matcher = Matcher(index, params)
        val tracker = Tracker(matcher, seq.calib, params, index.layers, index.targets, index.entrances, RealEnv.zoomTable())
        tracker.start()
        for (i in 0..50) tracker.update(RealEnv.frameInput(seq, seq.frames[i]), seq.frames[i].t)
        // the HoYoLAB teleport waypoint 31 px from where the player arrives (real_report.md section 2.1)
        val wp = index.geo.toPx0(-8142.5, -6003.5)
        tracker.hintDestination(P2(wp.x, wp.y), seq.frames[50].t)
        val p1 = tracker.update(RealEnv.frameInput(seq, seq.frames[51]), seq.frames[51].t)
        val p2 = tracker.update(RealEnv.frameInput(seq, seq.frames[52]), seq.frames[52].t)
        Log.i(CvTestEnv.TAG, "REAL DESTINATION frame 51: ${p1.state} ${p1.mode} ${"%.0f".format(p1.ms)} ms; frame 52: ${p2.state} ${p2.mode} err=%.2f".format(hypot(p2.xPos - seq.frames[52].x!!, p2.yPos - seq.frames[52].y!!)))
        tracker.close(); matcher.close(); index.close()
        assertEquals("destination", p1.mode)
        assertEquals(TrackState.TRACKING, p2.state)
    }

    /** The banner's anchor box narrows the whole-map search (PLAN §6.0): same answer, a fraction of the correlation work. */
    @Test
    fun bannerAreaRestrictsTheWholeMapSearch() {
        assumeTrue("real fixtures not pushed", RealEnv.available)
        val index = CvTestEnv.index(2)
        val params = TrackerParams(relocOrderTargets = false)          // whole-map stage only
        val seq = RealEnv.sequence()
        val table = RealEnv.zoomTable()
        val a = table.anchor(RegionZoomTable.ANCHOR_VOLKODLAK_TUNDRA)!!
        val p0 = index.geo.toPx0(a.l, a.t); val p1 = index.geo.toPx0(a.r, a.b)
        fun run(box: DoubleArray?): Triple<Double, String, TrackState> {
            val matcher = Matcher(index, params)
            val tracker = Tracker(matcher, seq.calib, params, index.layers, index.targets, index.entrances, table)
            tracker.start()
            if (box != null) tracker.hintArea(box, a.id)
            val f = seq.frames[100]
            val fi = RealEnv.frameInput(seq, f)
            val r = tracker.update(fi, 0.0)                         // whole-map search (targets are off): the first result
            val c = tracker.update(fi, 2.0)                         // the second agreeing result locks
            val out = Triple(r.ms, c.mode, c.state)
            Log.i(CvTestEnv.TAG, "REAL BANNER area box=${box != null}: first result ${r.mode} ${"%.0f".format(r.ms)} ms, then ${c.mode} ${c.state} err=%.2f".format(hypot(c.xPos - f.x!!, c.yPos - f.y!!)))
            tracker.close(); matcher.close()
            return out
        }
        val without = run(null)
        val with = run(doubleArrayOf(p0.x, p0.y, p1.x, p1.y))
        index.close()
        assertEquals(TrackState.TRACKING, with.third)
        assertTrue("restricted search is faster (${with.first} vs ${without.first} ms)", with.first < without.first)
    }

    /** The production extractor on full frames (the ~60 UID-blanked ones) agrees with the exported ROI + Python thumbnail. */
    @Test
    fun fullFramePathMatchesTheExportedRoiAndThumbnail() {
        assumeTrue("real fixtures not pushed", RealEnv.available)
        val seq = RealEnv.sequence()
        val screen = Screen(TrackerParams())
        var n = 0; var worstThumb = 0.0; var worstRing = 0.0; var badLoad = 0
        for (o in RealEnv.fullIndex()) {
            val i = o["i"]!!.jsonPrimitive.int
            val m = RealEnv.full("full/%03d.png".format(i))
            val a = RealEnv.extract(m, seq.calib)
            m.release()
            val b = RealEnv.frameInput(seq, seq.frames[i])
            var d = 0.0
            for (k in a.thumb.indices) d += Math.abs((a.thumb[k].toInt() and 0xff) - (b.thumb[k].toInt() and 0xff))
            worstThumb = maxOf(worstThumb, d / a.thumb.size)
            worstRing = maxOf(worstRing, Math.abs(screen.ringContrast(a, seq.calib) - screen.ringContrast(b, seq.calib)))
            if (screen.isLoadingScreen(a.thumb) != screen.isLoadingScreen(b.thumb)) badLoad++
            assertEquals("ROI of frame $i", b.roi.size, a.roi.size)
            n++
        }
        Log.i(CvTestEnv.TAG, "REAL full-frame path: $n frames, worst thumbnail mean |diff| %.2f (UID area blanked), ring contrast diff %.3f, loading disagreements $badLoad".format(worstThumb, worstRing))
        assertTrue("$n full frames", n >= 55)
        assertTrue("thumbnails agree ($worstThumb)", worstThumb < 3.0)
        assertTrue("ring contrast agrees ($worstRing)", worstRing < 0.01)
        assertEquals(0, badLoad)
    }
}
