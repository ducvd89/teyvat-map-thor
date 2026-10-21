package com.teyvatmap.thor.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/** Static geometry of one HoYoLAB map (`detail_v2` of /v3/map/info). */
data class MapGeometry(
    val mapId: Int,
    val totalW: Int,
    val totalH: Int,
    val originX: Int,
    val originY: Int,
    val minZoom: Int,
    val maxZoom: Int,
    val tileSize: Int = 256,
) {
    /** Number of zoom levels available (MapCompose "levels"). */
    val levelCount: Int get() = maxZoom - minZoom + 1
}

data class PixelPoint(val x: Double, val y: Double)

data class TileIndex(val zoom: Int, val x: Int, val y: Int)

/**
 * Converts between the three coordinate spaces used by HoYoLAB maps:
 *
 *  1. game space: the `x_pos` / `y_pos` of points,
 *  2. zoom-0 pixel space: `px = x_pos + originX`, `py = y_pos + originY` (Leaflet CRS.Simple),
 *  3. tile space: at zoom `z` a pixel is `p * 2^z`, tile index `floor(p * 2^z / tileSize)`.
 *
 * It also produces the normalized (0..1) coordinates MapCompose uses for markers and scrolling.
 */
class MapTransform(val geometry: MapGeometry) {

    fun toPixel(xPos: Double, yPos: Double): PixelPoint =
        PixelPoint(xPos + geometry.originX, yPos + geometry.originY)

    fun toGame(px: Double, py: Double): PixelPoint =
        PixelPoint(px - geometry.originX, py - geometry.originY)

    /** Pixel position at an arbitrary zoom level. */
    fun scaleToZoom(zoom0Pixel: Double, zoom: Int): Double = zoom0Pixel * 2.0.pow(zoom)

    fun fromZoom(pixelAtZoom: Double, zoom: Int): Double = pixelAtZoom / 2.0.pow(zoom)

    fun tileOf(xPos: Double, yPos: Double, zoom: Int): TileIndex {
        val p = toPixel(xPos, yPos)
        return tileOfPixel(p.x, p.y, zoom)
    }

    fun tileOfPixel(px: Double, py: Double, zoom: Int): TileIndex {
        val ts = geometry.tileSize
        return TileIndex(
            zoom = zoom,
            x = floor(scaleToZoom(px, zoom) / ts).toInt(),
            y = floor(scaleToZoom(py, zoom) / ts).toInt(),
        )
    }

    /** Zoom-0 pixel of the top-left corner of a tile. */
    fun tileOrigin(tile: TileIndex): PixelPoint {
        val ts = geometry.tileSize
        return PixelPoint(fromZoom((tile.x * ts).toDouble(), tile.zoom), fromZoom((tile.y * ts).toDouble(), tile.zoom))
    }

    fun tilesWide(zoom: Int): Int = ceil(geometry.totalW * 2.0.pow(zoom) / geometry.tileSize).toInt()
    fun tilesHigh(zoom: Int): Int = ceil(geometry.totalH * 2.0.pow(zoom) / geometry.tileSize).toInt()

    /** Normalized (0..1) map coordinates used by MapCompose markers and scroll positions. */
    fun toNormalized(xPos: Double, yPos: Double): PixelPoint {
        val p = toPixel(xPos, yPos)
        return PixelPoint(p.x / geometry.totalW, p.y / geometry.totalH)
    }

    fun fromNormalized(nx: Double, ny: Double): PixelPoint =
        toGame(nx * geometry.totalW, ny * geometry.totalH)

    /** MapCompose level (0 = most zoomed out) for a HoYoLAB zoom value. */
    fun levelOfZoom(zoom: Int): Int = zoom - geometry.minZoom

    fun zoomOfLevel(level: Int): Int = geometry.minZoom + level

    /** Size in pixels of the whole map at the highest zoom (MapCompose "full size"). */
    val fullWidth: Int get() = (geometry.totalW * 2.0.pow(geometry.maxZoom)).toInt()
    val fullHeight: Int get() = (geometry.totalH * 2.0.pow(geometry.maxZoom)).toInt()
}
