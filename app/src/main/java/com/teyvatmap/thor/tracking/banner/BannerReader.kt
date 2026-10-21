package com.teyvatmap.thor.tracking.banner

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.teyvatmap.thor.tracking.FrameSource
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean

/** Region of the screen that holds the area-name banner (1080p px): centre-top, configurable. */
data class BannerRoi(val x: Int = 460, val y: Int = 80, val w: Int = 1000, val h: Int = 200) {
    companion object { val DEFAULT = BannerRoi() }
}

/** Turns a bitmap into text lines (ML Kit on the device; a fake in tests). */
fun interface TextReader {
    fun read(bitmap: Bitmap, done: (List<String>?) -> Unit)
}

/** ML Kit on-device text recognition (bundled Latin model: the Vietnamese tone marks are lost or garbled, which the matcher tolerates). */
class MlKitTextReader : TextReader, AutoCloseable {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun read(bitmap: Bitmap, done: (List<String>?) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { t -> done(t.textBlocks.flatMap { b -> b.lines.map { it.text } }) }
            .addOnFailureListener { e -> Log.w(TAG, "OCR failed: ${e.message}"); done(null) }
    }

    override fun close() { runCatching { recognizer.close() } }

    private companion object { const val TAG = "BannerReader" }
}

data class BannerEvent(val timeSec: Double, val lines: List<String>, val match: AreaMatch?)

/**
 * Reads the area-name banner (PLAN §6.0): the text that appears centre-top when the player enters a region or sub-area, OCR'd
 * from a configurable [roi] and fuzzy-matched to the anchor names in the game's language. Cheap by design: nothing runs unless
 * the minimap is on screen (normal gameplay), the ROI contains enough bright text-like pixels and changed since the last read,
 * and then at most one OCR per [minIntervalSec]. A match is reported once per anchor per [repeatSec].
 */
class BannerReader(
    private val matcher: AreaNameMatcher,
    private val reader: TextReader,
    private val roi: () -> BannerRoi = { BannerRoi.DEFAULT },
    private val minIntervalSec: Double = 2.0,
    private val repeatSec: Double = 20.0,
    /** Anchor id -> zoom-0 box (l, t, r, b). */
    private val boxOf: (Int) -> DoubleArray?,
) {
    private val busy = AtomicBoolean(false)
    private var lastT = -1e9
    private var lastSig: FloatArray? = null
    private val reported = HashMap<Int, Double>()

    @Volatile var last: BannerEvent? = null
        private set
    @Volatile var reads = 0
        private set
    @Volatile var skippedByPrecheck = 0
        private set

    /** Brightness signature (8x4 grid means) of the ROI and the fraction of near-white pixels (banner text is white with a dark outline). */
    internal fun precheck(rgb: Mat): Pair<FloatArray, Double> {
        val g = Mat(); Imgproc.cvtColor(rgb, g, Imgproc.COLOR_RGB2GRAY)
        val small = Mat(); Imgproc.resize(g, small, org.opencv.core.Size(8.0, 4.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val b = ByteArray(32); small.get(0, 0, b)
        val white = Mat(); Core.compare(g, org.opencv.core.Scalar(215.0), white, Core.CMP_GT)
        val frac = Core.countNonZero(white).toDouble() / (g.rows() * g.cols())
        g.release(); small.release(); white.release()
        return FloatArray(32) { (b[it].toInt() and 0xff).toFloat() } to frac
    }

    /** Call from the tracker thread each tick. [onMatch] gets the anchor's zoom-0 box and id (from any thread). */
    fun step(tSec: Double, src: FrameSource, minimapVisible: Boolean, onMatch: (DoubleArray?, Int) -> Unit) {
        if (!minimapVisible || tSec - lastT < minIntervalSec || busy.get()) return
        val r = roi()
        val bgr = src.grabRegion(r.x, r.y, r.w, r.h) ?: return
        val rgb = Mat(); Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB); bgr.release()
        val (sig, white) = precheck(rgb)
        val prev = lastSig
        val changed = prev == null || sig.indices.sumOf { Math.abs(sig[it] - prev[it]).toDouble() } / sig.size > 1.0
        if (white < MIN_WHITE || white > MAX_WHITE || !changed) { skippedByPrecheck++; rgb.release(); return }
        lastT = tSec
        lastSig = sig
        process(rgb, tSec, onMatch)
    }

    /** OCR of an RGB ROI (also the entry point of tests). Takes ownership of [rgb]. */
    fun process(rgb: Mat, tSec: Double, onMatch: (DoubleArray?, Int) -> Unit) {
        if (!busy.compareAndSet(false, true)) { rgb.release(); return }
        // OCR works best on larger glyphs
        val big = Mat(); Imgproc.resize(rgb, big, org.opencv.core.Size(), 1.0, 1.0)
        val bmp = Bitmap.createBitmap(big.cols(), big.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(big, bmp)
        big.release(); rgb.release()
        reads++
        reader.read(bmp) { lines ->
            try {
                bmp.recycle()
                if (lines == null) return@read
                val m = matcher.match(lines)
                last = BannerEvent(tSec, lines, m)
                Log.i(TAG, "banner OCR ${lines.map { it.take(40) }} -> ${m?.let { "${it.name} (${"%.2f".format(it.score)}) ${it.anchorIds}" }}")
                if (m != null) {
                    // several anchors with the same name: the smallest box wins
                    val id = m.anchorIds.minByOrNull { a -> boxOf(a)?.let { (it[2] - it[0]) * (it[3] - it[1]) } ?: Double.MAX_VALUE } ?: return@read
                    val seen = reported[id]
                    if (seen == null || tSec - seen >= repeatSec) {
                        reported[id] = tSec
                        onMatch(boxOf(id), id)
                    }
                }
            } finally { busy.set(false) }
        }
    }

    companion object {
        const val TAG = "BannerReader"
        const val MIN_WHITE = 0.003
        const val MAX_WHITE = 0.35
    }
}
