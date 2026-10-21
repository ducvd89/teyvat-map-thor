package com.teyvatmap.thor.tracking.cv

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * Coordinate conventions (identical to `tools/cv/mapdata.py`): all "z0" values are continuous zoom-0 pixels,
 * `px0 = x_pos + origin.x`. A level-z reference image stores the land bounding box with integer offset (ox, oy).
 */
@Serializable
data class MapGeo(
    val mapId: Int,
    val version: String,
    val totalW: Int,
    val totalH: Int,
    val padX: Int,
    val padY: Int,
    val originX: Int,
    val originY: Int,
    val origW: Int,
    val origH: Int,
    /** Lowest zoom the server has tiles for; coarser working levels are derived from it (small maps stop at -2). */
    val minZoom: Int = -3,
) {
    fun toPos(px0: Double, py0: Double) = P2(px0 - originX, py0 - originY)
    fun toPx0(xPos: Double, yPos: Double) = P2(xPos + originX, yPos + originY)

    companion object {
        /** Reads `maps/{id}/info.json` of the pack (`info.detail_v2` with `padding` and `original_map_size`). */
        fun fromInfoJson(mapId: Int, root: JsonElement): MapGeo? {
            val top = root as? JsonObject ?: return null
            val data = (top["data"] as? JsonObject) ?: top
            val info = (data["info"] as? JsonObject) ?: data
            val d = (info["detail_v2"] as? JsonObject) ?: info.takeIf { it["total_size"] != null } ?: return null
            fun ints(k: String): List<Int>? = (d[k] as? JsonArray)?.map { it.jsonPrimitive.content.toDouble().toInt() }
            val size = ints("total_size") ?: return null
            val origin = ints("origin") ?: return null
            val pad = ints("padding") ?: listOf(0, 0)
            val orig = ints("original_map_size") ?: size
            val version = d["map_version"]?.jsonPrimitive?.content ?: return null
            return MapGeo(mapId, version, size[0], size[1], pad[0], pad[1], origin[0], origin[1], orig[0], orig[1],
                (d["min_zoom"]?.jsonPrimitive?.content?.toDouble()?.toInt() ?: -3))
        }
    }
}

/** Geometry of one mosaic/prepared level. `u = px0 * 2^z - ox`. */
@Serializable
data class Level(val z: Int, val ox: Int, val oy: Int, val w: Int, val h: Int) {
    val f: Double get() = 2.0.pow(z)
    fun fromZ0(px0: Double, py0: Double) = P2(px0 * f - ox, py0 * f - oy)
    fun toZ0(u: Double, v: Double) = P2((u + ox) / f, (v + oy) / f)
}

/** Level geometry covering the land bounding box of [g]. */
fun levelGeom(g: MapGeo, z: Int): Level {
    val f = 2.0.pow(z)
    val x0 = g.padX.toDouble()
    val y0 = g.padY.toDouble()
    val x1 = x0 + g.origW
    val y1 = y0 + g.origH
    val ox = floor(x0 * f).toInt()
    val oy = floor(y0 * f).toInt()
    val ex = ceil(x1 * f).toInt()
    val ey = ceil(y1 * f).toInt()
    return Level(z, ox, oy, ex - ox, ey - oy)
}

/** Finest useful level so that the template is down-sampled (not up-sampled) onto the map. */
fun levelForScale(s: Double, zmin: Int = -3, zmax: Int = 0): Int =
    floor(kotlin.math.log2(maxOf(s, 1e-3)) + 1e-6).toInt().coerceIn(zmin, zmax)
