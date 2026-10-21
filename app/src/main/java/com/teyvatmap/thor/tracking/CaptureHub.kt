package com.teyvatmap.thor.tracking

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A captured frame handed to a [FrameCallback]. [bitmap] is a private copy owned by the callback (recycle it when
 * done). If a [region] was requested it is the cropped region, otherwise the full frame; [fullWidth]/[fullHeight]
 * describe the whole captured display either way.
 */
class CapturedFrame(
    val bitmap: Bitmap,
    val fullWidth: Int,
    val fullHeight: Int,
    val region: Rect?,
    val timestampNanos: Long,
)

/** Receives frames on the capture thread. Keep the work short or hand off to another dispatcher. */
fun interface FrameCallback {
    fun onFrame(frame: CapturedFrame)
}

data class CaptureStatus(
    val running: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val framesSeen: Long = 0,
    val framesSaved: Int = 0,
    val blackFrames: Int = 0,
    val lastFrameBlack: Boolean? = null,
    val lastSavedPath: String? = null,
    val error: String? = null,
)

/**
 * Process-wide rendezvous between [CaptureService] (producer) and consumers: the diagnostics screen now, the
 * tracking pipeline later (PLAN §6).
 */
object CaptureHub {
    internal class Subscription(
        val region: Rect?,
        val minIntervalMs: Long,
        val callback: FrameCallback,
    ) {
        @Volatile var lastDispatchMs = 0L
    }

    private val subs = CopyOnWriteArrayList<Subscription>()
    private val _status = MutableStateFlow(CaptureStatus())
    private val _thumbnail = MutableStateFlow<Bitmap?>(null)

    /** The active pull-style source (set by [TrackingService]); null when nothing is capturing. */
    @Volatile var source: FrameSource? = null

    val status: StateFlow<CaptureStatus> = _status.asStateFlow()
    val thumbnail: StateFlow<Bitmap?> = _thumbnail.asStateFlow()

    /**
     * Subscribe to frames. [region] (in full-display pixels) crops each frame before delivery; null delivers the
     * full frame. At most one frame per [minIntervalMs] is delivered. Close the returned handle to unsubscribe.
     */
    fun subscribe(region: Rect? = null, minIntervalMs: Long = 250, callback: FrameCallback): AutoCloseable {
        val s = Subscription(region, minIntervalMs, callback)
        subs += s
        return AutoCloseable { subs -= s }
    }

    internal fun subscriptions(): List<Subscription> = subs

    internal fun update(block: (CaptureStatus) -> CaptureStatus) = _status.update(block)
    internal fun setThumbnail(b: Bitmap?) { _thumbnail.value = b }
    internal fun reset() { _status.value = CaptureStatus(); _thumbnail.value = null }
}
