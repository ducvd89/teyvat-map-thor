package com.teyvatmap.thor.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teyvatmap.thor.data.store.PackParser
import com.teyvatmap.thor.tracking.banner.AreaName
import com.teyvatmap.thor.tracking.banner.AreaNameMatcher
import com.teyvatmap.thor.tracking.banner.MlKitTextReader
import com.teyvatmap.thor.tracking.banner.TextReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** ML Kit on-device OCR (bundled Latin model) on Vietnamese game text, then the fuzzy matcher against `map_anchor` names (vi-vn). */
@RunWith(AndroidJUnit4::class)
class BannerOcrTest {
    private fun matcher(): AreaNameMatcher {
        // names of the pack's anchors.json are English: the vi-vn ones for the strings used here
        val vi = mapOf(363 to "Lãnh Nguyên Volkodlak", 366 to "Sretomorozsk", 367 to "Nhà Gỗ Thợ Săn", 368 to "Hang Bài Ca Thủy Triều", 369 to "Nhà Hesperides", 371 to "Rankova Zorya", 364 to "Đỉnh Băng Giá", 362 to "Thung Lũng Lông Vũ Lửa")
        return AreaNameMatcher(vi.map { AreaName(it.key, it.value) })
    }

    private fun ocr(r: TextReader, bmp: Bitmap): List<String>? {
        val latch = CountDownLatch(1)
        var out: List<String>? = null
        r.read(bmp) { out = it; latch.countDown() }
        assertTrue("OCR finished", latch.await(60, TimeUnit.SECONDS))
        return out
    }

    @Test
    fun realVietnameseRegionLabelOfTheBigMap() {
        assumeTrue(RealEnv.available)
        val reader = MlKitTextReader()
        val bm = RealEnv.full("bigmap/bm1.png")
        // "Lãnh Nguyên Volkodlak / Độ thám hiểm 100%" bottom right of the big map: real in-game Vietnamese text
        val crop = bm.submat(Rect(1270, 880, 450, 90))
        val rgb = org.opencv.core.Mat(); Imgproc.cvtColor(crop, rgb, Imgproc.COLOR_BGR2RGB)
        val bmp = Bitmap.createBitmap(rgb.cols(), rgb.rows(), Bitmap.Config.ARGB_8888); Utils.matToBitmap(rgb, bmp)
        val t0 = System.nanoTime()
        val lines = ocr(reader, bmp)
        val ms = (System.nanoTime() - t0) / 1e6
        val m = matcher().match(lines ?: emptyList())
        Log.i(CvTestEnv.TAG, "BANNER OCR real region label: lines=$lines -> ${m?.name} ${m?.score} ${m?.anchorIds} (${ms.toLong()} ms incl. model load)")
        reader.close()
        assertNotNull("recognised the region name in $lines", m)
        assertEquals(listOf(363), m!!.anchorIds)
    }

    @Test
    fun renderedBannersAreMatched() {
        val reader = MlKitTextReader()
        val mt = matcher()
        val rows = ArrayList<String>()
        var ok = 0
        val cases = listOf("Lãnh Nguyên Volkodlak" to 363, "Sretomorozsk" to 366, "Nhà Gỗ Thợ Săn" to 367, "Hang Bài Ca Thủy Triều" to 368, "Đỉnh Băng Giá" to 364)
        for ((text, id) in cases) {
            // centre-top banner look: large white serif text with a dark outline over a bright snowy background
            val bmp = Bitmap.createBitmap(1000, 200, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(170, 195, 225))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 70f; typeface = Typeface.SERIF; textAlign = Paint.Align.CENTER }
            p.style = Paint.Style.STROKE; p.strokeWidth = 7f; p.color = Color.argb(200, 30, 50, 80)
            c.drawText(text, 500f, 115f, p)
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            c.drawText(text, 500f, 115f, p)
            val lines = ocr(reader, bmp)
            val m = mt.match(lines ?: emptyList())
            rows += "'$text' -> $lines -> ${m?.name}"
            if (m != null && id in m.anchorIds) ok++
        }
        Log.i(CvTestEnv.TAG, "BANNER OCR rendered: $ok/${cases.size}\n${rows.joinToString("\n")}")
        reader.close()
        assertTrue("rendered banners matched $ok/${cases.size}:\n${rows.joinToString("\n")}", ok >= cases.size - 1)
    }
}
