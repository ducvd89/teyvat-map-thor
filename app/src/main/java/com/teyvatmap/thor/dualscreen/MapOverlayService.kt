package com.teyvatmap.thor.dualscreen

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.teyvatmap.thor.MainActivity
import com.teyvatmap.thor.R
import com.teyvatmap.thor.data.prefs.SettingsRepository
import com.teyvatmap.thor.ui.AppViewModel
import com.teyvatmap.thor.ui.map.MapViewModel
import com.teyvatmap.thor.ui.overlay.OverlayApp
import com.teyvatmap.thor.ui.theme.TeyvatTheme
import com.teyvatmap.thor.ui.tracking.CaptureConsentActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Game mode" (PLAN §8): hosts the map UI in a full-display `TYPE_APPLICATION_OVERLAY` window on the secondary display.
 *
 * Why not an Activity: an Activity window with FLAG_NOT_FOCUSABLE still makes its app the *focused application* of
 * that display when it is touched, and the input dispatcher then waits for a focused window that never comes ("Application
 * does not have a focused window" ANR) and drops the controller keys meant for the game. An overlay window has no
 * ActivityRecord, so touching it never changes the focused app. The window is non-focusable except while a text field
 * is being typed in (the IME needs a focusable window).
 */
@AndroidEntryPoint
class MapOverlayService : Service() {
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var vmFactory: OverlayViewModelFactory

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var owner: OverlayOwner? = null
    private var root: OverlayRoot? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var displayId = Display.DEFAULT_DISPLAY
    private var typing = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayChanged(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) { if (id == displayId) stopSelf() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_OPEN_APP -> openApp(null)
            else -> {
                startInForeground()
                if (root == null) {
                    val id = intent?.getIntExtra(EXTRA_DISPLAY, -1)?.takeIf { it >= 0 }
                        ?: DisplayRouter.secondaryDisplays(this).firstOrNull()?.displayId
                    if (id == null || !OverlayAccess.canDraw(this)) {
                        Log.w(TAG, "cannot show the overlay: display=$id permission=${OverlayAccess.canDraw(this)}")
                        stopSelf()
                    } else {
                        show(id)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------------------------------ window
    private fun show(id: Int) {
        val display = getSystemService(DisplayManager::class.java).getDisplay(id) ?: return stopSelf()
        displayId = id
        val windowContext = createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val themed = ContextThemeWrapper(windowContext, R.style.Theme_TeyvatSecondScreen)
        val manager = windowContext.getSystemService(WindowManager::class.java)

        val o = OverlayOwner().also { it.create() }
        val provider = ViewModelProvider(o.viewModelStore, vmFactory)
        val mapVm = provider[MapViewModel::class.java]
        val appVm = provider[AppViewModel::class.java]

        val view = OverlayRoot(themed) { o.onBackPressed() }
        view.setViewTreeLifecycleOwner(o)
        view.setViewTreeSavedStateRegistryOwner(o)
        view.setViewTreeViewModelStoreOwner(o)
        view.setViewTreeOnBackPressedDispatcherOwner(o)
        view.addView(
            ComposeView(themed).apply {
                setContent {
                    CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides o) {
                        TeyvatTheme {
                            OverlayApp(
                                appVm = appVm, mapVm = mapVm,
                                onOpenApp = { screen -> openApp(screen) },
                                onStartCapture = { startCaptureConsent() },
                            )
                        }
                    }
                }
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            NonFocusableWindow.overlayFlags(true, false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "TeyvatMapOverlay"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        manager.addView(view, lp)
        o.resume()

        owner = o; root = view; wm = manager; params = lp
        debugRoot = view
        _running.value = true
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)

        // The IME needs a focusable window: drop FLAG_NOT_FOCUSABLE only while a text field is being typed in.
        view.viewTreeObserver.addOnWindowFocusChangeListener { has ->
            if (has && typing) view.windowInsetsController?.show(WindowInsets.Type.ime())
        }
        scope.launch {
            combine(settingsRepo.settings, appVm.searchFocused) { s, t -> s to t }.collect { (s, t) ->
                val wasTyping = typing
                typing = t
                lp.flags = NonFocusableWindow.overlayFlags(s.nonFocusable && !t, s.keepScreenOn)
                if (view.isAttachedToWindow) manager.updateViewLayout(view, lp)
                if (t && !wasTyping && view.hasWindowFocus()) view.windowInsetsController?.show(WindowInsets.Type.ime())
                if (!t && wasTyping) view.windowInsetsController?.hide(WindowInsets.Type.ime())
            }
        }
    }

    private fun hide() {
        val v = root ?: return
        runCatching { wm?.removeView(v) }
        owner?.destroy()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        root = null; owner = null; wm = null; params = null
        if (debugRoot === v) debugRoot = null
        _running.value = false
    }

    override fun onDestroy() {
        hide()
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------------------------------- hand-offs
    /** Back to the normal app window on the same display (the overlay goes away; "Start on bottom screen" brings it back). */
    private fun openApp(screen: String?) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(DisplayRouter.EXTRA_ROUTED, true)
            .putExtra(MainActivity.EXTRA_NO_AUTOSTART, true)
            .putExtra(MainActivity.EXTRA_OPEN_SCREEN, screen)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        try {
            startActivity(intent, options.toBundle())
            stopSelf()
        } catch (e: Exception) {
            Log.w(TAG, "could not open the app: $e")
        }
    }

    private fun startCaptureConsent() {
        val intent = Intent(this, CaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()) }
            .onFailure { Log.w(TAG, "could not start the capture consent: $it") }
    }

    // ------------------------------------------------------------------------------- notification
    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Bottom-screen map", NotificationManager.IMPORTANCE_LOW))
        val action = { a: String, code: Int ->
            PendingIntent.getService(this, code, Intent(this, MapOverlayService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Teyvat Second Screen")
            .setContentText("Map on the bottom screen")
            .setOngoing(true)
            .setContentIntent(action(ACTION_OPEN_APP, 1))
            .addAction(0, "Open app", action(ACTION_OPEN_APP, 1))
            .addAction(0, "Stop", action(ACTION_STOP, 2))
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    /** Root of the overlay window: turns Back (only delivered while the window is focusable, i.e. typing) into the dispatcher. */
    private class OverlayRoot(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    companion object {
        const val TAG = "MapOverlay"
        const val ACTION_STOP = "com.teyvatmap.thor.overlay.STOP"
        const val ACTION_OPEN_APP = "com.teyvatmap.thor.overlay.OPEN_APP"
        const val EXTRA_DISPLAY = "com.teyvatmap.thor.overlay.DISPLAY"
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 4106

        private val _running = MutableStateFlow(false)
        /** True while the overlay window is up. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Debug screenshots draw this view (the overlay is not an Activity window). */
        @Volatile var debugRoot: View? = null
            private set

        /** Starts the overlay on [displayId] (default: the first secondary display). The caller must be in the foreground. */
        fun start(context: Context, displayId: Int? = null) {
            val i = Intent(context, MapOverlayService::class.java)
            if (displayId != null) i.putExtra(EXTRA_DISPLAY, displayId)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MapOverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
