package com.teyvatmap.thor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MapTransformTest {
    private val teyvat = MapGeometry(2, 36864, 18432, 24206, 8918, -3, 0)
    private val small = MapGeometry(7, 4096, 4096, 1849, 1779, -4, 0)
    private val t = MapTransform(teyvat)

    @Test fun gameToPixelAddsOrigin() {
        val p = t.toPixel(373.5, 145.5)
        assertEquals(24206 + 373.5, p.x, 1e-9)
        assertEquals(8918 + 145.5, p.y, 1e-9)
    }

    @Test fun pixelRoundTrip() {
        for ((x, y) in listOf(0.0 to 0.0, -9914.0 to -6977.0, 12000.5 to 9000.25)) {
            val p = t.toPixel(x, y)
            val g = t.toGame(p.x, p.y)
            assertEquals(x, g.x, 1e-9)
            assertEquals(y, g.y, 1e-9)
        }
    }

    @Test fun normalizedRoundTrip() {
        val n = t.toNormalized(-4274.37, 2529.5)
        val g = t.fromNormalized(n.x, n.y)
        assertEquals(-4274.37, g.x, 1e-6)
        assertEquals(2529.5, g.y, 1e-6)
        assertEquals((24206 - 4274.37) / 36864.0, n.x, 1e-9)
    }

    @Test fun tileIndexAtEachZoom() {
        // game origin -> pixel (24206, 8918)
        assertEquals(TileIndex(0, 94, 34), t.tileOf(0.0, 0.0, 0))
        assertEquals(TileIndex(-1, 47, 17), t.tileOf(0.0, 0.0, -1))
        assertEquals(TileIndex(-3, 11, 4), t.tileOf(0.0, 0.0, -3))
    }

    @Test fun tileOfKnownLandmarkMatchesWebTileName() {
        // 11_5_N3 in PLAN: x 11, y 5 at zoom -3 covers pixels x 22528..24575, y 10240..12287
        val tile = t.tileOfPixel(23000.0, 11000.0, -3)
        assertEquals(TileIndex(-3, 11, 5), tile)
        val o = t.tileOrigin(tile)
        assertEquals(22528.0, o.x, 1e-9)
        assertEquals(10240.0, o.y, 1e-9)
    }

    @Test fun tileCounts() {
        assertEquals(18, t.tilesWide(-3)); assertEquals(9, t.tilesHigh(-3))
        assertEquals(144, t.tilesWide(0)); assertEquals(72, t.tilesHigh(0))
        val total = (-3..0).sumOf { t.tilesWide(it) * t.tilesHigh(it) }
        assertEquals(13770, total)
        val s = MapTransform(small)
        assertEquals(341, (-4..0).sumOf { s.tilesWide(it) * s.tilesHigh(it) })
    }

    @Test fun levelMapping() {
        assertEquals(4, teyvat.levelCount)
        assertEquals(0, t.levelOfZoom(-3))
        assertEquals(3, t.levelOfZoom(0))
        assertEquals(-3, t.zoomOfLevel(0))
        assertEquals(5, small.levelCount)
        assertEquals(36864, t.fullWidth)
    }

    @Test fun negativeCoordinatesFloorCorrectly() {
        // A pixel slightly left of the map (negative) must give a negative tile index, not 0.
        assertEquals(-1, t.tileOfPixel(-1.0, 5.0, 0).x)
    }
}
