package com.teyvatmap.thor.tracking.cv

import org.opencv.core.Mat
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Read access to the pixels of one full frame (BGR order out). */
abstract class PixelSource {
    abstract val width: Int
    abstract val height: Int

    /** Writes [n] consecutive pixels of row [y] starting at column [x] into [out] (3 bytes each, BGR). */
    abstract fun readRow(x: Int, y: Int, n: Int, out: ByteArray)

    /** Packed 0xBBGGRR of one pixel. */
    abstract fun sample(x: Int, y: Int): Int
}

/** RGBA_8888 pixels in a direct buffer (an `ImageReader` plane). */
class RgbaSource(private val buf: ByteBuffer, private val rowStride: Int, private val pixelStride: Int, override val width: Int, override val height: Int) : PixelSource() {
    private var tmp = ByteArray(0)

    override fun readRow(x: Int, y: Int, n: Int, out: ByteArray) {
        if (pixelStride == 4) {
            if (tmp.size < n * 4) tmp = ByteArray(n * 4)
            val d = buf.duplicate()
            d.position(y * rowStride + x * 4)
            d.get(tmp, 0, n * 4)
            var o = 0
            var i = 0
            for (k in 0 until n) {
                out[o] = tmp[i + 2]; out[o + 1] = tmp[i + 1]; out[o + 2] = tmp[i]
                o += 3; i += 4
            }
        } else {
            for (k in 0 until n) {
                val p = y * rowStride + (x + k) * pixelStride
                out[k * 3] = buf.get(p + 2); out[k * 3 + 1] = buf.get(p + 1); out[k * 3 + 2] = buf.get(p)
            }
        }
    }

    override fun sample(x: Int, y: Int): Int {
        val p = y * rowStride + x * pixelStride
        return ((buf.get(p + 2).toInt() and 0xff) shl 16) or ((buf.get(p + 1).toInt() and 0xff) shl 8) or (buf.get(p).toInt() and 0xff)
    }
}

/** A BGR (CV_8UC3) [Mat] holding a whole frame (tests, calibration, fixtures). */
class MatSource(private val m: Mat) : PixelSource() {
    override val width: Int get() = m.cols()
    override val height: Int get() = m.rows()
    private val px = ByteArray(3)

    override fun readRow(x: Int, y: Int, n: Int, out: ByteArray) {
        require(out.size == n * 3)
        m.get(y, x, out)
    }

    override fun sample(x: Int, y: Int): Int {
        m.get(y, x, px)
        return ((px[0].toInt() and 0xff) shl 16) or ((px[1].toInt() and 0xff) shl 8) or (px[2].toInt() and 0xff)
    }
}

/**
 * Builds the per-tick [FrameInput] from a full frame: only the minimap region and a 64x36 thumbnail are read, nothing
 * else of the frame is touched (PLAN §6.1).
 */
object FrameExtractor {
    const val THUMB_W = 64
    const val THUMB_H = 36
    /** The ring is sampled up to R + 5.5 px; keep a margin. */
    private const val MARGIN = 8

    /** (x, y, w, h) of the region read around the minimap circle, clamped to the frame. */
    fun roiRect(calib: Calib, frameW: Int, frameH: Int): IntArray {
        val x0 = max(0, Math.floor(calib.cx - calib.r - MARGIN).toInt())
        val y0 = max(0, Math.floor(calib.cy - calib.r - MARGIN).toInt())
        val x1 = min(frameW, ceil(calib.cx + calib.r + MARGIN).toInt() + 1)
        val y1 = min(frameH, ceil(calib.cy + calib.r + MARGIN).toInt() + 1)
        return intArrayOf(x0, y0, max(1, x1 - x0), max(1, y1 - y0))
    }

    fun extract(src: PixelSource, calib: Calib, timeNanos: Long = 0, withThumb: Boolean = true): FrameInput {
        val r = roiRect(calib, src.width, src.height)
        val roi = ByteArray(r[2] * r[3] * 3)
        val row = ByteArray(r[2] * 3)
        for (y in 0 until r[3]) {
            src.readRow(r[0], r[1] + y, r[2], row)
            System.arraycopy(row, 0, roi, y * r[2] * 3, row.size)
        }
        val thumb = if (withThumb) thumbnail(src) else ByteArray(THUMB_W * THUMB_H)
        return FrameInput(roi, r[2], r[3], r[0], r[1], thumb, timeNanos)
    }

    /**
     * 64x36 gray thumbnail, equivalent to `screen.thumbnail`: every 10th pixel (offset 5) of a 1080p frame, 3x3
     * samples averaged per thumbnail pixel (other sizes scale the stride and use proportional boxes).
     */
    fun thumbnail(src: PixelSource): ByteArray {
        val w = src.width
        val h = src.height
        val sy = max(1, h / 108)
        val sx = max(1, w / 192)
        val ys = (sy / 2 until h step sy).toList()
        val xs = (sx / 2 until w step sx).toList()
        val out = ByteArray(THUMB_W * THUMB_H)
        for (ty in 0 until THUMB_H) {
            val ya = ty * ys.size / THUMB_H
            val yb = max(ya + 1, (ty + 1) * ys.size / THUMB_H)
            for (tx in 0 until THUMB_W) {
                val xa = tx * xs.size / THUMB_W
                val xb = max(xa + 1, (tx + 1) * xs.size / THUMB_W)
                var sb = 0; var sg = 0; var sr = 0; var n = 0
                for (j in ya until yb) for (i in xa until xb) {
                    val p = src.sample(xs[i], ys[j])
                    sb += (p shr 16) and 0xff; sg += (p shr 8) and 0xff; sr += p and 0xff; n++
                }
                val b = Math.rint(sb.toDouble() / n).toInt()
                val g = Math.rint(sg.toDouble() / n).toInt()
                val r = Math.rint(sr.toDouble() / n).toInt()
                out[ty * THUMB_W + tx] = ((1868 * b + 9617 * g + 4899 * r + 8192) shr 14).toByte()
            }
        }
        return out
    }
}
