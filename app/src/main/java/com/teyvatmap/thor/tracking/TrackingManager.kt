package com.teyvatmap.thor.tracking

import android.content.Context
import android.content.Intent
import android.util.Log
import com.teyvatmap.thor.tracking.cv.Calib
import com.teyvatmap.thor.tracking.cv.TrackerParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class TrackingStatus(
    /** The screen capture session is up (calibration can grab frames). */
    val capturing: Boolean = false,
    /** The tracker thread is running. */
    val tracking: Boolean = false,
    val paused: Boolean = false,
    /** Why tracking is not running although capture is (no calibration yet, matching data missing ...). */
    val message: String? = null,
)

/**
 * Process-wide handle of the tracking feature: the published [pose], the service/engine status, the debug snapshot and
 * the commands the UI sends (start with the consent result, pause, stop, save a frame). The work itself happens in
 * [TrackingService] / [TrackingEngine].
 */
@Singleton
class TrackingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val store: TrackingConfigStore,
) {
    private val _pose = MutableStateFlow(PlayerPose())
    /** `StateFlow<PlayerPose>` of PLAN §6.3: where the player is, how sure, in which state and layer. */
    val pose: StateFlow<PlayerPose> = _pose.asStateFlow()

    private val _status = MutableStateFlow(TrackingStatus())
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private val _debug = MutableStateFlow<DebugSnapshot?>(null)
    val debug: StateFlow<DebugSnapshot?> = _debug.asStateFlow()

    @Volatile internal var engine: TrackingEngine? = null

    /** True while the debug view is open: the tracker then produces images and timings for every check. */
    var debugEnabled: Boolean = false
        set(v) {
            field = v
            engine?.debugEnabled = v
            if (!v) _debug.value = null
        }

    // ------------------------------------------------------------------------------- commands
    fun start(resultCode: Int, data: Intent) = TrackingService.start(context, resultCode, data)
    fun stop() = TrackingService.stop(context)
    fun togglePause() = TrackingService.togglePause(context)

    /** Forces a fresh relocation (drops the lock; the search starts at the last position, then all maps). */
    fun retrack() { engine?.retrack() }

    fun saveParams(p: TrackerParams) {
        store.saveParams(p)
        engine?.setParams(p)
    }

    fun saveCalib(c: Calib) {
        store.saveCalib(c)
        TrackingService.reload(context)
    }

    /**
     * Saves the newest full frame (+ calibration and current pose as JSON) to `getExternalFilesDir/dataset/` for
     * regression tests. This is an explicit user action: the tick path never keeps more than the minimap region.
     */
    fun saveFrame(): String? {
        val src = CaptureHub.source ?: return null
        val frame = src.grabFullBgr() ?: return null
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "dataset").also { it.mkdirs() }
        val name = "frame_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ok = Imgcodecs.imwrite(File(dir, "$name.png").path, frame)
        frame.release()
        if (!ok) return null
        val p = _pose.value
        File(dir, "$name.json").writeText(
            """{"state":"${p.state}","mapId":${p.mapId},"x_pos":${p.xPos},"y_pos":${p.yPos},"floorId":${p.floorId},"heading":${p.headingDeg},"calib":${store.calib.value?.let { com.teyvatmap.thor.tracking.cv.TrackerParams.JSON.encodeToString(Calib.serializer(), it) }}}""",
        )
        Log.i("TrackingManager", "saved dataset frame ${File(dir, "$name.png")}")
        return File(dir, "$name.png").path
    }

    // --------------------------------------------------------------------- called by the service
    internal fun publish(p: PlayerPose) { _pose.value = p }
    internal fun publishDebug(d: DebugSnapshot) { _debug.value = d }
    internal fun setStatus(block: (TrackingStatus) -> TrackingStatus) = _status.update(block)
    internal fun reset() {
        engine = null
        _status.value = TrackingStatus()
        _pose.value = _pose.value.copy(state = com.teyvatmap.thor.tracking.cv.TrackState.OFF)
        _debug.value = null
    }
}
