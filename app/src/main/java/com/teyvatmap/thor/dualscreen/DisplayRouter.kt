package com.teyvatmap.thor.dualscreen

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Finds the secondary (bottom) display and opens the app there (PLAN §8). Genshin keeps running on the default
 * display (id 0) while this app lives on the other screen.
 */
object DisplayRouter {
    const val EXTRA_ROUTED = "com.teyvatmap.thor.EXTRA_ROUTED"

    fun displays(context: Context): List<Display> =
        context.getSystemService(DisplayManager::class.java).displays.filter { it.isValid }.toList()

    /** Non-default, powered, non-private displays (excludes virtual capture displays), lowest id first. */
    fun secondaryDisplays(context: Context): List<Display> =
        displays(context).filter {
            it.displayId != Display.DEFAULT_DISPLAY &&
                it.state != Display.STATE_OFF &&
                (it.flags and Display.FLAG_PRIVATE) == 0
        }.sortedBy { it.displayId }

    fun currentDisplayId(activity: Activity): Int = activity.display?.displayId ?: Display.DEFAULT_DISPLAY

    fun isOnDefaultDisplay(activity: Activity): Boolean = currentDisplayId(activity) == Display.DEFAULT_DISPLAY

    /**
     * Called from `onCreate`: if we were started on the default display and a secondary exists, relaunch there.
     * Returns true if the caller should finish immediately.
     */
    fun routeOnLaunch(activity: Activity, enabled: Boolean): Boolean {
        if (!enabled) return false
        if (activity.intent?.getBooleanExtra(EXTRA_ROUTED, false) == true) return false
        if (!isOnDefaultDisplay(activity)) return false
        val target = secondaryDisplays(activity).firstOrNull() ?: return false
        return launchOn(activity, target.displayId)
    }

    /** The display "Move to other screen" would go to: the first secondary, or the default one when on a secondary. */
    fun otherDisplayId(activity: Activity): Int? {
        val cur = currentDisplayId(activity)
        return if (cur == Display.DEFAULT_DISPLAY) secondaryDisplays(activity).firstOrNull()?.displayId
        else Display.DEFAULT_DISPLAY
    }

    /** "Move to other screen" action. Returns false if there is nowhere to go. */
    fun moveToOtherScreen(activity: Activity): Boolean {
        val target = otherDisplayId(activity) ?: return false
        return launchOn(activity, target)
    }

    private fun launchOn(activity: Activity, displayId: Int): Boolean {
        val intent = Intent(activity, activity.javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(EXTRA_ROUTED, true)
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        return try {
            activity.startActivity(intent, options.toBundle())
            activity.finish()
            true
        } catch (e: Exception) {
            false
        }
    }
}
