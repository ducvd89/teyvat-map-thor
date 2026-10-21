package com.teyvatmap.thor.debug

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug-only stand-in for the game (launch it on display 0):
 * `adb shell am start --display 0 --activity-clear-task -n com.teyvatmap.thor/.debug.FakeGameActivity -e scenario world --ez loop true`
 * (extras: `--ed from <sec>`, `--ez hold true` to freeze on one frame, `--ed speed 2.0`).
 *
 * Plays an exported fixture scenario (`tools/cv/export_fixtures.py`, pushed to `getExternalFilesDir/fake/<name>/`)
 * full-screen with its original timing. Only the minimap ROI and a 64x36 thumbnail of every frame exist, so the full
 * frame is rebuilt: the thumbnail scaled up as the scene, the ROI pasted at its position. That is exactly what the
 * tracker reads (ring, minimap crop, loading-screen thumbnail), so the real MediaProjection pipeline can be exercised
 * on the emulator without the game.
 */
class FakeGameActivity : Activity() {
    private class Fr(val t: Double, val roi: Bitmap?, val thumb: Bitmap?, val x: Int, val y: Int, val label: String, val fullPath: String? = null)

    private lateinit var view: GameView
    private val handler = Handler(Looper.getMainLooper())
    private var frames: List<Fr> = emptyList()
    private var idx = 0
    private var t0 = 0L
    private var loop = false
    private var speed = 1.0
    private var hold = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // draw behind the status / navigation bars like a real full-screen game does
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        view = GameView(this)
        setContentView(view)
        window.insetsController?.let {
            it.hide(android.view.WindowInsets.Type.systemBars())
            it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val name = intent.getStringExtra("scenario") ?: "world"
        loop = intent.getBooleanExtra("loop", false)
        speed = intent.getDoubleExtra("speed", 1.0)
        hold = intent.getBooleanExtra("hold", false)   // show the frame at "from" and stay on it (calibration)
        frames = load(this, name)
        Log.i(TAG, "scenario $name: ${frames.size} frames, display ${display?.displayId}")
        if (frames.isEmpty()) return
        start(intent.getDoubleExtra("from", 0.0))
    }

    private fun start(from: Double) {
        idx = frames.indexOfLast { it.t <= from }.coerceAtLeast(0)
        t0 = SystemClock.elapsedRealtime() - (from * 1000 / speed).toLong()
        show()
    }

    private fun show() {
        val f = frames[idx]
        view.frame = f
        // a full-frame scenario (real frames, UID blanked): decode the one frame on show, never all of them at once
        view.full = f.fullPath?.let { BitmapFactory.decodeFile(it) }
        view.invalidate()
        Log.i(TAG, "frame ${f.label} t=${f.t}")
        if (hold) return
        val next = idx + 1
        if (next >= frames.size) {
            if (loop) handler.postDelayed({ start(0.0) }, 2000) else Log.i(TAG, "scenario finished")
            return
        }
        val due = t0 + (frames[next].t * 1000 / speed).toLong()
        idx = next
        handler.postAtTime({ show() }, android.os.SystemClock.uptimeMillis() + (due - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // Stand-in for the game's controller input: every key / gamepad button that reaches this window is logged
    // (`adb logcat -s FakeGame:I`), so the focus behaviour of the map window on the other display can be verified.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) keysReceived++
        Log.i(TAG, "KEY ${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"} ${KeyEvent.keyCodeToString(event.keyCode)} dev=${event.deviceId} display=${display?.displayId} total=$keysReceived")
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(TAG, "window focus = $hasFocus")
    }

    private var keysReceived = 0

    private class GameView(ctx: Context) : View(ctx) {
        var frame: Fr? = null
        var full: Bitmap? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dst = Rect()

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.BLACK)
            val f = frame ?: return
            dst.set(0, 0, width, height)
            full?.let { c.drawBitmap(it, null, dst, paint); return }
            val thumb = f.thumb ?: return
            val roi = f.roi ?: return
            c.drawBitmap(thumb, null, dst, paint)
            val k = width / 1920f
            c.drawBitmap(roi, null, Rect((f.x * k).toInt(), (f.y * k).toInt(), ((f.x + roi.width) * k).toInt(), ((f.y + roi.height) * k).toInt()), null)
        }
    }

    companion object {
        const val TAG = "FakeGame"

        private fun load(ctx: Context, name: String): List<Fr> {
            val dir = File(ctx.getExternalFilesDir("fake"), "scenarios/$name")
            val idx = File(dir, "index.json")
            if (!idx.isFile) { Log.w(TAG, "missing $idx"); return emptyList() }
            val j = JSONObject(idx.readText())
            val roi = j.getJSONArray("roi")
            val arr: JSONArray = j.getJSONArray("frames")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                if (o.has("full")) {
                    Fr(o.getDouble("t"), null, null, 0, 0, "${o.optString("expected")}/${o.optString("kind")}", File(ctx.getExternalFilesDir("fake"), o.getString("full")).path)
                } else {
                    val roiBmp = BitmapFactory.decodeFile(File(ctx.getExternalFilesDir("fake"), o.getString("file")).path)
                    val thumb = BitmapFactory.decodeFile(File(ctx.getExternalFilesDir("fake"), o.getString("thumb")).path)
                    Fr(o.getDouble("t"), roiBmp, thumb, roi.getInt(0), roi.getInt(1), "${o.getString("expected")}/${o.getString("kind")}")
                }
            }
        }
    }
}
