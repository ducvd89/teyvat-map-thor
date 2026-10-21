package com.teyvatmap.thor.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.teyvatmap.thor.MainActivity
import com.teyvatmap.thor.R
import com.teyvatmap.thor.data.db.AppDatabase
import com.teyvatmap.thor.data.prefs.SettingsRepository
import com.teyvatmap.thor.data.store.PackPaths
import com.teyvatmap.thor.tracking.banner.AreaNameMatcher
import com.teyvatmap.thor.tracking.banner.BannerReader
import com.teyvatmap.thor.tracking.banner.BannerRoi
import com.teyvatmap.thor.tracking.banner.MlKitTextReader
import com.teyvatmap.thor.tracking.cv.Cv
import com.teyvatmap.thor.tracking.cv.CvIndex
import com.teyvatmap.thor.tracking.cv.MapCtx
import com.teyvatmap.thor.tracking.cv.MapProvider
import com.teyvatmap.thor.tracking.cv.P2
import com.teyvatmap.thor.tracking.cv.TrackState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service (type mediaProjection) of live tracking (PLAN §6.1): owns the screen capture session and the
 * [TrackingEngine]. The notification has Pause / Resume and Stop. Without a calibration profile it only keeps the
 * capture running (the calibration screen grabs frames from it); the engine starts as soon as a profile is saved.
 */
@AndroidEntryPoint
class TrackingService : Service() {
    @Inject lateinit var manager: TrackingManager
    @Inject lateinit var db: AppDatabase
    @Inject lateinit var cvIndex: CvIndexManager
    @Inject lateinit var regionZoom: RegionZoomStore
    @Inject lateinit var areaNames: AreaNameStore
    @Inject lateinit var packPaths: PackPaths
    @Inject lateinit var settingsRepo: SettingsRepository
    private var banner: MlKitTextReader? = null

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var grabber: ScreenGrabber? = null
    private var engine: TrackingEngine? = null
    private var paused = false
    private var started = false
    private var lastSaved = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_PAUSE -> togglePause()
            ACTION_RELOAD -> if (started) scope.launch { startEngine() }
            ACTION_START -> if (!started) {
                started = true
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                startInForeground()
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                if (data == null || !beginCapture(code, data)) shutdown()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(code: Int, data: Intent): Boolean = try {
        Cv.init()
        // the diagnostics capture test owns the same projection slot
        CaptureService.stop(this)
        val g = ScreenGrabber(this)
        g.onStopped = { shutdown() }
        g.start(code, data)
        grabber = g
        CaptureHub.source = g
        manager.setStatus { it.copy(capturing = true, message = null) }
        scope.launch { createDisplayWhenSettled(g) }
        scope.launch { startEngine() }
        manager.store.params.drop(1).onEach { engine?.setParams(it) }.launchIn(scope)
        true
    } catch (e: Exception) {
        Log.e(TAG, "capture failed", e)
        manager.setStatus { it.copy(capturing = false, message = "Capture failed: ${e.message}") }
        false
    }

    /**
     * Creates the VirtualDisplay once, after the consent activity is gone and our windows have been quiet (see
     * [CapturePolicy]): AYN's firmware can deadlock system_server when it is created during a focus/IME change.
     * Pause/resume never touch it; it lives as long as the projection.
     */
    private suspend fun createDisplayWhenSettled(g: ScreenGrabber) {
        val t0 = SystemClock.elapsedRealtime()
        while (started && grabber === g) {
            val w = CaptureGate.waitMs(g.displayCreated)
            if (w == CapturePolicy.NEVER) return
            if (w == 0L || SystemClock.elapsedRealtime() - t0 > CapturePolicy.MAX_WAIT_MS) break
            delay(w.coerceAtLeast(50L))
        }
        if (!started || grabber !== g) return
        try {
            g.createDisplay()
        } catch (e: Exception) {
            Log.e(TAG, "virtual display failed", e)
            manager.setStatus { it.copy(message = "Capture failed: ${e.message}") }
            shutdown()
        }
    }

    /** (Re)creates the engine from the saved calibration and the built index. */
    private suspend fun startEngine() {
        engine?.let { it.stop(); manager.engine = null }
        engine = null

        val g = grabber ?: return
        val calib = manager.store.calib.value
        if (calib == null) {
            manager.setStatus { it.copy(tracking = false, message = "Calibrate the minimap first") }
            return
        }
        // every map with a built index takes part: the tracker starts on the map of the last position (else Teyvat) and follows the lock
        val built = db.maps().getAll().filter { cvIndex.isBuilt(it.id, it.activeVersion) }
        val versions = built.associate { it.id to it.activeVersion }
        val lastSaved = manager.store.lastPose()
        val startId = lastSaved?.mapId?.takeIf { it in versions } ?: MAP_ID.takeIf { it in versions } ?: versions.keys.minOrNull()
        val index = startId?.let { cvIndex.open(it, versions.getValue(it)) }
        if (index == null || startId == null) {
            cvIndex.enqueue()
            manager.setStatus { it.copy(tracking = false, message = "Matching data for the map is not built yet (see Settings)") }
            return
        }
        if (calib.frameW != g.width || calib.frameH != g.height) {
            Log.w(TAG, "calibration was made at ${calib.frameW}x${calib.frameH}, capture is ${g.width}x${g.height}")
        }
        val prior = lastSaved?.takeIf { it.mapId == startId }?.let { p ->
            val gg = index.geo
            gg.toPx0(p.xPos, p.yPos).let { P2(it.x, it.y) }
        }
        val tables = versions.keys.associateWith { regionZoom.table(it) }
        val params0 = manager.store.params.value
        val provider = object : MapProvider {
            override val ids: List<Int> = versions.keys.filter { it != startId }.sorted()
            override fun load(mapId: Int): MapCtx? {
                val v = versions[mapId] ?: return null
                val idx = cvIndex.open(mapId, v) ?: return null
                return MapCtx.of(idx, manager.store.params.value, tables.getValue(mapId))
            }
        }
        // area-name banner: OCR in the game's language, anchors of the Teyvat map (the only map whose names are matched)
        val gameLang = settingsRepo.current().gameLanguage
        val matcher = AreaNameMatcher(areaNames.names(MAP_ID, gameLang))
        val bannerGeo = if (MAP_ID in versions) CvIndex.readMeta(cvIndex.paths.dir(MAP_ID, versions.getValue(MAP_ID)))?.geo else null
        val boxes = if (bannerGeo == null) emptyMap() else db.areas().getForMap(MAP_ID).associate { a ->
            val p0 = bannerGeo.toPx0(a.lX, a.lY); val p1 = bannerGeo.toPx0(a.rX, a.rY)
            a.id to doubleArrayOf(p0.x, p0.y, p1.x, p1.y)
        }
        val reader = banner ?: MlKitTextReader().also { banner = it }
        val bannerReader = BannerReader(matcher, reader, roi = { manager.store.params.value.let { BannerRoi(it.bannerX, it.bannerY, it.bannerW, it.bannerH) } }, boxOf = { boxes[it] }).takeIf { matcher.size > 0 && bannerGeo != null }
        val e = TrackingEngine(
            index, calib, params0, g, prior, tables.getValue(startId),
            tileFileOf = { gm -> { z, x, y -> packPaths.tileFile(gm.mapId, gm.version, z, x, y) } },
            overlayFileOf = { id -> packPaths.overlayFile(id) },
            selectorMapFile = java.io.File(filesDir, "floor_selector_map.json"),
            banner = bannerReader, bannerMapId = MAP_ID, maps = provider,
            onPose = { pose, raw ->
                manager.publish(pose)
                Log.d(TAG, "pose ${pose.state} map=${pose.mapId} ${raw.mode} x=%.0f y=%.0f hd=%s conf=%.2f floor=${pose.floorId} score=%.2f ${raw.ms.toInt()}ms next=%.1fs ring=%.2f scale=%.3f".format(pose.xPos, pose.yPos, pose.headingDeg?.toInt(), pose.confidence, raw.score, raw.delay, engine?.lastRing ?: 0.0, pose.scale))
                if (pose.state == TrackState.TRACKING && raw.accepted) maybeSave(pose)
                updateNotification(pose.state)
            },
            onDebug = { manager.publishDebug(it) },
        )
        e.debugEnabled = manager.debugEnabled
        engine = e
        manager.engine = e
        paused = false
        manager.setStatus { it.copy(tracking = true, paused = false, message = null) }
        e.start()
    }

    private fun maybeSave(p: PlayerPose) {
        val now = System.currentTimeMillis()
        if (now - lastSaved < 10_000) return
        lastSaved = now
        runCatching { manager.store.saveLastPose(SavedPose(p.mapId, p.xPos, p.yPos, p.floorId, now)) }
    }

    private fun togglePause() {
        val e = engine ?: return
        paused = !paused
        if (paused) e.pause() else e.resume()
        manager.setStatus { it.copy(paused = paused) }
        updateNotification(null)
    }

    // ---------------------------------------------------------------------------------- notification
    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun pending(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, TrackingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Teyvat Second Screen")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (paused) "Resume" else "Pause", pending(ACTION_PAUSE, 1))
            .addAction(0, "Stop", pending(ACTION_STOP, 2))
            .build()
    }

    private var lastText = ""

    private fun updateNotification(state: TrackState?) {
        val text = when {
            paused -> "Paused"
            state == null -> lastText
            else -> when (state) {
                TrackState.TRACKING -> "Tracking your position"
                TrackState.LOADING -> "Loading screen"
                TrackState.RELOCATING -> "Finding your position"
                TrackState.DOMAIN -> "In a domain"
                TrackState.HIDDEN -> "Minimap hidden"
                TrackState.LOST -> "Position lost"
                TrackState.OFF -> "Waiting"
                TrackState.FIXED -> "Position fixed from the map"
            }
        }
        if (text == lastText) return
        lastText = text
        nm().notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startInForeground() {
        nm().createNotificationChannel(NotificationChannel(CHANNEL, "Live position", NotificationManager.IMPORTANCE_LOW))
        lastText = "Reading the minimap (read-only)"
        startForeground(NOTIFICATION_ID, buildNotification(lastText), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun shutdown() {
        if (!started) { stopSelf(); return }
        started = false
        val p = manager.pose.value
        if (p.hasPosition) runCatching { manager.store.saveLastPose(SavedPose(p.mapId, p.xPos, p.yPos, p.floorId, System.currentTimeMillis())) }

        engine?.stop()
        engine = null
        CaptureHub.source = null
        grabber?.stop(); grabber = null
        banner?.close(); banner = null
        manager.reset()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (started) shutdown()
        super.onDestroy()
    }

    companion object {
        const val TAG = "TrackingService"
        /** Teyvat: the default start map and the map whose area names the banner reader matches. */
        const val MAP_ID = 2
        const val ACTION_START = "com.teyvatmap.thor.tracking.START"
        const val ACTION_STOP = "com.teyvatmap.thor.tracking.STOP"
        const val ACTION_PAUSE = "com.teyvatmap.thor.tracking.PAUSE"
        const val ACTION_RELOAD = "com.teyvatmap.thor.tracking.RELOAD"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 4105

        fun start(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) { context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP)) }
        fun togglePause(context: Context) { context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_PAUSE)) }
        fun reload(context: Context) {
            // only meaningful while the service runs; never starts a foreground service without consent
            if (CaptureHub.source != null) context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_RELOAD))
        }
    }
}
