package com.teyvatmap.thor.cv

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teyvatmap.thor.tracking.cv.Cv
import com.teyvatmap.thor.tracking.cv.CvIndex
import com.teyvatmap.thor.tracking.cv.CvIndexBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IndexBuildTest {
    @Test
    fun opencvLoads() {
        Cv.init()
        assertNotNull(org.opencv.core.Core.getVersionString())
        Log.i(CvTestEnv.TAG, "OpenCV ${org.opencv.core.Core.getVersionString()}")
    }

    @Test
    fun indexOfTeyvatIsComplete() {
        val idx = CvTestEnv.index(2)
        val m = idx.meta
        Log.i(CvTestEnv.TAG, "index: levels=${m.levels.map { "${it.z}:${it.w}x${it.h}" }} floors=${m.floors.size} targets=${m.targets} built in ${m.buildMs} ms")
        assertEquals(listOf(-3, -2, -1, 0), m.levels.map { it.z })
        assertTrue("floors ${m.floors.size}", m.floors.size > 200)
        assertTrue("targets ${m.targets}", m.targets in 700..900)
        val dir: File = idx.dir
        for (z in listOf(-3, -2, -1, 0)) {
            val lv = m.levels.first { it.z == z }
            assertEquals((lv.w.toLong() * lv.h), File(dir, "prep_z$z.bin").length())
        }
        // the prepared image must carry texture (LCN values spread around 128), not an empty file
        val ref = idx.prep(-2)
        val mm = org.opencv.core.Core.mean(ref.mat.submat(org.opencv.core.Rect(ref.mat.cols() / 2, ref.mat.rows() / 2, 400, 400)))
        Log.i(CvTestEnv.TAG, "mean of a z-2 patch ${mm.`val`[0]}")
        assertTrue(mm.`val`[0] in 100.0..160.0)
        assertTrue(idx.targets.ofKind("statue").isNotEmpty() && idx.targets.ofKind("domain").isNotEmpty() && idx.targets.ofKind("waypoint").isNotEmpty())
        assertNotNull(idx.floorRef(idx.layers.floors.first().floorId, -1))
        idx.close()
    }
}
