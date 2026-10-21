package com.teyvatmap.thor.ui.tracking

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.teyvatmap.thor.tracking.TrackingManager

/**
 * Returns a function that starts screen capture: asks for the notification permission when needed, then for the
 * MediaProjection consent (same flow as the diagnostics screen; with `appops PROJECT_MEDIA allow` the consent is
 * granted without a dialog) and hands the result to [TrackingManager.start]. The consent activity is the system's,
 * not an in-app Dialog window.
 */
@Composable
fun rememberCaptureStarter(manager: TrackingManager): () -> Unit {
    val context = LocalContext.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) manager.start(r.resultCode, data)
    }
    fun requestConsent() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        consent.launch(mpm.createScreenCaptureIntent())
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { requestConsent() }
    return remember(manager) {
        {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) requestConsent() else notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
