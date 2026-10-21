package com.teyvatmap.thor.tracking

import com.teyvatmap.thor.tracking.cv.BigFixRules
import com.teyvatmap.thor.tracking.cv.JobKind
import com.teyvatmap.thor.tracking.cv.PlanMap
import com.teyvatmap.thor.tracking.cv.RegionZoomTable
import com.teyvatmap.thor.tracking.cv.RelocPlan
import com.teyvatmap.thor.tracking.cv.SearchJob
import com.teyvatmap.thor.tracking.cv.SearchRun
import com.teyvatmap.thor.tracking.cv.SearchSlice
import com.teyvatmap.thor.tracking.cv.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the multi-map relocation: job order, time-boxed slices, scan levels and the big-map fix rules. */
class RelocPlanTest {
    private val teyvat = PlanMap(2, cost = 6.0e6, scales = listOf(2.222, 0.752), targets = true, floorTargets = true)
    private val chasm = PlanMap(9, cost = 1.5e5, scales = listOf(0.752, 0.715, 0.79), targets = true, floorTargets = false)
    private val enka = PlanMap(7, cost = 2.0e5, scales = listOf(0.752), targets = true, floorTargets = false)
    private val ladder = listOf(0.752, 0.80, 0.70, 1.2)

    private fun plan(cur: Int = 2, dest: Int? = null, last: Boolean = true) =
        RelocPlan.build(cur, dest, listOf(teyvat, chasm, enka), ladder, hasLast = last)

    @Test fun lastPositionThenTargetsOfAllMapsThenWholeMaps() {
        val jobs = plan()
        val firstOf = { k: JobKind -> jobs.indexOfFirst { it.kind == k } }
        assertEquals(JobKind.LAST, jobs.first().kind)
        assertTrue(jobs.filter { it.kind == JobKind.LAST }.all { it.mapId == 2 })
        // targets of every map (Teyvat first, then the others) before any underground-floor target and before any whole-map scan
        val targetMaps = jobs.filter { it.kind == JobKind.TARGETS }.map { it.mapId }.distinct()
        assertEquals(listOf(2, 9, 7), targetMaps)
        assertTrue(jobs.indexOfLast { it.kind == JobKind.TARGETS } < firstOf(JobKind.FLOOR_TARGETS))
        assertTrue(jobs.indexOfLast { it.kind == JobKind.FLOOR_TARGETS } < firstOf(JobKind.WHOLE))
        assertTrue(jobs.filter { it.kind == JobKind.FLOOR_TARGETS }.all { it.mapId == 2 })   // the underground waypoints of Teyvat
    }

    @Test fun wholeMapScansStartWithTheCurrentMapThenTheOthers() {
        val whole = plan().filter { it.kind == JobKind.WHOLE }
        // likely scales: current map first, then the other maps
        val tier1 = whole.take(teyvat.scales.size + chasm.scales.size + enka.scales.size)
        assertEquals(listOf(2, 9, 7), tier1.map { it.mapId }.distinct())
        assertEquals(teyvat.scales, tier1.filter { it.mapId == 2 }.map { it.scale })
        // the ladder rest is scanned cheapest map first (a miss on the huge map must not delay the small ones)
        val rest = whole.drop(tier1.size)
        assertEquals(listOf(9, 7, 2), rest.filter { it.scale == 1.2 }.map { it.mapId })
        // a rung that equals a likely scale of a map is not scanned twice on that map
        assertEquals(1, whole.count { it.mapId == 2 && Math.abs(it.scale - 0.752) < 1e-9 })
    }

    @Test fun destinationMapComesFirstEverywhere() {
        val jobs = plan(cur = 2, dest = 9)
        assertEquals(JobKind.DEST, jobs.first().kind)
        assertEquals(9, jobs.first().mapId)
        assertEquals(9, jobs.first { it.kind == JobKind.TARGETS }.mapId)
        assertEquals(9, jobs.first { it.kind == JobKind.WHOLE }.mapId)
        assertEquals(JobKind.LAST, jobs[jobs.indexOfFirst { it.kind != JobKind.DEST }].kind)
    }

    @Test fun currentMapFollowsTheLock() {
        // after a lock on map 9 the next plan starts from there: last position on map 9, Teyvat searched like any other map
        val jobs = RelocPlan.build(9, null, listOf(chasm, teyvat, enka), ladder, hasLast = true)
        assertTrue(jobs.filter { it.kind == JobKind.LAST }.all { it.mapId == 9 })
        assertEquals(listOf(9, 2, 7), jobs.filter { it.kind == JobKind.TARGETS }.map { it.mapId }.distinct())
    }

    @Test fun noLastPositionNoTargetOrderFlag() {
        assertTrue(plan(last = false).none { it.kind == JobKind.LAST })
        val whole = RelocPlan.build(2, null, listOf(teyvat, chasm), ladder, hasLast = true, withTargets = false)
        assertTrue(whole.all { it.kind == JobKind.WHOLE })
    }

    @Test fun boxedScansOfTheBannerAreaGoFirst() {
        val boxed = PlanMap(2, 6.0e6, listOf(0.752), targets = false, floorTargets = false, boxed = true)
        val jobs = RelocPlan.build(2, null, listOf(boxed), emptyList(), hasLast = false)
        assertTrue(jobs[0].boxed); assertFalse(jobs[1].boxed)
    }

    // ---------------------------------------------------------------------------------------- time budget
    @Test fun sliceStopsAtTheBudgetAndResumes() {
        val run = SearchRun((1..50).map { SearchJob(JobKind.WHOLE, 2, 0.5 + it * 0.01) })
        var now = 0L
        val clock = { now }
        var ran = 0
        // every job "costs" 100 ms; a 1 s budget runs 10 jobs
        val found = SearchSlice.run(run, 1000.0, clock, { false }) { now += 100_000_000L; ran++; false }
        assertFalse(found)
        assertEquals(10, ran)
        assertFalse(run.done)
        SearchSlice.run(run, 1000.0, clock, { false }) { now += 100_000_000L; ran++; false }
        assertEquals(20, ran)                                    // the next slice continues where the first one stopped
        assertEquals(20, run.cursor)
    }

    @Test fun sliceRunsAtLeastOneJobAndStopsOnAResult() {
        val run = SearchRun((1..5).map { SearchJob(JobKind.TARGETS, 9, it.toDouble()) })
        var now = 0L
        var ran = 0
        SearchSlice.run(run, 0.0, { now }, { false }) { now += 5_000_000_000L; ran++; false }   // one job takes 5 s, budget 0
        assertEquals(1, ran)
        val seen = ArrayList<Double>()
        val found = SearchSlice.run(run, 1e9, { now }, { false }) { seen += it.scale; it.scale == 3.0 }
        assertTrue(found)
        assertEquals(listOf(2.0, 3.0), seen)
        assertEquals(3, run.cursor)
    }

    @Test fun bigJobIsCutAndNeverOverrunsTheBudget() {
        // a whole-map scan estimated at 4.5 s is cut into pieces of at most 600 ms; a slice (1200 ms) then never exceeds the budget
        val model = com.teyvatmap.thor.tracking.cv.CostModel { 250.0 }
        val run = SearchRun(listOf(SearchJob(JobKind.WHOLE, 2, 1.0)))
        var now = 0L
        var worst = 0.0
        var pieces = 0
        var slices = 0
        while (!run.done && slices < 50) {
            slices++
            val t0 = now
            SearchSlice.run(run, 1200.0, { now }, { false },
                estimateMs = { j -> 18.0 / j.parts * model.msPerUnit("W") },
                split = { j -> if (j.parts > 1) null else model.parts("W", 18.0, 600.0).let { n -> if (n > 1) List(n) { j.copy(part = it, parts = n) } else null } },
            ) { j -> now += (4_500_000_000L / j.parts); pieces++; false }
            worst = maxOf(worst, (now - t0) / 1e6)
        }
        assertTrue(run.done)
        assertEquals(8, pieces)
        assertTrue("slice took $worst ms", worst <= 1200.0)
    }

    @Test fun costModelLearnsTheDeviceSpeed() {
        val m = com.teyvatmap.thor.tracking.cv.CostModel { 250.0 }
        assertEquals(8, m.parts("W", 6.0, 200.0))
        m.observe("W", 6.0, 600.0)                                 // the device does 100 ms/Mpx
        assertEquals(100.0, m.msPerUnit("W"), 1e-6)
        assertEquals(1, m.parts("W", 6.0, 700.0))
    }

    @Test fun cancelStopsAtTheNextJobBoundary() {
        val run = SearchRun((1..30).map { SearchJob(JobKind.WHOLE, 2, it.toDouble()) })
        var cancel = false
        var ran = 0
        SearchSlice.run(run, 1e9, { 0L }, { cancel }) { ran++; if (ran == 3) cancel = true; false }
        assertEquals(3, ran)
    }

    @Test fun aFullLadderOnTheBigMapFitsInBoundedSlices() {
        // 40 whole-map scans of ~0.8 s each (phone estimate) are 30+ s as one blocking call; sliced, no slice exceeds budget + one job
        val run = SearchRun((1..40).map { SearchJob(JobKind.WHOLE, 2, it.toDouble()) })
        var now = 0L
        var slices = 0
        var worstMs = 0.0
        while (!run.done) {
            val t0 = now
            SearchSlice.run(run, 1200.0, { now }, { false }) { now += 800_000_000L; false }
            worstMs = maxOf(worstMs, (now - t0) / 1e6)
            slices++
        }
        assertTrue("slice took $worstMs ms", worstMs <= 1200.0 + 800.0)
        assertEquals(20, slices)
    }

    // ---------------------------------------------------------------------------------------- scan levels / ladder
    @Test fun scanLevelKeepsTheTemplateWideEnough() {
        // crop side 268 (R 133.4): tundra zoom -> z-3, town zoom -> z-1, in between z-2
        assertEquals(-3, RelocPlan.scanLevel(0.752, 268, 34))
        assertEquals(-2, RelocPlan.scanLevel(1.5, 268, 34))
        assertEquals(-1, RelocPlan.scanLevel(2.222, 268, 34))
        assertEquals(0, RelocPlan.scanLevel(9.0, 268, 34))
        assertEquals(-3, RelocPlan.scanLevel(0.1, 268, 34))
    }

    @Test fun scanRungsCoverTheLadderClosestFirst() {
        val r = RelocPlan.scanRungs(0.752, 0.752 * 0.4, 0.752 * 3.7, 0.06, around = 0.75)
        assertTrue(r.size in 35..40)
        assertEquals(0.752, r.first(), 0.752 * 0.06)
        assertTrue(r.min() >= 0.752 * 0.4 - 1e-9 && r.max() <= 0.752 * 3.7 + 1e-9)
    }

    // ---------------------------------------------------------------------------------------- learn on lock per map
    @Test fun zoomIsLearnedPerMapThroughTheCatchAllAnchor() {
        val persisted = ArrayList<Int>()
        val table = RegionZoomTable(catchAll(9), onChange = { persisted += it.anchorId })
        assertNotNull(table.regionAt(-119.0, -106.0))
        assertTrue(table.knownScales().isEmpty())                     // unknown: the ladder rungs around s0 are used
        table.observe(-119.5, -106.4, 0.737)
        assertEquals(0.737, table.scaleAt(-100.0, -90.0)!!, 1e-9)
        assertEquals(listOf(-9), persisted)
        assertEquals(emptyList<Any>(), catchAll(2))                   // Teyvat keeps its real anchors only
    }

    // ---------------------------------------------------------------------------------------- big-map fix rules
    private fun fix(arrow: Boolean, state: TrackState, hasPos: Boolean = true, same: Boolean = true, dist: Double = 0.0, ret: String = "track") =
        BigFixRules.accept(arrow, state, ret, hasPos, same, dist, maxJump = 250.0)

    @Test fun bigMapFixNeedsTheArrow() {
        assertFalse(fix(arrow = false, state = TrackState.RELOCATING))
        assertFalse(fix(arrow = false, state = TrackState.LOST, hasPos = false))
        assertFalse(fix(arrow = false, state = TrackState.HIDDEN))
        assertTrue(fix(arrow = true, state = TrackState.HIDDEN, dist = 10.0))
    }

    @Test fun pannedBigMapCannotMoveTheTrackedPlayer() {
        // the chasm log: 650, 1000 and 3500 px away from the tracked position while only the view was panned
        for (d in listOf(650.0, 1000.0, 3500.0)) {
            assertFalse(fix(arrow = true, state = TrackState.HIDDEN, dist = d))
            assertFalse(fix(arrow = true, state = TrackState.FIXED, dist = d))
            assertFalse(fix(arrow = true, state = TrackState.TRACKING, dist = d))
        }
        assertFalse(fix(arrow = true, state = TrackState.HIDDEN, same = false, dist = 5.0))     // another map without a loading screen
    }

    @Test fun afterALoadingScreenOrWhenLostAnyArrowFixIsFine() {
        assertTrue(fix(arrow = true, state = TrackState.RELOCATING, dist = 5000.0, same = false))
        assertTrue(fix(arrow = true, state = TrackState.LOST, dist = 5000.0))
        assertTrue(fix(arrow = true, state = TrackState.HIDDEN, ret = "lost", dist = 5000.0))
        assertTrue(fix(arrow = true, state = TrackState.TRACKING, hasPos = false))
    }
}
