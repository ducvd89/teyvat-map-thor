package com.teyvatmap.thor

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.teyvatmap.thor.data.BackupManager
import com.teyvatmap.thor.data.update.UpdateManager
import com.teyvatmap.thor.data.update.WorkScheduler
import com.teyvatmap.thor.sync.SyncManager
import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var backups: BackupManager
    @Inject lateinit var updates: UpdateManager
    @Inject lateinit var sync: SyncManager
    @Inject lateinit var cvIndex: com.teyvatmap.thor.tracking.CvIndexManager
    @Inject lateinit var importer: com.teyvatmap.thor.data.store.PackImporter
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        WorkScheduler.scheduleAll(this)
        updates.schedulePeriodic()
        sync.start(appScope)
        appScope.launch {
            runCatching { com.teyvatmap.thor.tracking.cv.Cv.init() }
            runCatching { importer.ensureEntrances() }
            runCatching { cvIndex.enqueue() }
            runCatching { backups.ensureToday() }
            runCatching { updates.checkOnStart() }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
            .build()
}
