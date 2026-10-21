package com.teyvatmap.thor.ui.tracking

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.teyvatmap.thor.tracking.CaptureGate
import com.teyvatmap.thor.tracking.TrackingManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Invisible activity that asks for the notification permission and the MediaProjection consent on behalf of the
 * bottom-screen overlay (a service cannot show either), hands the result to [TrackingManager.start] and finishes.
 * Both prompts are system activities, not windows of ours.
 */
@AndroidEntryPoint
class CaptureConsentActivity : ComponentActivity() {
    @Inject lateinit var tracking: TrackingManager

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) tracking.start(r.resultCode, data)
        finish()
    }
    private val notif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestConsent() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CaptureGate.setConsentActive(true)
        if (savedInstanceState != null) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) requestConsent() else notif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        CaptureGate.noteWindowChange()
    }

    override fun onDestroy() {
        CaptureGate.setConsentActive(false)
        super.onDestroy()
    }

    private fun requestConsent() {
        consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}
