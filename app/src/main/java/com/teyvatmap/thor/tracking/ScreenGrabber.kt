package com.teyvatmap.thor.tracking

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import com.teyvatmap.thor.tracking.cv.Calib
import com.teyvatmap.thor.tracking.cv.FrameExtractor
import com.teyvatmap.thor.tracking.cv.FrameInput
import com.teyvatmap.thor.tracking.cv.RgbaSource
import org.opencv.core.CvType
import org.opencv.core.Mat

/** Pull-style frame provider: one frame per request, minimap region + thumbnail only (PLAN §6.1). */
interface FrameSource {
    /** Region of interest + 64x36 thumbnail of the newest frame, or null if none has arrived yet. */
    fun grab(calib: Calib, withThumb: Boolean = true): FrameInput?

    /** A BGR region of the newest frame (area-name banner), clamped to the frame, or null. */
    fun grabRegion(x: Int, y: Int, w: Int, h: Int): Mat?

    /** The whole newest frame as BGR (calibration preview and "save frame" only), or null. */
    fun grabFullBgr(): Mat?

    val frameSize: Pair<Int, Int>
}

/**
 * MediaProjection -> VirtualDisplay (mirror of the default display = the game screen) -> ImageReader. The reader's
 * listener only swaps the "newest image" reference (frames in between are closed unread); pixels are read when a
 * consumer calls [grab] / [grabFullBgr] (the tracker, at its own check rate).
 */
class ScreenGrabber(private val context: Context) : FrameSource {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val lock = Any()
    private var held: Image? = null
    private var handler: Handler? = null
    private var densityDpi = 0
    val displayCreated: Boolean get() = virtualDisplay != null
    var width = 0; private set
    var height = 0; private set
    @Volatile var framesSeen = 0L; private set
    @Volatile var onStopped: (() -> Unit)? = null
    /** Optional per-frame hook (diagnostics test mode); runs on the capture thread with the image still open. */
    @Volatile var push: ((Image) -> Unit)? = null

    override val frameSize: Pair<Int, Int> get() = width to height

    @Suppress("DEPRECATION")
    fun start(resultCode: Int, data: Intent): Boolean {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        // Display.getRealMetrics() reflects the *process's* display adjustments (1240x1080 when the app lives on the
        // second screen); the physical mode of display 0 is the real game resolution.
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val mode = display.mode
        val rotated = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
        width = if (rotated) mode.physicalHeight else mode.physicalWidth
        height = if (rotated) mode.physicalWidth else mode.physicalHeight
        val t = HandlerThread("capture").also { it.start(); thread = it }
        val handler = Handler(t.looper).also { this.handler = it }
        densityDpi = metrics.densityDpi
        val proj = mpm.getMediaProjection(resultCode, data) ?: error("MediaProjection unavailable")
        projection = proj
        // Android 14+: a callback must be registered before creating the virtual display.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { onStopped?.invoke() } }
        }, handler)
        // 3 buffers: one stays held as "newest frame", the producer keeps two.
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader = r
        r.setOnImageAvailableListener({ rd -> onImage(rd) }, handler)
        return true
    }

    /**
     * Creates the VirtualDisplay (once per projection; later calls do nothing). Call it only when
     * [CaptureGate.waitMs] is 0 (see [CapturePolicy]): creating it during a focus change can deadlock AYN's system_server.
     * Only AUTO_MIRROR is requested; the system adds PRIVATE/PRESENTATION itself for projections.
     */
    fun createDisplay(): Boolean {
        val proj = projection ?: return false
        val r = reader ?: return false
        if (virtualDisplay != null) return true
        virtualDisplay = proj.createVirtualDisplay(
            "teyvat-capture", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        return true
    }

    private fun onImage(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return
        framesSeen++
        push?.let { runCatching { it(img) } }
        synchronized(lock) {
            held?.close()
            held = img
        }
    }

    private fun <T> withLatest(block: (Image) -> T): T? = synchronized(lock) { held?.let(block) }

    override fun grab(calib: Calib, withThumb: Boolean): FrameInput? = withLatest { img ->
        val p = img.planes[0]
        FrameExtractor.extract(RgbaSource(p.buffer, p.rowStride, p.pixelStride, width, height), calib, img.timestamp, withThumb)
    }

    override fun grabRegion(x: Int, y: Int, w: Int, h: Int): Mat? = withLatest { img ->
        val x0 = x.coerceIn(0, width - 1); val y0 = y.coerceIn(0, height - 1)
        val ww = minOf(w, width - x0); val hh = minOf(h, height - y0)
        if (ww <= 0 || hh <= 0) return@withLatest null
        val p = img.planes[0]
        val src = RgbaSource(p.buffer, p.rowStride, p.pixelStride, width, height)
        val bgr = Mat(hh, ww, CvType.CV_8UC3)
        val row = ByteArray(ww * 3)
        for (yy in 0 until hh) { src.readRow(x0, y0 + yy, ww, row); bgr.put(yy, 0, row) }
        bgr
    }

    override fun grabFullBgr(): Mat? = withLatest { img ->
        val p = img.planes[0]
        val src = RgbaSource(p.buffer, p.rowStride, p.pixelStride, width, height)
        val bgr = Mat(height, width, CvType.CV_8UC3)
        val row = ByteArray(width * 3)
        for (y in 0 until height) {
            src.readRow(0, y, width, row)
            bgr.put(y, 0, row)
        }
        bgr
    }

    fun stop() {
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        synchronized(lock) { held?.close(); held = null }
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        thread?.quitSafely(); thread = null
        CaptureGate.noteReleased()
    }
}
