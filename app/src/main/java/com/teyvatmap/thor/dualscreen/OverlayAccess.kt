package com.teyvatmap.thor.dualscreen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** What the Activity UI needs to offer the bottom-screen overlay ("game mode"). */
data class OverlayControls(
    /** A second display exists. */
    val available: Boolean,
    /** "Display over other apps" is granted. */
    val granted: Boolean,
    /** Start the overlay on the bottom screen (or, when not [granted], show the permission explanation). */
    val start: () -> Unit,
    /** Open the system page where the permission is granted. */
    val requestPermission: () -> Unit,
)

object OverlayAccess {
    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    const val EXPLANATION =
        "Game mode shows the map on the bottom screen as an overlay window, so touching it never takes the controller " +
            "away from the game on the top screen. Android only allows that with the \"Display over other apps\" permission. " +
            "Open the settings page, switch it on for Teyvat Second Screen, then come back and tap Start on bottom screen again."
}
