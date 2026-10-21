package com.teyvatmap.thor

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.teyvatmap.thor.data.prefs.SettingsRepository
import com.teyvatmap.thor.dualscreen.DisplayRouter
import com.teyvatmap.thor.dualscreen.MapOverlayService
import com.teyvatmap.thor.dualscreen.NonFocusableWindow
import com.teyvatmap.thor.dualscreen.OverlayAccess
import com.teyvatmap.thor.dualscreen.OverlayControls
import com.teyvatmap.thor.ui.AppViewModel
import com.teyvatmap.thor.ui.InputEventLog
import com.teyvatmap.thor.ui.TeyvatApp
import com.teyvatmap.thor.ui.theme.TeyvatTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepo: SettingsRepository
    private val vm: AppViewModel by viewModels()
    private var overlayGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = runBlocking { settingsRepo.current() }

        // Game mode: with the overlay permission and a second screen, the map goes to an overlay window on the bottom
        // screen instead (an Activity there makes the app the focused application and breaks the game's controller input).
        val hasSecondScreen = DisplayRouter.secondaryDisplays(this).isNotEmpty()
        if (savedInstanceState == null && hasSecondScreen && OverlayAccess.canDraw(this) && !MapOverlayService.running.value &&
            (intent.getBooleanExtra(EXTRA_START_OVERLAY, false) ||
                (initial.autoStartOverlay && !intent.getBooleanExtra(EXTRA_NO_AUTOSTART, false)))
        ) {
            MapOverlayService.start(this)
            finishAndRemoveTask()
            return
        }

        // Opened on the game screen with a second display available: relaunch on the second display.
        if (savedInstanceState == null && DisplayRouter.routeOnLaunch(this, initial.autoMoveToSecondary)) {
            return
        }

        // Non-focusable only as the fallback when the overlay permission is missing; with it this window is the
        // management UI (settings, import, consent) and game mode lives in the overlay.
        NonFocusableWindow.apply(window, initial.nonFocusable && !OverlayAccess.canDraw(this), initial.keepScreenOn)
        enableEdgeToEdge()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(vm.settings, vm.searchFocused) { s, searching -> s to searching }.collect { (s, searching) ->
                    // The IME needs a focusable window: drop the flag only while the search field is active.
                    NonFocusableWindow.apply(window, s.nonFocusable && !searching && !OverlayAccess.canDraw(this@MainActivity), s.keepScreenOn)
                }
            }
        }

        setContent {
            TeyvatTheme {
                TeyvatApp(
                    vm = vm,
                    canMoveToOtherScreen = { DisplayRouter.otherDisplayId(this) != null },
                    onMoveToOtherScreen = { DisplayRouter.moveToOtherScreen(this) },
                    overlay = OverlayControls(
                        available = hasSecondScreen,
                        granted = overlayGranted,
                        start = { MapOverlayService.start(this); finishAndRemoveTask() },
                        requestPermission = { startActivity(OverlayAccess.permissionIntent(this)) },
                    ),
                    initialScreen = intent.getStringExtra(EXTRA_OPEN_SCREEN),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = OverlayAccess.canDraw(this)    // the user may just have granted it in system settings
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        InputEventLog.key(event)
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        InputEventLog.motion(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        InputEventLog.touch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        InputEventLog.setWindowFocus(hasFocus)
        com.teyvatmap.thor.tracking.CaptureGate.noteWindowChange()
    }

    companion object {
        /** Do not auto-start the overlay for this launch (set when returning from the overlay's "Open app"). */
        const val EXTRA_NO_AUTOSTART = "com.teyvatmap.thor.EXTRA_NO_AUTOSTART"
        /** Start game mode (the bottom-screen overlay) right away, e.g. from adb. */
        const val EXTRA_START_OVERLAY = "com.teyvatmap.thor.EXTRA_START_OVERLAY"
        /** Name of the in-app page to open first (SETTINGS, DIAGNOSTICS, CALIBRATION, DEBUG ...). */
        const val EXTRA_OPEN_SCREEN = "com.teyvatmap.thor.EXTRA_OPEN_SCREEN"
    }
}
