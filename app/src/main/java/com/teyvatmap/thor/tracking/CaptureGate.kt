package com.teyvatmap.thor.tracking

import android.os.SystemClock
import java.util.Collections

/**
 * When may a VirtualDisplay be created? (pure logic, unit-tested)
 *
 * AYN's firmware can deadlock system_server: `createVirtualDisplay` takes the display-manager SyncRoot and then the
 * DisplayManagerGlobal lock, while an input-method / focus change on the second display takes them in the opposite
 * order. So a VirtualDisplay is only created from the service, once the consent activity is gone, no window of ours
 * changed focus or IME state for [SETTLE_MS], no text field of ours is active, and the previous projection was
 * released at least [COOLDOWN_MS] ago. A VirtualDisplay is created at most once per projection (see [ScreenGrabber]).
 */
object CapturePolicy {
    const val SETTLE_MS = 1_500L
    const val COOLDOWN_MS = 5_000L
    const val POLL_MS = 250L
    /** Gives up waiting after this long (creating late is better than never). */
    const val MAX_WAIT_MS = 20_000L

    data class Inputs(
        val nowMs: Long,
        val consentActive: Boolean,
        val typing: Boolean,
        val lastWindowChangeMs: Long,
        val lastReleaseMs: Long,
        /** True when this projection already has a VirtualDisplay (never create a second one). */
        val displayExists: Boolean = false,
    )

    /** Milliseconds to wait before creating the display; 0 = create now, [NEVER] = do not create (one exists). */
    fun waitMs(i: Inputs): Long {
        if (i.displayExists) return NEVER
        if (i.consentActive || i.typing) return POLL_MS
        val settle = i.lastWindowChangeMs + SETTLE_MS - i.nowMs
        val cool = i.lastReleaseMs + COOLDOWN_MS - i.nowMs
        return maxOf(0L, settle, cool)
    }

    const val NEVER = -1L
}

/** Process-wide record of our own focus/IME activity, read by [CapturePolicy]. */
object CaptureGate {
    private val typers = Collections.synchronizedSet(HashSet<Any>())
    @Volatile private var consentActive = false
    @Volatile private var lastWindowChangeMs = SystemClock.elapsedRealtime()
    @Volatile private var lastReleaseMs = 0L

    fun noteWindowChange() { lastWindowChangeMs = SystemClock.elapsedRealtime() }
    fun setConsentActive(v: Boolean) { consentActive = v; noteWindowChange() }
    fun setTyping(owner: Any, v: Boolean) {
        if (v) typers.add(owner) else typers.remove(owner)
        noteWindowChange()
    }
    /** Called when a VirtualDisplay / projection was released. */
    fun noteReleased() { lastReleaseMs = SystemClock.elapsedRealtime() }

    fun waitMs(displayExists: Boolean = false): Long = CapturePolicy.waitMs(
        CapturePolicy.Inputs(SystemClock.elapsedRealtime(), consentActive, typers.isNotEmpty(), lastWindowChangeMs, lastReleaseMs, displayExists),
    )
}
