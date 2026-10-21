package com.teyvatmap.thor.dualscreen

import android.view.Window
import android.view.WindowManager

/**
 * Window policy for the map window (PLAN §8).
 *
 * With `FLAG_NOT_FOCUSABLE` the window still receives touches, but key / gamepad events keep going to the
 * focused window on the other display (Genshin). The flag has to be dropped while the search field is being typed
 * in, because a non-focusable window cannot host the IME. Note that this also means no `Dialog` / `Popup` windows
 * may be used in the UI: they are separate, focusable windows.
 */
object NonFocusableWindow {
    /** Flag bits for an overlay window's [WindowManager.LayoutParams] (the overlay is always at least [WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN]). */
    fun overlayFlags(notFocusable: Boolean, keepScreenOn: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (notFocusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (keepScreenOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        return flags
    }

    fun apply(window: Window, notFocusable: Boolean, keepScreenOn: Boolean) {
        val mask = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        var flags = 0
        if (notFocusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (keepScreenOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        window.setFlags(flags, mask)
    }
}
