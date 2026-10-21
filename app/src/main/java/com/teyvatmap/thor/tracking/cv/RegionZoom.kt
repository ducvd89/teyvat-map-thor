package com.teyvatmap.thor.tracking.cv

import kotlin.math.abs
import kotlin.math.ln

/** One named region (HoYoLAB `map_anchor`), bounding box in game coordinates (`x_pos`, `y_pos`). */
data class ZoomAnchor(val id: Int, val parentId: Int, val name: String, val l: Double, val t: Double, val r: Double, val b: Double) {
    fun contains(x: Double, y: Double, margin: Double = 0.0) = x >= l - margin && x <= r + margin && y >= t - margin && y <= b + margin
    val area: Double get() = (r - l) * (b - t)
}

/** The minimap scale (minimap px per zoom-0 px) measured for one region; [samples] counts the observations behind it. */
data class ZoomEntry(val anchorId: Int, val scale: Double, val samples: Int, val updatedAt: Long)

/**
 * The region zoom table of PLAN §6.0 / §6.3: the game zooms the minimap per region (constant inside it), so the scale
 * prior of the tracker is looked up here by the position's smallest containing anchor. Entries are learned from locks
 * (EMA) and persisted through [onChange] (Room in the app). Pure Kotlin, thread-safe.
 *
 * Positions here are game coordinates (`x_pos`, `y_pos`); the tracker converts from zoom-0 px.
 */
class RegionZoomTable(
    anchors: List<ZoomAnchor>,
    entries: List<ZoomEntry> = emptyList(),
    private val onChange: (ZoomEntry) -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val anchors = anchors.sortedBy { it.area }          // smallest first: the first containing anchor wins
    private val byId = anchors.associateBy { it.id }
    private val entries = HashMap<Int, ZoomEntry>().also { m -> entries.forEach { m[it.anchorId] = it } }
    private val lastPersist = HashMap<Int, Long>()

    @Synchronized fun entry(anchorId: Int): ZoomEntry? = entries[anchorId]
    @Synchronized fun all(): List<ZoomEntry> = entries.values.sortedBy { it.anchorId }
    fun anchor(id: Int): ZoomAnchor? = byId[id]

    /** Smallest anchor containing the point (an anchor nested inside another one wins over its parent). */
    fun regionAt(x: Double, y: Double, margin: Double = 0.0): ZoomAnchor? = anchors.firstOrNull { it.contains(x, y, margin) }

    /** All anchors containing the point, smallest first. */
    fun regionsAt(x: Double, y: Double, margin: Double = 0.0): List<ZoomAnchor> = anchors.filter { it.contains(x, y, margin) }

    /** Scale of the point's own (smallest) region, or null when that region has no entry (= unknown: use the ladder). */
    @Synchronized
    fun scaleAt(x: Double, y: Double): Double? = regionAt(x, y)?.let { entries[it.id]?.scale }

    /** True when the point's smallest region has an entry. */
    fun isKnown(x: Double, y: Double): Boolean = scaleAt(x, y) != null

    /**
     * Scales to try at the point, most likely first: the own region's, then those of regions whose box contains the
     * point inflated by [margin] (a region border: both zooms are tried), then entries of the enclosing regions as hints
     * when the own region is unknown. Scales within 1.5 % of each other are merged.
     */
    @Synchronized
    fun candidates(x: Double, y: Double, margin: Double): List<Double> {
        val out = ArrayList<Double>()
        fun add(s: Double?) { if (s != null && out.none { abs(ln(it / s)) < 0.015 }) out += s }
        val own = regionAt(x, y)
        add(own?.let { entries[it.id]?.scale })
        for (a in regionsAt(x, y, margin)) add(entries[a.id]?.scale)          // smallest first, includes the borders
        if (own == null || entries[own.id] == null) {
            var p = own?.parentId?.let { byId[it] }
            while (p != null) { add(entries[p.id]?.scale); p = byId[p.parentId] }
        }
        return out
    }

    /** Distinct scales of the whole table (relocation without any position knowledge), most sampled first. */
    @Synchronized
    fun knownScales(): List<Double> {
        val out = ArrayList<Double>()
        for (e in entries.values.filter { it.anchorId > FLOOR_KEY_BASE }.sortedByDescending { it.samples }) if (out.none { abs(ln(it / e.scale)) < 0.015 }) out += e.scale
        return out
    }

    /**
     * Records a scale measured at the point (a confirmed lock). The smallest containing region's entry is updated by
     * a running mean for its first 10 samples and an EMA (alpha 0.1) afterwards; a missing entry is created.
     * Returns the updated entry, or null when no region contains the point.
     */
    @Synchronized
    fun observe(x: Double, y: Double, scale: Double, persistEveryMs: Long = 20_000): ZoomEntry? {
        val a = regionAt(x, y) ?: return null
        return update(a.id, scale, persistEveryMs)
    }

    private fun update(id: Int, scale: Double, persistEveryMs: Long): ZoomEntry {
        val now = nowMs()
        val old = entries[id]
        val upd = if (old == null) ZoomEntry(id, scale, 1, now) else {
            val alpha = if (old.samples < 10) 1.0 / (old.samples + 1) else 0.1
            ZoomEntry(id, old.scale * Math.pow(scale / old.scale, alpha), old.samples + 1, now)
        }
        entries[id] = upd
        val last = lastPersist[id] ?: 0L
        if (old == null || now - last >= persistEveryMs || abs(ln(upd.scale / (old.scale))) > 0.01) {
            lastPersist[id] = now
            onChange(upd)
        }
        return upd
    }

    /**
     * Minimap scale learned inside an underground floor group (the game zooms caves and fortresses by its own rule, e.g. the
     * Fortress of Meropide at 1.49 where the surface around it is 0.752). Keyed entries outside the anchor list (negative ids
     * below [FLOOR_KEY_BASE]), so they never shadow the surface regions; they persist; [knownScales] (surface) leaves them out, [floorScales] lists them.
     */
    @Synchronized fun floorScale(groupId: Int): Double? = entries[floorKey(groupId)]?.scale

    /** Learned scales of the floor groups, most sampled first. */
    @Synchronized
    fun floorScales(): List<Double> {
        val out = ArrayList<Double>()
        for (e in entries.values.filter { it.anchorId <= FLOOR_KEY_BASE }.sortedByDescending { it.samples }) if (out.none { abs(ln(it / e.scale)) < 0.015 }) out += e.scale
        return out
    }

    /** Records a scale measured on a confirmed lock on a floor of [groupId] (same running mean / EMA as [observe]). */
    @Synchronized
    fun observeFloor(groupId: Int, scale: Double, persistEveryMs: Long = 20_000): ZoomEntry = update(floorKey(groupId), scale, persistEveryMs)

    /** Sets an entry as is (seeding, big-map fix with the exact position). */
    @Synchronized
    fun put(e: ZoomEntry) { entries[e.anchorId] = e; lastPersist[e.anchorId] = nowMs(); onChange(e) }

    companion object {
        val EMPTY = RegionZoomTable(emptyList())

        /** Entry id of floor group g is `FLOOR_KEY_BASE - g` (catch-all anchors use `-mapId`, far above). */
        const val FLOOR_KEY_BASE = -1_000_000
        fun floorKey(groupId: Int) = FLOOR_KEY_BASE - groupId

        /** The two zoom values measured on the real Thor (data/cv/real_report.md section 2.3), anchor ids of Teyvat's anchors.json. */
        const val ANCHOR_VOLKODLAK_TUNDRA = 363
        const val ANCHOR_SRETOMOROZSK = 366
        val SEEDS: List<Pair<Int, Double>> = listOf(ANCHOR_VOLKODLAK_TUNDRA to 0.752, ANCHOR_SRETOMOROZSK to 2.222)
    }
}
