package com.teyvatmap.thor.tracking

import com.teyvatmap.thor.tracking.cv.MapGeo
import com.teyvatmap.thor.tracking.cv.MosaicGeom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mosaic-origin rule of the big-map registration: pixel (0,0) is (tx0*256, ty0*256) in level px, never the box corner. */
class MosaicGeomTest {
    private val geo = MapGeo(2, "v", 36864, 18432, 4096, 4096, 24206, 8918, 28000, 14000)

    @Test fun originIsATileMultipleNotTheBoxCorner() {
        // the quick script's bug: box corner (14276, 1764) at z0 instead of the tile multiple (14080, 1536) = (55*256, 6*256)
        val g = MosaicGeom.forBox(geo, 0, 14276.0, 1764.0, 14276.0 + 1900.0, 1764.0 + 1100.0)
        assertEquals(55, g.tx0); assertEquals(6, g.ty0)
        val o = g.toZ0(0.0, 0.0)
        assertEquals(14080.0, o.x, 0.0); assertEquals(1536.0, o.y, 0.0)
        assertEquals(14276.0, g.toZ0(14276.0 - 14080.0, 1764.0 - 1536.0).x, 1e-9)       // the corner sits at (196, 228) inside the mosaic
        assertEquals(196.0, g.fromZ0(14276.0, 1764.0).x, 1e-9); assertEquals(228.0, g.fromZ0(14276.0, 1764.0).y, 1e-9)
        assertEquals(g.w, (g.tx1 - g.tx0 + 1) * 256)
        // covers the whole box
        assertTrue(g.toZ0(g.w.toDouble(), g.h.toDouble()).x >= 14276.0 + 1900.0)
        assertTrue(g.toZ0(g.w.toDouble(), g.h.toDouble()).y >= 1764.0 + 1100.0)
    }

    @Test fun otherLevelsScaleTheOrigin() {
        for (z in -3..0) {
            val f = Math.pow(2.0, z.toDouble())
            val g = MosaicGeom.forBox(geo, z, 15000.0, 3000.0, 17000.0, 5000.0)
            val o = g.toZ0(0.0, 0.0)
            assertEquals(g.tx0 * 256 / f, o.x, 1e-9); assertEquals(g.ty0 * 256 / f, o.y, 1e-9)
            assertTrue("origin left of the box", o.x <= 15000.0 && o.y <= 3000.0)
            assertTrue("less than a tile outside", 15000.0 - o.x < 256 / f && 3000.0 - o.y < 256 / f)
            // round trip of an arbitrary point
            val p = g.fromZ0(16001.5, 4002.25)
            val q = g.toZ0(p.x, p.y)
            assertEquals(16001.5, q.x, 1e-9); assertEquals(4002.25, q.y, 1e-9)
        }
    }

    @Test fun boxesAreClampedToTheMap() {
        val g = MosaicGeom.forBox(geo, -1, -500.0, -500.0, 100000.0, 100000.0)
        assertEquals(0, g.tx0); assertEquals(0, g.ty0)
        assertEquals(Math.ceil(36864 * 0.5 / 256).toInt() - 1, g.tx1)
        assertEquals(Math.ceil(18432 * 0.5 / 256).toInt() - 1, g.ty1)
    }
}
