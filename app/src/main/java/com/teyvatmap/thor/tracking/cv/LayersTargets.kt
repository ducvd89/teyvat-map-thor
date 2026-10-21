package com.teyvatmap.thor.tracking.cv

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.hypot

/** One underground floor: overlay bounds in zoom-0 px (origin applied). */
@Serializable
data class Floor(
    val groupId: Int,
    val floorId: Int,
    val name: String,
    val l: Double,
    val t: Double,
    val r: Double,
    val b: Double,
) {
    fun contains(px0: Double, py0: Double, margin: Double = 0.0) =
        px0 >= l - margin && px0 <= r + margin && py0 >= t - margin && py0 <= b + margin
}

/** All floors of a map + rectangle lookup on a uniform grid (the "layer zone" test of PLAN §6.2). */
class LayerIndex(val floors: List<Floor>) {
    private val grid = HashMap<Long, MutableList<Floor>>()
    val byId: Map<Int, Floor> = floors.associateBy { it.floorId }
    val groups: Map<Int, List<Floor>> = floors.groupBy { it.groupId }
    val zoneRect: Map<Int, DoubleArray> = groups.mapValues { (_, fs) ->
        doubleArrayOf(fs.minOf { it.l }, fs.minOf { it.t }, fs.maxOf { it.r }, fs.maxOf { it.b })
    }

    init {
        for (f in floors) {
            for (i in floor(f.l / CELL).toInt()..floor(f.r / CELL).toInt()) {
                for (j in floor(f.t / CELL).toInt()..floor(f.b / CELL).toInt()) {
                    grid.getOrPut(key(i, j)) { ArrayList(2) } += f
                }
            }
        }
    }

    private fun key(i: Int, j: Int): Long = (i.toLong() shl 32) xor (j.toLong() and 0xffffffffL)

    /** Floors whose bounds contain the point (the layer-check candidates). */
    fun floorsAt(px0: Double, py0: Double, margin: Double = 0.0): List<Floor> {
        val c = grid[key(floor(px0 / CELL).toInt(), floor(py0 / CELL).toInt())] ?: return emptyList()
        return c.filter { it.contains(px0, py0, margin) }
    }

    fun inZone(px0: Double, py0: Double): Boolean = floorsAt(px0, py0).isNotEmpty()

    companion object {
        const val CELL = 256.0
        /** Floors smaller than this (z0 px) cannot hold a minimap and are ignored. */
        const val MIN_SIDE = 40.0
        val EMPTY = LayerIndex(emptyList())
    }
}

/**
 * A layer entry point (PLAN §6.2): a real point (cave, pathway, underground waypoint ...) or a group's own entrance
 * coordinate, in zoom-0 px, with the floor(s) it leads to. [pointId] 0 = group coordinate.
 */
@Serializable
data class Entrance(
    val x: Double,
    val y: Double,
    val floorIds: List<Int>,
    val groupId: Int,
    val pointId: Long = 0,
)

/** Spatial index over the entrances of one map. */
class EntranceIndex(val entrances: List<Entrance>) {
    private val grid = HashMap<Long, MutableList<Entrance>>()

    init {
        for (e in entrances) grid.getOrPut(key(floor(e.x / CELL).toInt(), floor(e.y / CELL).toInt())) { ArrayList(2) } += e
    }

    private fun key(i: Int, j: Int): Long = (i.toLong() shl 32) xor (j.toLong() and 0xffffffffL)

    /** Entrances within [radius] z0 px of the point, nearest first. */
    fun near(px0: Double, py0: Double, radius: Double): List<Entrance> {
        if (entrances.isEmpty()) return emptyList()
        val i0 = floor((px0 - radius) / CELL).toInt(); val i1 = floor((px0 + radius) / CELL).toInt()
        val j0 = floor((py0 - radius) / CELL).toInt(); val j1 = floor((py0 + radius) / CELL).toInt()
        val out = ArrayList<Pair<Double, Entrance>>()
        for (i in i0..i1) for (j in j0..j1) {
            grid[key(i, j)]?.forEach { e ->
                val d = hypot(e.x - px0, e.y - py0)
                if (d <= radius) out += d to e
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    fun nearest(px0: Double, py0: Double, maxDist: Double): Entrance? = near(px0, py0, maxDist).firstOrNull()

    companion object {
        const val CELL = 128.0
        val EMPTY = EntranceIndex(emptyList())
    }
}

/** Teleport targets (label tree "Waypoints" -> points) for the RELOCATING step 2. */
class Targets(val xy: DoubleArray, val kind: ByteArray, val link: IntArray = IntArray(kind.size)) {
    val size: Int get() = kind.size

    /** Floor id this target is an entrance of (the 12 underground Teleport Waypoints), 0 = none. */
    fun linkedFloor(i: Int) = link[i]

    fun x(i: Int) = xy[2 * i]
    fun y(i: Int) = xy[2 * i + 1]

    fun ofKind(k: String): IntArray {
        val kk = KINDS.indexOf(k).toByte()
        return (0 until size).filter { kind[it] == kk }.toIntArray()
    }

    /**
     * (priority indices, remaining indices). first = "domain": domains within [near] z0 px of [last], nearest first;
     * first = "statue": all statues, nearest first.
     */
    fun order(last: P2?, first: String?, near: Double): Pair<IntArray, IntArray> {
        if (first == null || last == null) return IntArray(0) to IntArray(size) { it }
        var idx = ofKind(first)
        val d = HashMap<Int, Double>()
        idx.forEach { d[it] = hypot(x(it) - last.x, y(it) - last.y) }
        idx = idx.sortedBy { d[it]!! }.toIntArray()
        if (first == "domain") idx = idx.filter { d[it]!! <= near }.toIntArray()
        val taken = idx.toHashSet()
        val rest = (0 until size).filter { it !in taken }.toIntArray()
        return idx to rest
    }

    fun subset(idx: IntArray): Targets {
        val a = DoubleArray(idx.size * 2)
        val k = ByteArray(idx.size)
        idx.forEachIndexed { n, i -> a[2 * n] = x(i); a[2 * n + 1] = y(i); k[n] = kind[i] }
        return Targets(a, k, IntArray(idx.size) { link[idx[it]] })
    }

    companion object {
        val KINDS = listOf("waypoint", "statue", "domain", "other")
        val EMPTY = Targets(DoubleArray(0), ByteArray(0))
    }
}
