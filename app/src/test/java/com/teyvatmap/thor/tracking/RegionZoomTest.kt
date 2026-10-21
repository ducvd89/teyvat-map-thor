package com.teyvatmap.thor.tracking

import com.teyvatmap.thor.tracking.cv.RegionZoomTable
import com.teyvatmap.thor.tracking.cv.ZoomAnchor
import com.teyvatmap.thor.tracking.cv.ZoomEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionZoomTest {
    // Teyvat anchors.json: Volkodlak Tundra 363 contains Sretomorozsk 366 (and Rankova Zorya 371)
    private val tundra = ZoomAnchor(363, 0, "Volkodlak Tundra", -9030.0, -6254.0, -7744.0, -5338.0)
    private val town = ZoomAnchor(366, 363, "Sretomorozsk", -8678.0, -5524.0, -8242.0, -5128.0)
    private val rankova = ZoomAnchor(371, 363, "Rankova Zorya", -8744.0, -6339.0, -8402.0, -6037.0)
    private val seeds = RegionZoomTable.SEEDS.map { (id, s) -> ZoomEntry(id, s, 30, 0) }
    private val changed = ArrayList<ZoomEntry>()
    private val table = RegionZoomTable(listOf(tundra, town, rankova), seeds, { changed += it })

    @Test fun smallestContainingAnchorWins() {
        assertEquals(366, table.regionAt(-8418.0, -5344.0)!!.id)            // the indoor spot of the recording: town, not tundra
        assertEquals(363, table.regionAt(-8143.0, -6034.0)!!.id)            // the tundra spot
        assertEquals(2.222, table.scaleAt(-8418.0, -5344.0)!!, 1e-9)
        assertEquals(0.752, table.scaleAt(-8223.0, -6098.0)!!, 1e-9)
        assertNull(table.regionAt(0.0, 0.0))
    }

    @Test fun unknownRegionHasNoScaleButHintsFromTheParent() {
        val p = -8600.0 to -6200.0                                          // Rankova Zorya: nested in the tundra, no entry of its own
        assertEquals(371, table.regionAt(p.first, p.second)!!.id)
        assertNull(table.scaleAt(p.first, p.second))
        assertEquals(listOf(0.752), table.candidates(p.first, p.second, 0.0))   // the tundra's zoom as a hint
    }

    @Test fun bordersTryBothRegions() {
        // 60 z0 px outside the town box, inside the tundra: tundra first, the town's zoom too within the margin
        val c = table.candidates(-8242.0 + 60.0, -5400.0, 150.0)
        assertEquals(0.752, c[0], 1e-9)
        assertTrue(c.any { Math.abs(it - 2.222) < 1e-9 })
        // deep inside the tundra only its own zoom
        assertEquals(listOf(0.752), table.candidates(-8000.0, -6000.0, 150.0))
        // inside the town: its zoom first, the tundra's second (parent contains it)
        assertEquals(listOf(2.222, 0.752), table.candidates(-8418.0, -5344.0, 150.0))
    }

    @Test fun learnsByEmaAndPersists() {
        val before = table.entry(366)!!
        val e = table.observe(-8418.0, -5344.0, 2.3, persistEveryMs = 0)!!
        assertEquals(31, e.samples)
        assertTrue(e.scale > before.scale && e.scale < 2.3)
        assertEquals(366, changed.last().anchorId)
        // a region without an entry gets one on its first observation
        val n = table.observe(-8600.0, -6200.0, 1.1)!!
        assertEquals(371, n.anchorId); assertEquals(1.1, n.scale, 1e-9); assertEquals(1, n.samples)
        assertNotNull(table.scaleAt(-8600.0, -6200.0))
        assertNull(table.observe(0.0, 0.0, 1.0))
    }

    @Test fun knownScalesAreDistinctAndOrderedBySamples() {
        assertEquals(setOf(0.752, 2.222), table.knownScales().toSet())
        val t2 = RegionZoomTable(listOf(tundra), listOf(ZoomEntry(363, 0.75, 5, 0), ZoomEntry(999, 0.752, 50, 0)))
        assertEquals(1, t2.knownScales().size)                               // 0.75 and 0.752 are the same zoom
    }
}
