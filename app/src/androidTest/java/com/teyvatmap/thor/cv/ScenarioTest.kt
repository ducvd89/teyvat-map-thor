package com.teyvatmap.thor.cv

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teyvatmap.thor.cv.CvTestEnv.arr
import com.teyvatmap.thor.cv.CvTestEnv.dbl
import com.teyvatmap.thor.cv.CvTestEnv.intv
import com.teyvatmap.thor.tracking.cv.Matcher
import com.teyvatmap.thor.tracking.cv.TrackState
import com.teyvatmap.thor.tracking.cv.Tracker
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

/** Replays the exported scenario sequences through the Kotlin tracker in simulated time. */
@RunWith(AndroidJUnit4::class)
class ScenarioTest {
    class Frame(val t: Double, val o: JsonObject) {
        val expected = o["expected"]!!.jsonPrimitive.content
        val kind = o["kind"]!!.jsonPrimitive.content
        val inWorld = o["inWorld"]!!.jsonPrimitive.content == "true"
        val grace = o["grace"]!!.jsonPrimitive.content == "true"
        val scale: Double? = o["scale"]?.takeIf { it !is JsonNull }?.dbl()
        val truthFloor: Int? = o["floorId"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull()
        val px0 = o["px0"]?.takeIf { it !is JsonNull }?.dbl()
        val py0 = o["py0"]?.takeIf { it !is JsonNull }?.dbl()
        val pyState = o["py"]!!.let { (it as JsonObject)["state"]!!.jsonPrimitive.content }
    }

    class Row(val t: Double, val f: Frame, val state: TrackState, val accepted: Boolean, val px0: Double, val py0: Double, val floorId: Int?, val ms: Double, val mode: String, val scale: Double)

    class Run(val rows: List<Row>, val stats: String) {
        val graded get() = rows.filter { !it.f.grace }
        fun stateAccuracy(): Double = graded.count { it.state.name == it.f.expected || (it.f.expected == "SEA" && (it.state == TrackState.TRACKING || it.state == TrackState.LOST)) }.toDouble() / graded.size
        fun pythonAgreement(): Double = graded.count { it.state.name == it.f.pyState }.toDouble() / graded.size
        fun wrongPositions(): Int = rows.count { it.accepted && it.f.inWorld && it.f.px0 != null && hypot(it.px0 - it.f.px0, it.py0 - it.f.py0!!) > 25 }
        fun trackingErrors(): List<Double> = graded.filter { it.state == TrackState.TRACKING && it.f.inWorld && it.f.px0 != null }.map { hypot(it.px0 - it.f.px0!!, it.py0 - it.f.py0!!) }
        fun caveRows() = graded.filter { it.f.kind == "cave" }
        fun floorAccuracy(): Double = caveRows().let { c -> if (c.isEmpty()) 1.0 else c.count { it.floorId == it.f.truthFloor && it.state == TrackState.TRACKING }.toDouble() / c.size }

        /** Seconds from every change of the truth floor until the tracker reports it (null = never). */
        fun floorLatencies(): List<Pair<Double, Double?>> {
            val out = ArrayList<Pair<Double, Double?>>()
            var prev: Int? = null
            var first = true
            for ((i, r) in rows.withIndex()) {
                val tf = r.f.truthFloor
                if (r.f.kind == "cave") {
                    if (!first && tf != prev) {
                        val hit = rows.drop(i).firstOrNull { it.f.kind == "cave" && it.floorId == tf && it.state == TrackState.TRACKING }
                        out += r.t to hit?.let { it.t - r.t }
                    }
                    first = false
                    prev = tf
                } else { first = true }
            }
            return out
        }
    }

    private fun scenario(name: String, params: TrackerParams): Run {
        val index = CvTestEnv.index(2)
        val idx = CvTestEnv.jsonAsset("scenarios/$name/index.json")
        val calib = CvTestEnv.calibOf(idx["calib"] as JsonObject)
        val roi = idx.arr("roi").map { it.intv() }
        val frames = idx.arr("frames").map { Frame(it.let { f -> (f as JsonObject)["t"]!!.dbl() }, it as JsonObject) }
        val duration = idx["duration"]!!.dbl()
        val matcher = Matcher(index, params)
        val tracker = Tracker(matcher, calib, params, index.layers, index.targets, index.entrances)
        tracker.start()
        val rows = ArrayList<Row>()
        var t = 0.0
        val cache = HashMap<Int, com.teyvatmap.thor.tracking.cv.FrameInput>()
        while (t < duration) {
            val k = frames.indices.minBy { abs(frames[it].t - t) }
            val fi = cache.getOrPut(k) { CvTestEnv.frameInput(frames[k].o["file"]!!.jsonPrimitive.content, frames[k].o["thumb"]!!.jsonPrimitive.content, roi) }
            val p = tracker.update(fi, t)
            rows += Row(t, frames[k], p.state, p.accepted, p.px0, p.py0, p.floorId, p.ms, p.mode, p.scale)
            t += p.delay
        }
        val st = tracker.stats
        val stats = "full=${st.full} target=${st.target} local=${st.local} layer=${st.layerChecks} cheap=${st.cheap} checks=${st.checks} cpuMs=%.0f".format(st.cpuMs)
        tracker.close(); matcher.close(); index.close()
        return Run(rows, stats)
    }

    private fun report(name: String, run: Run): String {
        val lat = run.floorLatencies().joinToString { (t, l) -> "t=%.1f:%s".format(t, l?.let { "%.1fs".format(it) } ?: "never") }
        val errs = run.trackingErrors().sorted()
        val line = "SCENARIO $name states ${"%.3f".format(run.stateAccuracy())} (python-agree ${"%.3f".format(run.pythonAgreement())}) wrong=${run.wrongPositions()} " +
            "trackErr median %.2f p90 %.2f max %.2f | floorAcc %.3f | floor latencies [$lat] | ${run.stats}".format(
                errs.getOrElse(errs.size / 2) { 0.0 }, errs.getOrElse((errs.size * 0.9).toInt()) { 0.0 }, errs.lastOrNull() ?: 0.0, run.floorAccuracy())
        Log.i(CvTestEnv.TAG, line)
        val seq = run.rows.joinToString(" ") { "%.1f:%s%s/%s%s".format(it.t, it.state.name.take(3), it.floorId?.let { f -> "#$f" } ?: "", it.f.expected.take(3), if (it.f.grace) "*" else "") }
        File(CvTestEnv.target.getExternalFilesDir(null), "cv_scenario_$name.txt").appendText(line + "\n" + seq + "\n")
        return line
    }

    private fun reset(name: String) { File(CvTestEnv.target.getExternalFilesDir(null), "cv_scenario_$name.txt").delete() }

    @Test
    fun worldScenarioStatesMatch() {
        reset("world")
        val run = scenario("world", TrackerParams().synthetic())
        report("world", run)
        assertEquals("wrong positions accepted", 0, run.wrongPositions())
        assertTrue("state accuracy ${run.stateAccuracy()}", run.stateAccuracy() >= 0.95)
        assertTrue("median tracking error", run.trackingErrors().sorted().let { it[it.size / 2] } <= 1.5)
    }

    @Test
    fun caveScenarioFollowsFloors() {
        reset("cave")
        val run = scenario("cave", TrackerParams().synthetic())
        report("cave", run)
        assertEquals("wrong positions accepted", 0, run.wrongPositions())
        assertTrue("state accuracy ${run.stateAccuracy()}", run.stateAccuracy() >= 0.95)
        assertTrue("floor accuracy ${run.floorAccuracy()}", run.floorAccuracy() >= 0.85)
    }

    @Test
    fun entranceScenarioEntranceVsTimer() {
        reset("entrance")
        val withEntrances = scenario("entrance", TrackerParams().synthetic())
        val a = report("entrance(entrances on)", withEntrances)
        val timer = scenario("entrance", TrackerParams().synthetic().copy(useEntrances = false, layerPeriod = 5.0))
        val b = report("entrance(zone timer only)", timer)
        File(CvTestEnv.target.getExternalFilesDir(null), "cv_scenario_entrance.txt").appendText("$a\n$b\n")
        assertEquals("wrong positions accepted", 0, withEntrances.wrongPositions())
        assertTrue("state accuracy ${withEntrances.stateAccuracy()}", withEntrances.stateAccuracy() >= 0.9)
        assertTrue("floor accuracy ${withEntrances.floorAccuracy()}", withEntrances.floorAccuracy() >= withEntrances.floorAccuracy().coerceAtMost(timer.floorAccuracy()))
        assertTrue("underground teleport relocates on its floor", withEntrances.caveRows().any { it.f.expected == "TRACKING" && it.floorId != null && it.floorId == it.f.truthFloor })
    }

    /** The minimap zoom changes on the fly (ramps and jumps to 0.62x-0.85x of the base scale): no misses, no LOST. */
    @Test
    fun zoomChangesDoNotCauseMisses() {
        reset("zoom")
        val on = scenario("zoom", TrackerParams().synthetic())
        report("zoom(dynamic scale)", on)
        val graded = on.rows.filter { it.t >= 3.0 }
        val lost = graded.count { it.state != TrackState.TRACKING }
        val relErr = graded.filter { it.f.scale != null }.map { abs(it.scale / it.f.scale!! - 1) }.sorted()
        val line = "ZOOM dynamic: non-TRACKING checks after 3 s: $lost/${graded.size}, wrong=${on.wrongPositions()}, scale rel.err median %.3f p90 %.3f max %.3f".format(
            relErr[relErr.size / 2], relErr[(relErr.size * 0.9).toInt()], relErr.last())
        Log.i(CvTestEnv.TAG, line)
        File(CvTestEnv.target.getExternalFilesDir(null), "cv_scenario_zoom.txt").appendText(line + "\n")
        val fixed = scenario("zoom", TrackerParams().synthetic().copy(dynamicScale = false))
        val lostFixed = fixed.rows.filter { it.t >= 3.0 }.count { it.state != TrackState.TRACKING }
        val line2 = "ZOOM fixed scale (ladder off): non-TRACKING checks after 3 s: $lostFixed/${graded.size}, wrong=${fixed.wrongPositions()}"
        Log.i(CvTestEnv.TAG, line2)
        File(CvTestEnv.target.getExternalFilesDir(null), "cv_scenario_zoom.txt").appendText(line2 + "\n")
        assertEquals("wrong positions accepted", 0, on.wrongPositions())
        assertEquals("zoom changes must not cost the lock", 0, lost)
        assertTrue("tracked scale follows the real one (median ${relErr[relErr.size / 2]})", relErr[relErr.size / 2] <= 0.05)
        assertTrue("the ladder is what keeps the lock ($lostFixed vs $lost)", lostFixed > lost)
    }
}
