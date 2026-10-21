package com.teyvatmap.thor.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.teyvatmap.thor.R
import com.teyvatmap.thor.data.db.AppDatabase
import com.teyvatmap.thor.data.store.PackPaths
import com.teyvatmap.thor.tracking.cv.BuildProgress
import com.teyvatmap.thor.tracking.cv.CvIndexBuilder
import com.teyvatmap.thor.tracking.cv.CvPaths
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds the matching reference data of every map's active `map_version` (PLAN §6.4): preprocessed land mosaics, floor
 * overlay references, layer zones, entrance index and teleport targets, into `getExternalFilesDir/cv/{mapId}/{version}/`.
 * Runs after a pack import and after a data update switched a `map_version`; indices of older versions are deleted
 * once the new one is complete. Takes ~20 s on the emulator, 1-3 minutes on a phone.
 */
@HiltWorker
class CvIndexBuildWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val pack: PackPaths,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val paths = CvPaths(File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "cv"))
        val builder = CvIndexBuilder(pack, paths)
        val maps = db.maps().getAll().sortedBy { if (it.id == 2) 0 else 1 }
        var built = 0
        var lastReport = 0L
        var startedForeground = false
        try {
            for (m in maps) {
                currentCoroutineContext().ensureActive()
                val geo = builder.readGeo(m.id)
                if (geo == null) { Log.w(TAG, "map ${m.id}: no info.json"); continue }
                if (geo.version != m.activeVersion) { Log.w(TAG, "map ${m.id}: pack info (${geo.version}) differs from the database (${m.activeVersion}); skipped"); continue }
                if (!force && paths.isBuilt(m.id, geo.version)) { prune(paths, m.id, geo.version); continue }
                if (!builder.tilesComplete(geo)) { Log.w(TAG, "map ${m.id}: tiles incomplete; skipped"); continue }
                if (!startedForeground) {
                    startedForeground = true
                    runCatching { setForeground(foregroundInfo("Preparing the position matcher", 0, 0)) }
                }
                val t0 = System.currentTimeMillis()
                builder.build(
                    geo,
                    progress = BuildProgress { stage, done, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 1000 || done == total) {   // <= 1 notification update per second
                            lastReport = now
                            // setProgress is suspend-free via the blocking Data API; the worker thread can afford it
                            kotlinx.coroutines.runBlocking {
                                setProgress(workDataOf(KEY_STAGE to stage, KEY_DONE to done, KEY_TOTAL to total))
                                runCatching { setForeground(foregroundInfo(stage, done, total)) }
                            }
                        }
                    },
                    cancelled = { if (isStopped) throw CancellationException("stopped") },
                )
                Log.i(TAG, "map ${m.id} ${geo.version.take(8)} built in ${System.currentTimeMillis() - t0} ms")
                prune(paths, m.id, geo.version)
                built++
            }
            Result.success(workDataOf(KEY_BUILT to built))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "index build failed", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun prune(paths: CvPaths, mapId: Int, keep: String) {
        File(paths.root, "$mapId").listFiles { f -> f.isDirectory && f.name != keep }?.forEach { it.deleteRecursively() }
    }

    private fun foregroundInfo(stage: String, done: Int, total: Int): ForegroundInfo {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Position matcher", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(appContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Preparing the position matcher")
            .setContentText(stage)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "CvIndexBuild"
        const val UNIQUE = "cv_index_build"
        const val KEY_FORCE = "force"
        const val KEY_STAGE = "stage"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val KEY_BUILT = "built"
        private const val CHANNEL = "cv_index"
        private const val NOTIFICATION_ID = 4104
    }
}
