package com.teyvatmap.thor.tracking

import com.teyvatmap.thor.data.store.PackParser
import com.teyvatmap.thor.tracking.cv.Calib
import com.teyvatmap.thor.tracking.cv.Entrance
import com.teyvatmap.thor.tracking.cv.EntranceIndex
import com.teyvatmap.thor.tracking.cv.Floor
import com.teyvatmap.thor.tracking.cv.FrameExtractor
import com.teyvatmap.thor.tracking.cv.LayerIndex
import com.teyvatmap.thor.tracking.cv.MapGeo
import com.teyvatmap.thor.tracking.cv.P2
import com.teyvatmap.thor.tracking.cv.RgbaSource
import com.teyvatmap.thor.tracking.cv.Targets
import com.teyvatmap.thor.tracking.cv.TrackerParams
import com.teyvatmap.thor.tracking.cv.levelForScale
import com.teyvatmap.thor.tracking.cv.levelGeom
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-Kotlin parts of the tracking pipeline (the OpenCV parts are covered by the instrumented tests). */
class CvLogicTest {
    private val geo = MapGeo(2, "v", 36864, 18432, 4096, 4096, 18432, 9216, 28000, 14000)

    @Test fun levelGeometryCoversLandBox() {
        val l = levelGeom(geo, -2)
        assertEquals(1024, l.ox); assertEquals(1024, l.oy)
        assertEquals(7000, l.w); assertEquals(3500, l.h)
        val p = l.fromZ0(5000.0, 6000.0)
        assertEquals(5000.0, l.toZ0(p.x, p.y).x, 1e-9)
        assertEquals(-1, levelForScale(0.8, -2, 0)); assertEquals(0, levelForScale(1.3)); assertEquals(-2, levelForScale(0.1, -2, 0))
    }

    @Test fun layerZonesAndEntrances() {
        val f1 = Floor(7, 70, "A", 100.0, 100.0, 400.0, 300.0)
        val f2 = Floor(7, 71, "B", 200.0, 150.0, 500.0, 350.0)
        val li = LayerIndex(listOf(f1, f2))
        assertEquals(setOf(70, 71), li.floorsAt(300.0, 200.0).map { it.floorId }.toSet())
        assertEquals(listOf(70), li.floorsAt(120.0, 120.0).map { it.floorId })
        assertTrue(!li.inZone(800.0, 800.0))
        val ei = EntranceIndex(listOf(Entrance(150.0, 150.0, listOf(70), 7, 11), Entrance(190.0, 150.0, listOf(71), 7, 12), Entrance(900.0, 900.0, listOf(70), 7, 13)))
        assertEquals(listOf(11L, 12L), ei.near(155.0, 150.0, 60.0).map { it.pointId })
        assertEquals(13L, ei.nearest(880.0, 880.0, 60.0)?.pointId)
        assertTrue(ei.near(500.0, 500.0, 60.0).isEmpty())
    }

    @Test fun targetsOrderPutsNearestFirst() {
        val t = Targets(doubleArrayOf(0.0, 0.0, 100.0, 0.0, 5000.0, 0.0, 10.0, 0.0), byteArrayOf(2, 2, 2, 1), intArrayOf(0, 0, 0, 55))
        val (prio, rest) = t.order(P2(90.0, 0.0), "domain", 3000.0)
        assertEquals(listOf(1, 0), prio.toList())          // the far domain is out of range
        assertEquals(listOf(2, 3), rest.toList())
        assertEquals(55, t.linkedFloor(3))
        assertEquals(listOf(3), t.order(P2(0.0, 0.0), "statue", 0.0).first.toList())
        assertEquals(4, t.order(null, "domain", 1.0).second.size)
    }

    @Test fun paramsRoundTripAndDefaultsMatchReadme() {
        val p = TrackerParams(tauLocal = 0.33, layerPeriod = 7.0)
        assertEquals(p, TrackerParams.fromJson(p.toJson()))
        val d = TrackerParams()
        assertEquals(0.40, d.tauLocal, 0.0); assertEquals(0.55, d.tauRelocLocal, 0.0); assertEquals(0.50, d.tauGlobal, 0.0)
        assertEquals(0.70, d.ringThr, 0.0); assertEquals(228.0, d.loadBright, 0.0); assertEquals(10.0, d.layerPeriod, 0.0)
        assertEquals(60.0, d.entranceRadius, 0.0)
        // real Thor defaults (data/cv/real_report.md): ring contrast test, mask 0.18R..0.80R, ladder step <= 2.5 %, calibration from the ring fit
        assertEquals("contrast", d.ringMode); assertEquals(6.0, d.ringContrastThr, 0.0)
        assertEquals(0.18, d.rIn, 0.0); assertEquals(0.80, d.rOut, 0.0); assertTrue(d.maskNorthRect && !d.maskNorth)
        assertTrue(d.scaleStep <= 0.025)
        assertEquals(210.5, Calib().cx, 0.0); assertEquals(156.1, Calib().cy, 0.0); assertEquals(133.4, Calib().r, 0.0); assertEquals(0.752, Calib().s, 0.0)
        val syn = d.synthetic()
        assertEquals("ncc", syn.ringMode); assertEquals(0.55, syn.scaleMin, 0.0); assertEquals(1.45, syn.scaleMax, 0.0); assertEquals(0.04, syn.scaleStep, 0.0)
    }

    @Test fun roiAndThumbnailFromRgbaFrame() {
        val w = 1920; val h = 1080
        val stride = w * 4 + 16
        val buf = ByteBuffer.allocateDirect(stride * h)
        // uniform grey 200 with a bright 10x10 spot
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (x in 100..109 && y in 100..109) 255 else 200
            val o = y * stride + x * 4
            buf.put(o, v.toByte()); buf.put(o + 1, v.toByte()); buf.put(o + 2, v.toByte()); buf.put(o + 3, 255.toByte())
        }
        val calib = Calib()
        val f = FrameExtractor.extract(RgbaSource(buf, stride, 4, w, h), calib)
        assertEquals(69, f.roiX); assertEquals(14, f.roiY); assertEquals(284, f.roiW); assertEquals(285, f.roiH)
        assertEquals(f.roiW * f.roiH * 3, f.roi.size)
        assertEquals(64 * 36, f.thumb.size)
        assertEquals(200, f.thumb[5 * 64 + 40].toInt() and 0xff)
        assertEquals(255, f.roi[((100 - f.roiY) * f.roiW + (100 - f.roiX)) * 3].toInt() and 0xff)
    }

    @Test fun mapGeoAndEntranceParsing() {
        val info = PackParser.json.parseToJsonElement("""{"info":{"detail_v2":{"total_size":[36864,18432],"padding":[4096,4096],"origin":[18432,9216],"map_version":"abc","original_map_size":[28000,14000]}}}""")
        val g = MapGeo.fromInfoJson(2, info)!!
        assertEquals(28000, g.origW); assertEquals("abc", g.version)
        val pg = PackParser.json.parseToJsonElement("""{"list":[{"id":9,"underground_entrance_x_pos":1.5,"underground_entrance_y_pos":0,"floors":[{"id":90,"entrance_ids":[5,6]},{"id":91,"entrance_ids":[7]}]}]}""")
        val (links, groups) = PackParser.parseEntrances(pg, 2)
        assertEquals(listOf(90 to 5L, 90 to 6L, 91 to 7L), links.map { it.floorId to it.pointId })
        assertEquals(1.5, groups.single().entranceX, 0.0)
    }
}
