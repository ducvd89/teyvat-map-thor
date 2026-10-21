package com.teyvatmap.thor.tracking

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.teyvatmap.thor.tracking.cv.CvIndex
import com.teyvatmap.thor.tracking.cv.CvPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CvIndexUiState(
    val running: Boolean = false,
    val waiting: Boolean = false,
    val stage: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** Facade over [CvIndexBuildWorker] and the on-disk index (where it lives, which versions exist). */
@Singleton
class CvIndexManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val wm = WorkManager.getInstance(context)

    val paths = CvPaths(File(context.getExternalFilesDir(null) ?: context.filesDir, "cv"))

    /** Builds what is missing (a no-op when every map's active version is indexed). */
    fun enqueue(force: Boolean = false) {
        wm.enqueueUniqueWork(
            CvIndexBuildWorker.UNIQUE,
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CvIndexBuildWorker>()
                .setInputData(workDataOf(CvIndexBuildWorker.KEY_FORCE to force))
                .build(),
        )
    }

    val state: Flow<CvIndexUiState> = wm.getWorkInfosForUniqueWorkFlow(CvIndexBuildWorker.UNIQUE).map { infos ->
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        val waiting = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        val failed = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
        CvIndexUiState(
            running = running != null,
            waiting = running == null && waiting != null,
            stage = running?.progress?.getString(CvIndexBuildWorker.KEY_STAGE).orEmpty(),
            done = running?.progress?.getInt(CvIndexBuildWorker.KEY_DONE, 0) ?: 0,
            total = running?.progress?.getInt(CvIndexBuildWorker.KEY_TOTAL, 0) ?: 0,
            error = if (running == null && waiting == null) failed?.outputData?.getString(CvIndexBuildWorker.KEY_ERROR) else null,
        )
    }

    /** Opens the built index of a map version, or null when it is not built yet. Caller closes it. */
    fun open(mapId: Int, version: String): CvIndex? = CvIndex.open(paths.dir(mapId, version))

    fun isBuilt(mapId: Int, version: String) = paths.isBuilt(mapId, version)

    /** Total size of the index files on disk. */
    fun sizeBytes(): Long = paths.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
