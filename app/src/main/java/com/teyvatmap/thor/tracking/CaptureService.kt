package com.teyvatmap.thor.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.teyvatmap.thor.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (type mediaProjection) that mirrors the *default* display (the game screen) into an
 * [ImageReader] and delivers frames to [CaptureHub] subscribers.
 *
 * In test mode (Phase 0.1 diagnostics) it also saves a full PNG every [TEST_INTERVAL_MS] to
 * `getExternalFilesDir/captures/` and publishes a thumbnail + black-frame statistics.
 * The tracking pipeline reuses it by calling [CaptureHub.subscribe] (cropped region or full frame).
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var testSubscription: AutoCloseable? = null
    private val saver = Executors.newSingleThreadExecutor()
    private val saving = AtomicBoolean(false)
    private var width = 0
    private var height = 0
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_START -> if (!started) {
                started = true
                startInForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data == null || !begin(resultCode, data, intent.getBooleanExtra(EXTRA_TEST_MODE, true))) {
                    shutdown()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Teyvat Second Screen")
            .setContentText("Capturing the game screen (read-only)")
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    private fun begin(resultCode: Int, data: Intent, testMode: Boolean): Boolean {
        return try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val dm = getSystemService(DisplayManager::class.java)
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            // Display.getRealMetrics() reflects the *process's* display adjustments: with the app on the second screen
            // it reported 1240x1080 (the app screen) and the capture came out letterboxed. The physical mode of
            // display 0 is the real game resolution.
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            val mode = display.mode
            val rotated = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
            width = if (rotated) mode.physicalHeight else mode.physicalWidth
            height = if (rotated) mode.physicalWidth else mode.physicalHeight

            val thread = HandlerThread("capture").also { it.start(); this.thread = it }
            val handler = Handler(thread.looper)

            val proj = mpm.getMediaProjection(resultCode, data) ?: error("MediaProjection unavailable")
            projection = proj
            // Android 14+: a callback must be registered before creating the virtual display.
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post { shutdown() }
                }
            }, handler)

            val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ rd -> onImage(rd) }, handler)
            val dpi = metrics.densityDpi
            // created once, when no focus/IME change of ours is in flight (see CapturePolicy)
            val t0 = SystemClock.elapsedRealtime()
            lateinit var create: Runnable
            create = Runnable {
                if (!started || reader !== r) return@Runnable
                val w = CaptureGate.waitMs(virtualDisplay != null)
                if (w > 0 && SystemClock.elapsedRealtime() - t0 < CapturePolicy.MAX_WAIT_MS) { handler.postDelayed(create, w.coerceAtLeast(50L)); return@Runnable }
                if (w == CapturePolicy.NEVER) return@Runnable
                try {
                    virtualDisplay = proj.createVirtualDisplay(
                        "teyvat-capture", width, height, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
                    )
                } catch (e: Exception) {
                    CaptureHub.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
                    shutdown()
                }
            }
            handler.post(create)
            CaptureHub.reset()
            CaptureHub.update { it.copy(running = true, width = width, height = height) }
            if (testMode) testSubscription = CaptureHub.subscribe(null, TEST_INTERVAL_MS, ::onTestFrame)
            true
        } catch (e: Exception) {
            CaptureHub.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
            false
        }
    }

    private fun onImage(rd: ImageReader) {
        val image = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return
        image.use { img ->
            CaptureHub.update { it.copy(framesSeen = it.framesSeen + 1) }
            val now = SystemClock.elapsedRealtime()
            val due = CaptureHub.subscriptions().filter { now - it.lastDispatchMs >= it.minIntervalMs }
            if (due.isEmpty()) return
            val full = toBitmap(img)
            val ts = img.timestamp
            for ((i, s) in due.withIndex()) {
                s.lastDispatchMs = now
                val bmp = try {
                    cropOrCopy(full, s.region)
                } catch (_: Exception) {
                    continue
                }
                try {
                    s.callback.onFrame(CapturedFrame(bmp, width, height, s.region, ts))
                } catch (e: Exception) {
                    bmp.recycle()
                }
            }
            full.recycle()
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    private fun cropOrCopy(src: Bitmap, region: Rect?): Bitmap {
        if (region == null) return src.copy(Bitmap.Config.ARGB_8888, false)
        val r = Rect(region).apply { intersect(0, 0, src.width, src.height) }
        return Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
    }

    /** Test-mode consumer: black-frame statistics, thumbnail, PNG every 2 s. */
    private fun onTestFrame(frame: CapturedFrame) {
        val bmp = frame.bitmap
        val black = isAllBlack(bmp)
        val thumbW = 320
        val thumb = Bitmap.createScaledBitmap(bmp, thumbW, (thumbW.toFloat() * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
        CaptureHub.setThumbnail(thumb)
        CaptureHub.update { it.copy(lastFrameBlack = black, blackFrames = it.blackFrames + if (black) 1 else 0) }
        if (saving.compareAndSet(false, true)) {
            saver.execute {
                try {
                    val dir = File(getExternalFilesDir(null) ?: filesDir, "captures").apply { mkdirs() }
                    val name = "frame_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".png"
                    val f = File(dir, name)
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    CaptureHub.update { it.copy(framesSaved = it.framesSaved + 1, lastSavedPath = f.absolutePath) }
                } catch (e: Exception) {
                    CaptureHub.update { it.copy(error = "save failed: ${e.message}") }
                } finally {
                    bmp.recycle()
                    saving.set(false)
                }
            }
        } else {
            bmp.recycle()
        }
    }

    private fun isAllBlack(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 64).coerceAtLeast(1)
        val stepY = (bmp.height / 36).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r > BLACK_THRESHOLD || g > BLACK_THRESHOLD || b > BLACK_THRESHOLD) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }

    private fun shutdown() {
        testSubscription?.close(); testSubscription = null
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { reader?.setOnImageAvailableListener(null, null); reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        thread?.quitSafely(); thread = null
        CaptureGate.noteReleased()
        CaptureHub.update { it.copy(running = false) }
        started = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (started) shutdown()
        saver.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.teyvatmap.thor.capture.START"
        const val ACTION_STOP = "com.teyvatmap.thor.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_TEST_MODE = "test_mode"
        const val TEST_INTERVAL_MS = 2000L
        private const val BLACK_THRESHOLD = 6
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 4102

        /** Starts capture with the result of the MediaProjection consent dialog. */
        fun start(context: Context, resultCode: Int, data: Intent, testMode: Boolean = true) {
            val i = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_TEST_MODE, testMode)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
