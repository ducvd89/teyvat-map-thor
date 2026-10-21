package com.teyvatmap.thor.tracking.cv

import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Scalar

/** Loads the OpenCV native library once per process. Safe to call from any thread, any number of times. */
object Cv {
    @Volatile private var loaded = false

    @Synchronized
    fun init() {
        if (loaded) return
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        loaded = true
    }
}

/**
 * Collects temporary [Mat]s and releases their native memory deterministically. The JVM cannot see native
 * allocations, so relying on the garbage collector would let hundreds of MB pile up during a whole-map search.
 */
class Mats : AutoCloseable {
    private val all = ArrayList<Mat>(16)

    fun mat(): Mat = Mat().also { all += it }
    fun mat(rows: Int, cols: Int, type: Int): Mat = Mat(rows, cols, type).also { all += it }
    fun zeros(rows: Int, cols: Int, type: Int): Mat = Mat.zeros(rows, cols, type).also { all += it }
    fun full(rows: Int, cols: Int, type: Int, v: Double): Mat = Mat(rows, cols, type, Scalar(v)).also { all += it }
    fun <M : Mat> track(m: M): M = m.also { all += it }

    override fun close() {
        for (m in all) m.release()
        all.clear()
    }
}

inline fun <T> mats(block: (Mats) -> T): T = Mats().use(block)

/** A point in continuous coordinates. */
data class P2(val x: Double, val y: Double)
