package com.teyvatmap.thor.tracking.cv

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log2

/** The steps of a relocation / lost search (PLAN §6.2 "RELOCATING search order"). */
enum class JobKind {
    /** The waypoint picked on the big map (teleport destination). */
    DEST,
    /** Local search around the last known position (last floor, surface, floors of a nearby entrance). */
    LAST,
    /** Teleport targets (the "Waypoints" category) of one map, on the surface reference. */
    TARGETS,
    /** Targets inside a layer zone against the floor overlays (underground waypoints). */
    FLOOR_TARGETS,
    /**
     * Every underground floor overlay of a map as its own small reference (the minimap inside a cave or fortress shows only the
     * floor drawing, the surface tiles never match it), at one scale. [SearchJob.part] / [SearchJob.parts] cut the priority-ordered floor list.
     */
    FLOORS,
    /** Whole-map scan at the coarse level, top candidates refined. [SearchJob.boxed]: only the banner's area. */
    WHOLE,
}

/** One unit of a search: runs on map [mapId] at minimap scale [scale]; the unit of the time budget and of cancelling. */
data class SearchJob(val kind: JobKind, val mapId: Int, val scale: Double, val boxed: Boolean = false, val part: Int = 0, val parts: Int = 1)

/** What the plan needs to know about one map. [cost] orders the whole-map scans (coarse map area), [scales] are the likely minimap scales, most likely first. */
class PlanMap(
    val id: Int, val cost: Double, val scales: List<Double>, val targets: Boolean, val floorTargets: Boolean, val boxed: Boolean = false,
    /** The map has floor overlays that are searched as references of their own ([JobKind.FLOORS]). */
    val floors: Boolean = false,
    /** Minimap scales learned on floor groups of this map (indoor zooms), tried first on floors. */
    val floorScales: List<Double> = emptyList(),
)

/**
 * The ordered job list of one search episode:
 *  1. teleport destination (map of the big-map pin), then the last known position on the current map;
 *  2. teleport targets of ALL maps (destination map and current map first), then the underground-floor targets;
 *  3. whole-map scans: the likely scales on every map (current map first), then the rest of the scale ladder, cheapest
 *     (smallest) maps first so that a miss on the huge Teyvat map does not hold up the small maps.
 * Pure data, no OpenCV: the order is unit-tested.
 */
object RelocPlan {
    fun build(
        cur: Int, destMap: Int?, maps: List<PlanMap>, ladder: List<Double>, hasLast: Boolean, withTargets: Boolean = true,
        /** Rungs of the floor scan (indoor zooms first); interleaved with the surface [ladder]. */
        floorLadder: List<Double> = emptyList(),
    ): List<SearchJob> {
        val jobs = ArrayList<SearchJob>()
        val byId = maps.associateBy { it.id }
        val curMap = byId[cur]
        // 1. destination, last position
        if (destMap != null) byId[destMap]?.let { m -> for (s in m.scales) jobs += SearchJob(JobKind.DEST, m.id, s) }
        if (withTargets && hasLast && curMap != null) for (s in curMap.scales) jobs += SearchJob(JobKind.LAST, cur, s)
        // 2. targets of all maps, then the floor targets
        val targetOrder = listOfNotNull(destMap?.let { byId[it] }, curMap).distinctBy { it.id } + maps.filter { it.id != destMap && it.id != cur }
        if (withTargets) {
            for (m in targetOrder) if (m.targets) for (s in m.scales) jobs += SearchJob(JobKind.TARGETS, m.id, s)
            for (m in targetOrder) if (m.floorTargets) for (s in floorLikely(m)) jobs += SearchJob(JobKind.FLOOR_TARGETS, m.id, s)
        }
        // 2c. the floor overlays as references of their own, at the likely scales (learned indoor zooms first)
        for (m in targetOrder) if (m.floors) for (s in floorLikely(m)) jobs += SearchJob(JobKind.FLOORS, m.id, s)
        // 3. whole maps: banner box first, likely scales (current map first), then the ladder, cheap maps first
        for (m in targetOrder) if (m.boxed) for (s in m.scales) jobs += SearchJob(JobKind.WHOLE, m.id, s, boxed = true)
        for (m in targetOrder) for (s in m.scales) jobs += SearchJob(JobKind.WHOLE, m.id, s)
        val cheap = maps.sortedBy { it.cost }
        // the rest of the ladders, one rung of the floor scan (indoor zooms) alternating with one rung of the surface scan
        val fg = floorLadder.map { s -> cheap.filter { m -> m.floors && floorLikely(m).none { close(it, s) } }.map { SearchJob(JobKind.FLOORS, it.id, s) } }
        val wg = ladder.map { s -> cheap.filter { m -> m.scales.none { close(it, s) } }.map { SearchJob(JobKind.WHOLE, it.id, s) } }
        for (i in 0 until maxOf(fg.size, wg.size)) {
            fg.getOrNull(i)?.let { jobs += it }
            wg.getOrNull(i)?.let { jobs += it }
        }
        return jobs
    }

    /** Scales a map's floors are tried at first: the learned indoor zooms, then the map's likely surface scales. */
    private fun floorLikely(m: PlanMap): List<Double> {
        val out = ArrayList<Double>()
        for (s in m.floorScales + m.scales) if (out.none { close(it, s) }) out += s
        return out
    }

    private fun close(a: Double, b: Double) = abs(ln(a / b)) < 0.02

    /** Ladder of the whole-map scan: geometric rungs [step] apart through [lo, hi] anchored on [base], closest to [around] first. */
    fun scanRungs(base: Double, lo: Double, hi: Double, step: Double, around: Double): List<Double> {
        val f = 1.0 + step
        val kMin = ceil(ln(lo / base) / ln(f) - 1e-9).toInt()
        val kMax = Math.floor(ln(hi / base) / ln(f) + 1e-9).toInt()
        return (kMin..kMax).map { base * Math.pow(f, it.toDouble()) }.sortedBy { abs(ln(it / around)) }
    }

    /**
     * Coarsest prepared level (z-3..0) whose minimap template at scale [s] is still at least [minT] px wide: the whole-map NCC
     * costs about the map area at the scanned level, so z-3 is 4x cheaper than z-2 (offline: 16/16 real frames found at z-3 for
     * the tundra zoom, z-2 for the town zoom, z-1 above s = 2.3).
     */
    fun scanLevel(s: Double, cropSide: Int, minT: Int): Int =
        ceil(log2(minT * s / cropSide) - 1e-9).toInt().coerceIn(-3, 0)
}

/** The unfinished job list of one search episode; a slice of work resumes where the previous one stopped. */
class SearchRun(jobs: List<SearchJob>) {
    private val list = ArrayList(jobs)
    val jobs: List<SearchJob> get() = list
    var cursor = 0
        private set
    val done: Boolean get() = cursor >= list.size

    fun next(): SearchJob? = if (done) null else list[cursor++]

    /** Puts the job [next] just returned back (it did not fit the slice's budget). */
    fun unread() { if (cursor > 0) cursor-- }

    /** Replaces the job [next] just returned by [parts] (a big job cut into pieces); the first piece is next. */
    fun split(parts: List<SearchJob>) {
        if (cursor == 0 || parts.isEmpty()) return
        cursor--
        list.removeAt(cursor)
        list.addAll(cursor, parts)
    }
}

/**
 * Self-calibrating cost model of the splittable jobs: milliseconds per work unit (megapixels scanned, targets tested) per
 * key. The prior assumes a slow phone; every measured job pulls the rate towards what this device really does.
 */
class CostModel(private val prior: (String) -> Double) {
    private val rate = HashMap<String, Double>()
    /** How much slower (or faster) than its prior this device runs: a key measured for the first time scales the priors of the keys not seen yet. */
    private var speed = 1.0

    fun msPerUnit(key: String): Double = rate[key] ?: (prior(key) * speed)

    fun observe(key: String, units: Double, ms: Double) {
        if (units <= 1e-9) return
        val r = ms / units
        val p = prior(key)
        if (rate[key] == null && p > 1e-9) speed = if (rate.isEmpty()) (r / p).coerceIn(0.1, 60.0) else (0.5 * speed + 0.5 * (r / p)).coerceIn(0.1, 60.0)
        rate[key] = rate[key]?.let { 0.4 * it + 0.6 * r } ?: r
    }

    /** Number of pieces so that each costs at most [targetMs] (1 = run whole). */
    fun parts(key: String, units: Double, targetMs: Double, max: Int = 16): Int =
        Math.ceil(units * msPerUnit(key) / targetMs.coerceAtLeast(50.0) - 1e-9).toInt().coerceIn(1, max)
}

/** Time-boxed execution of a [SearchRun] (the search must not block the tracker thread for 30 s). */
object SearchSlice {
    /**
     * Runs jobs until [step] reports a result (returns true), the run is finished, [cancelled] turns true or the budget of
     * [budgetMs] is used up. At least one job runs per slice (progress is guaranteed); the clock is checked after each job.
     *
     * A single job cannot be interrupted, so the budget is enforced before it starts: [split] may cut a big job into pieces
     * that each fit, and a job whose [estimateMs] does not fit into what is left of the slice waits for the next one.
     */
    fun run(
        run: SearchRun, budgetMs: Double, nowNs: () -> Long, cancelled: () -> Boolean,
        estimateMs: (SearchJob) -> Double = { 0.0 }, split: (SearchJob) -> List<SearchJob>? = { null },
        step: (SearchJob) -> Boolean,
    ): Boolean {
        val t0 = nowNs()
        var ran = 0
        while (true) {
            var j = run.next() ?: return false
            split(j)?.takeIf { it.size > 1 }?.let { run.split(it); j = run.next() ?: return false }
            if (ran > 0 && (nowNs() - t0) / 1e6 + estimateMs(j) > budgetMs) { run.unread(); return false }
            ran++
            if (step(j)) return true
            if (cancelled() || (nowNs() - t0) / 1e6 >= budgetMs) return false
        }
    }
}
