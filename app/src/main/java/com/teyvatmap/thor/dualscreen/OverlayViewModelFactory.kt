package com.teyvatmap.thor.dualscreen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.teyvatmap.thor.data.BackupManager
import com.teyvatmap.thor.data.MapRepository
import com.teyvatmap.thor.data.MarkRepository
import com.teyvatmap.thor.data.prefs.SettingsRepository
import com.teyvatmap.thor.data.store.PackImporter
import com.teyvatmap.thor.data.store.PackPaths
import com.teyvatmap.thor.data.store.TileStore
import com.teyvatmap.thor.data.update.PackDownloader
import com.teyvatmap.thor.data.update.UpdateManager
import com.teyvatmap.thor.sync.GoogleAuth
import com.teyvatmap.thor.sync.SyncManager
import com.teyvatmap.thor.tracking.AreaNameStore
import com.teyvatmap.thor.tracking.CvIndexManager
import com.teyvatmap.thor.tracking.TrackingManager
import com.teyvatmap.thor.ui.AppViewModel
import com.teyvatmap.thor.ui.map.IconCache
import com.teyvatmap.thor.ui.map.MapViewModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the two `@HiltViewModel`s for the overlay window, which has no Activity (so Hilt's own factory is not
 * available). Both only depend on singletons, which are injected here once.
 */
@Singleton
class OverlayViewModelFactory @Inject constructor(
    private val repo: MapRepository,
    private val marks: MarkRepository,
    private val settings: SettingsRepository,
    private val tiles: TileStore,
    private val icons: IconCache,
    private val tracking: TrackingManager,
    private val importer: PackImporter,
    private val downloader: PackDownloader,
    private val backups: BackupManager,
    private val updates: UpdateManager,
    private val sync: SyncManager,
    private val googleAuth: GoogleAuth,
    private val cvIndex: CvIndexManager,
    private val areaNames: AreaNameStore,
    private val paths: PackPaths,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        MapViewModel::class.java -> MapViewModel(repo, marks, settings, tiles, icons, tracking)
        AppViewModel::class.java ->
            AppViewModel(repo, settings, importer, downloader, backups, updates, sync, googleAuth, cvIndex, tracking, areaNames, paths)
        else -> throw IllegalArgumentException("Unsupported ${modelClass.name}")
    } as T
}
