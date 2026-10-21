package com.teyvatmap.thor.debug

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import com.teyvatmap.thor.data.update.UpdateManager
import dagger.hilt.EntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.lang.ref.WeakReference

/** Debug-only: tracks the top activity and writes PNG screenshots of its window on a broadcast. */
class DebugShotProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context!!.applicationContext as Application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { top = WeakReference(a); byDisplay[a.display?.displayId ?: 0] = WeakReference(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { shot(c, i.getStringExtra("name") ?: "shot", if (i.hasExtra("display")) i.getIntExtra("display", 0) else null) }.onFailure { Log.w(TAG, "shot failed: $it") }
            }
        }, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
        // `adb shell am broadcast -a com.teyvatmap.thor.DEBUG_UPDATE` = "Update now" without driving the UI.
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val ep = EntryPointAccessors.fromApplication(c.applicationContext, DebugEntryPoint::class.java)
                ep.updates().updateNow()
            }
        }, IntentFilter("com.teyvatmap.thor.DEBUG_UPDATE"), Context.RECEIVER_EXPORTED)
        // `adb shell am broadcast -a com.teyvatmap.thor.DEBUG_MARK_FOUND --es names "Anemoculus,Geoculus" --ez found true`
        // (or `--es label_ids "5,6"`): bulk "Mark all found" / "Clear all found" (found=false) for the categories with
        // those names (case-insensitive) / ids on every map, written like the filter drawer does. Logs the changed count (tag BulkMark).
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val found = i.getBooleanExtra("found", true)
                val names = i.getStringExtra("names")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                val ids = i.getStringExtra("label_ids")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
                val marks = EntryPointAccessors.fromApplication(c.applicationContext, DebugEntryPoint::class.java).marks()
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val labels = (marks.leafLabelsByName(names) + marks.leafLabelsById(ids)).distinct()
                        val ch = marks.bulkSetFound(labels, found)
                        Log.i("BulkMark", "found=$found names=$names label_ids=$ids -> ${labels.size} leaf categories, ${ch.count} points changed")
                    } catch (e: Exception) {
                        Log.w("BulkMark", "failed: $e")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }, IntentFilter("com.teyvatmap.thor.DEBUG_MARK_FOUND"), Context.RECEIVER_EXPORTED)
        return true
    }

    /** [display] selects the window of that display (0 = the game / FakeGameActivity); default: the main app window. */
    private fun shot(c: Context, name: String, display: Int?) {
        // Game mode: the bottom-screen map is an overlay window, not an Activity; draw its view tree.
        val ov = com.teyvatmap.thor.dualscreen.MapOverlayService.debugRoot
        if (ov != null && ov.width > 0 && (display == null || ov.display?.displayId == display)) {
            val bmp = drawHardware(ov)
            val f = File(c.getExternalFilesDir("shots"), "$name.png")
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "saved ${f.path} display=${ov.display?.displayId} overlay")
            return
        }
        val a = (if (display != null) byDisplay[display]?.get() else byDisplay.entries.firstOrNull { it.key != 0 }?.value?.get() ?: top?.get())
            ?: return Unit.also { Log.w(TAG, "no activity") }
        val v = a.window.decorView
        if (v.width == 0) return
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(a.window, bmp, { r ->
            if (r == PixelCopy.SUCCESS) {
                val f = File(c.getExternalFilesDir("shots"), "$name.png")
                f.parentFile?.mkdirs()
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(TAG, "saved ${f.path} display=${a.display?.displayId}")
            } else Log.w(TAG, "PixelCopy failed $r")
        }, Handler(Looper.getMainLooper()))
    }

    /** Renders a view tree through a [android.graphics.HardwareRenderer] into an ImageReader (software canvases reject the map's hardware bitmaps). */
    private fun drawHardware(v: android.view.View): Bitmap {
        val w = v.width; val h = v.height
        val node = android.graphics.RenderNode("shot")
        node.setPosition(0, 0, w, h)
        val canvas = node.beginRecording(w, h)
        v.draw(canvas)
        node.endRecording()
        val reader = android.media.ImageReader.newInstance(
            w, h, android.graphics.PixelFormat.RGBA_8888, 1,
            android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        )
        val renderer = android.graphics.HardwareRenderer()
        renderer.setSurface(reader.surface)
        renderer.setContentRoot(node)
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        val img = reader.acquireNextImage()
        val hw = Bitmap.wrapHardwareBuffer(img.hardwareBuffer!!, null)!!
        val out = hw.copy(Bitmap.Config.ARGB_8888, false)
        img.close(); renderer.destroy(); reader.close(); node.discardDisplayList()
        return out
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        const val ACTION = "com.teyvatmap.thor.SHOT"
        const val TAG = "DebugShot"
        @Volatile var top: WeakReference<Activity>? = null
        val byDisplay = java.util.concurrent.ConcurrentHashMap<Int, WeakReference<Activity>>()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugEntryPoint {
    fun updates(): UpdateManager
    fun marks(): com.teyvatmap.thor.data.MarkRepository
}
