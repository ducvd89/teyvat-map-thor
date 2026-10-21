package com.teyvatmap.thor.ui.calibration

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teyvatmap.thor.data.db.AppDatabase
import com.teyvatmap.thor.data.store.TileStore
import com.teyvatmap.thor.tracking.CaptureHub
import com.teyvatmap.thor.tracking.CvIndexManager
import com.teyvatmap.thor.tracking.TrackingManager
import com.teyvatmap.thor.tracking.TrackingStatus
import com.teyvatmap.thor.tracking.cv.Calib
import com.teyvatmap.thor.tracking.cv.CalibrationTools
import com.teyvatmap.thor.tracking.cv.Cv
import com.teyvatmap.thor.tracking.cv.FrameContext
import com.teyvatmap.thor.tracking.cv.FrameExtractor
import com.teyvatmap.thor.tracking.cv.Matcher
import com.teyvatmap.thor.tracking.cv.MatSource
import com.teyvatmap.thor.tracking.cv.P2
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import javax.inject.Inject

data class CalibrationUi(
    val frame: Bitmap? = null,
    val frameW: Int = 1920,
    val frameH: Int = 1080,
    val cx: Double = Calib().cx,
    val cy: Double = Calib().cy,
    val r: Double = Calib().r,
    val detected: Boolean = false,
    val hint: P2? = null,           // zoom-0 px picked on the map
    val busy: String? = null,
    val scale: Double? = null,
    val score: Double? = null,
    val matchPos: P2? = null,
    val message: String? = null,
    val saved: Boolean = false,
)

/** State of the calibration screen (PLAN §6.5): captured frame, circle handles, scale estimate, saving. */
@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val tracking: TrackingManager,
    private val cvIndex: CvIndexManager,
    private val db: AppDatabase,
    private val tiles: TileStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(CalibrationUi())
    val ui: StateFlow<CalibrationUi> = _ui.asStateFlow()
    val status: StateFlow<TrackingStatus> = tracking.status
    val saved: StateFlow<Calib?> = tracking.store.calib

    private var frameBgr: Mat? = null

    private val _mapBitmap = MutableStateFlow<Bitmap?>(null)
    /** Whole-world picture (zoom -3 tiles) for the optional "tap your position" hint. */
    val mapBitmap: StateFlow<Bitmap?> = _mapBitmap.asStateFlow()
    private var mapGeo: Triple<Int, Int, Int>? = null // originX, originY, scale unused

    init {
        tracking.store.calib.value?.let { c -> _ui.update { it.copy(cx = c.cx, cy = c.cy, r = c.r, scale = c.s) } }
    }

    fun captureFrame() {
        viewModelScope.launch(Dispatchers.Default) {
            _ui.update { it.copy(busy = "Capturing", message = null, saved = false) }
            val src = CaptureHub.source
            val bgr = src?.grabFullBgr()
            if (bgr == null) {
                _ui.update { it.copy(busy = null, message = "No frame yet: start the capture first (the game must be on the top screen).") }
                return@launch
            }
            frameBgr?.release()
            frameBgr = bgr
            val rgb = Mat()
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB)
            val bmp = Bitmap.createBitmap(bgr.cols(), bgr.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgb, bmp)
            rgb.release()
            val c = CalibrationTools.findMinimapCircle(bgr)
            _ui.update {
                it.copy(
                    frame = bmp, frameW = bgr.cols(), frameH = bgr.rows(), busy = null,
                    cx = c?.first ?: it.cx, cy = c?.second ?: it.cy, r = c?.third ?: it.r, detected = c != null,
                    scale = null, score = null, matchPos = null,
                    message = if (c != null) "Minimap circle found. Drag to fine-tune, then estimate the scale." else "No circle found: place the handles by hand.",
                )
            }
        }
    }

    fun detectAgain() {
        val bgr = frameBgr ?: return
        val c = CalibrationTools.findMinimapCircle(bgr) ?: return _ui.update { it.copy(message = "No circle found") }
        _ui.update { it.copy(cx = c.first, cy = c.second, r = c.third, detected = true) }
    }

    fun setCircle(cx: Double, cy: Double, r: Double) {
        _ui.update { it.copy(cx = cx, cy = cy, r = r.coerceIn(40.0, 200.0), scale = null, score = null, saved = false) }
    }

    fun nudge(dx: Double, dy: Double, dr: Double) = _ui.value.let { setCircle(it.cx + dx, it.cy + dy, it.r + dr) }

    fun setHint(px0: Double, py0: Double) = _ui.update { it.copy(hint = P2(px0, py0)) }
    fun clearHint() = _ui.update { it.copy(hint = null) }

    /** Loads the zoom -3 tiles into one bitmap (once). */
    fun loadMap() {
        if (_mapBitmap.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val m = db.maps().get(TRACK_MAP) ?: return@launch
            val z = m.minZoom
            val f = Math.pow(2.0, z.toDouble())
            val w = Math.ceil(m.totalW * f).toInt()
            val h = Math.ceil(m.totalH * f).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val cv = Canvas(bmp)
            cv.drawColor(Color.rgb(16, 23, 31))
            val opts = BitmapFactory.Options()
            for (ty in 0 until Math.ceil(h / 256.0).toInt()) for (tx in 0 until Math.ceil(w / 256.0).toInt()) {
                val s = tiles.open(m.id, m.activeVersion, z, tx, ty) ?: continue
                val t = s.use { BitmapFactory.decodeStream(it, null, opts) } ?: continue
                cv.drawBitmap(t, tx * 256f, ty * 256f, Paint())
                t.recycle()
            }
            mapGeo = Triple(m.originX, m.originY, z)
            _mapBitmap.value = bmp
        }
    }

    /** Picture pixel -> zoom-0 px. */
    fun mapPixelToZ0(u: Float, v: Float): P2 {
        val z = mapGeo?.third ?: -3
        val f = Math.pow(2.0, z.toDouble())
        return P2(u / f, v / f)
    }

    fun z0ToMapPixel(p: P2): Pair<Float, Float> {
        val z = mapGeo?.third ?: -3
        val f = Math.pow(2.0, z.toDouble())
        return (p.x * f).toFloat() to (p.y * f).toFloat()
    }

    fun estimateScale() {
        val bgr = frameBgr ?: return
        val u = _ui.value
        viewModelScope.launch(Dispatchers.Default) {
            _ui.update { it.copy(busy = "Estimating the scale ...", message = null, saved = false) }
            val t0 = SystemClock.elapsedRealtime()
            try {
                Cv.init()
                val map = db.maps().get(TRACK_MAP)
                val index = map?.let { cvIndex.open(TRACK_MAP, it.activeVersion) }
                if (index == null) {
                    _ui.update { it.copy(busy = null, message = "The matching data is not built yet (Settings > Position matching data).") }
                    return@launch
                }
                val calib = Calib(u.cx, u.cy, u.r, 0.8, null, bgr.cols(), bgr.rows())
                val fi = FrameExtractor.extract(MatSource(bgr), calib)
                val params = tracking.store.params.value
                val matcher = Matcher(index, params)
                val ctx = FrameContext.create(fi, calib, params, index.meta.sigma, index.meta.lcnC)
                val hint = u.hint
                val est = matcher.estimateScale(ctx, hint = hint, hintRadiusZ0 = 400.0) { p -> _ui.update { it.copy(busy = "Estimating the scale: $p") } }
                ctx.close(); matcher.close(); index.close()
                val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
                if (est == null || !est.match.ok) {
                    _ui.update { it.copy(busy = null, message = "No scale found (${"%.1f".format(sec)} s). Stand in a textured area (not sea) or tap your position on the map.") }
                } else {
                    val good = est.match.score >= 0.5
                    _ui.update {
                        it.copy(
                            busy = null, scale = est.s, score = est.match.score, matchPos = P2(est.match.px0, est.match.py0),
                            message = "Scale %.3f, match score %.2f (%.1f s)".format(est.s, est.match.score, sec) +
                                if (good) "" else ": low, check the circle or tap your position and retry.",
                        )
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, message = "Estimation failed: ${e.message}") }
            }
        }
    }

    fun saveProfile() {
        val bgr = frameBgr ?: return
        val u = _ui.value
        val s = u.scale ?: return
        viewModelScope.launch(Dispatchers.Default) {
            Cv.init()
            // no ring reference any more: the ring test is a local-contrast statistic (real_report.md), nothing to calibrate
            val base = Calib(u.cx, u.cy, u.r, s, null, bgr.cols(), bgr.rows())
            withContext(Dispatchers.Main) { tracking.saveCalib(base) }
            _ui.update { it.copy(saved = true, message = "Profile saved (${bgr.cols()}x${bgr.rows()}, circle %.0f,%.0f r=%.0f, scale %.3f).".format(u.cx, u.cy, u.r, s)) }
        }
    }

    /** The measured 1080p profile of the real Thor (circle 210.5, 156.1, r 133.4; base scale 0.752): no capture needed. */
    fun useThorDefaults() {
        val d = Calib()
        tracking.saveCalib(d)
        _ui.update { it.copy(cx = d.cx, cy = d.cy, r = d.r, scale = d.s, saved = true, message = "Thor 1080p defaults saved (circle %.1f, %.1f r=%.1f, base scale %.3f). The minimap zoom per region comes from the region table.".format(d.cx, d.cy, d.r, d.s)) }
    }

    override fun onCleared() {
        frameBgr?.release(); frameBgr = null
        _mapBitmap.value = null
    }

    private companion object { const val TRACK_MAP = 2 }
}
