package com.teyvatmap.thor.tracking.cv

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Minimap circle detection and ring-reference capture for the calibration screen (PLAN §6.5). */
object CalibrationTools {
    /** (cx, cy, r) of the minimap circle found by Hough in the top-left [region] of a BGR frame, or null. */
    fun findMinimapCircle(frameBgr: Mat, region: IntArray = intArrayOf(0, 0, 420, 420)): Triple<Double, Double, Double>? = mats { sc ->
        val x1 = minOf(region[2], frameBgr.cols())
        val y1 = minOf(region[3], frameBgr.rows())
        val roi = sc.track(frameBgr.submat(Rect(region[0], region[1], x1 - region[0], y1 - region[1])))
        val g = sc.mat()
        Imgproc.cvtColor(roi, g, Imgproc.COLOR_BGR2GRAY)
        val b = sc.mat()
        Imgproc.GaussianBlur(g, b, Size(5.0, 5.0), 1.5)
        val circles = sc.mat()
        Imgproc.HoughCircles(b, circles, Imgproc.HOUGH_GRADIENT, 1.0, 50.0, 100.0, 40.0, 60, 170)
        if (circles.empty()) return@mats null
        val a = FloatArray(3)
        circles.get(0, 0, a)
        Triple(a[0] + region[0].toDouble(), a[1] + region[1].toDouble(), a[2].toDouble())
    }

    /** Stores the border-ring gray samples of [frame] (minimap visible) in a copy of [calib]. */
    fun withRingRef(calib: Calib, frame: FrameInput, params: TrackerParams): Calib {
        val sig = Screen(params).ringSignature(frame, calib)
        return calib.copy(ringRef = sig.map { Math.round(it * 10f) / 10f })
    }

    fun bgrMat(bytes: ByteArray, w: Int, h: Int): Mat = Mat(h, w, CvType.CV_8UC3).also { it.put(0, 0, bytes) }
}
