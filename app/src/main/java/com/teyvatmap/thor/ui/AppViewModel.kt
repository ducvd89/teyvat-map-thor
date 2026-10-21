package com.teyvatmap.thor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teyvatmap.thor.data.BackupManager
import com.teyvatmap.thor.data.MapRepository
import com.teyvatmap.thor.data.prefs.Settings
import com.teyvatmap.thor.data.prefs.SettingsRepository
import com.teyvatmap.thor.data.store.ImportProgress
import com.teyvatmap.thor.data.store.PackImporter
import com.teyvatmap.thor.data.store.PackPaths
import com.teyvatmap.thor.data.update.DownloadState
import com.teyvatmap.thor.data.update.PackDownloader
import com.teyvatmap.thor.data.update.UpdateManager
import com.teyvatmap.thor.data.update.UpdateUiState
import com.teyvatmap.thor.sync.AuthOutcome
import com.teyvatmap.thor.sync.GoogleAuth
import com.teyvatmap.thor.sync.SyncManager
import com.teyvatmap.thor.sync.SyncUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val progress: ImportProgress) : ImportState
    data class Done(val points: Int, val millis: Long = 0) : ImportState
    data class Failed(val message: String) : ImportState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val repo: MapRepository,
    private val settingsRepo: SettingsRepository,
    private val importer: PackImporter,
    private val downloader: PackDownloader,
    private val backups: BackupManager,
    private val updates: UpdateManager,
    private val sync: SyncManager,
    private val googleAuth: GoogleAuth,
    private val cvIndex: com.teyvatmap.thor.tracking.CvIndexManager,
    val tracking: com.teyvatmap.thor.tracking.TrackingManager,
    private val areaNames: com.teyvatmap.thor.tracking.AreaNameStore,
    val paths: PackPaths,
) : ViewModel() {

    val settings: StateFlow<Settings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Number of imported points; null until the first database read. */
    val pointCount: StateFlow<Int?> =
        repo.pointCount().map<Int, Int?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val downloadState: StateFlow<DownloadState> =
        downloader.state.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadState.Idle)

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _import.asStateFlow()

    private val _packOnDisk = MutableStateFlow(paths.hasImportablePack())
    /** True when `pack/` already holds importable JSON (e.g. pushed with adb). */
    val packOnDisk: StateFlow<Boolean> = _packOnDisk.asStateFlow()

    /** Set by the search field; the activity drops FLAG_NOT_FOCUSABLE while it is true. */
    val searchFocused = MutableStateFlow(false).also { f ->
        // the capture gate must not create a VirtualDisplay while an IME / focus change of ours is in flight
        viewModelScope.launch { f.collect { com.teyvatmap.thor.tracking.CaptureGate.setTyping(f, it) } }
    }

    fun refreshPackOnDisk() { _packOnDisk.value = paths.hasImportablePack() }

    fun startDownload(withDetails: Boolean, withMedia: Boolean) {
        viewModelScope.launch {
            settingsRepo.setDownloadDetails(withDetails)
            settingsRepo.setDownloadMedia(withMedia)
        }
        downloader.start(withDetails, withMedia)
    }

    fun cancelDownload() = downloader.cancel()

    fun importPack() {
        if (_import.value is ImportState.Running) return
        viewModelScope.launch(Dispatchers.Default) {
            _import.value = ImportState.Running(ImportProgress("Starting", 0, 1))
            try {
                val t0 = System.currentTimeMillis()
                val r = importer.importAll { _import.value = ImportState.Running(it) }
                repo.clearCaches()
                val ms = System.currentTimeMillis() - t0
                android.util.Log.i("Import", "imported ${r.points} points, ${r.details} details in $ms ms")
                _import.value = ImportState.Done(r.points, ms)
            } catch (e: Exception) {
                _import.value = ImportState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ------------------------------------------------------- position matching index
    val cvIndexState: StateFlow<com.teyvatmap.thor.tracking.CvIndexUiState> =
        cvIndex.state.stateIn(viewModelScope, SharingStarted.Eagerly, com.teyvatmap.thor.tracking.CvIndexUiState())
    val cvIndexBytes = MutableStateFlow(0L)
    fun rebuildCvIndex() = cvIndex.enqueue(force = true)
    fun refreshCvIndexSize() { viewModelScope.launch(Dispatchers.IO) { cvIndexBytes.value = cvIndex.sizeBytes() } }

    // ---------------------------------------------------------------- data updates
    val updateState: StateFlow<UpdateUiState> = updates.state.stateIn(viewModelScope, SharingStarted.Eagerly, UpdateUiState())
    fun updateNow() = updates.updateNow()
    fun cancelUpdate() = updates.cancel()

    // ------------------------------------------------------------- install maps from zip
    val installState: StateFlow<com.teyvatmap.thor.data.update.InstallUiState> =
        updates.installState.stateIn(viewModelScope, SharingStarted.Eagerly, com.teyvatmap.thor.data.update.InstallUiState())
    fun installZip(uri: android.net.Uri) = updates.installZip(uri)
    fun cancelInstall() = updates.cancelInstall()

    // ------------------------------------------------------------- Drive sync
    val syncState: StateFlow<SyncUiState> = sync.state.stateIn(viewModelScope, SharingStarted.Eagerly, SyncUiState())
    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    fun syncNow() = sync.syncNow()
    fun signOut() { viewModelScope.launch { sync.disable() } }

    /** Asks Google for the Drive appdata scope; [launch] is called when Google needs to show its approval screen. */
    fun signIn(launch: (android.app.PendingIntent) -> Unit) {
        viewModelScope.launch {
            _syncMessage.value = null
            when (val o = googleAuth.authorize()) {
                is AuthOutcome.Token -> sync.enable(o.account)
                is AuthOutcome.NeedsResolution -> launch(o.intent)
                is AuthOutcome.Failure -> _syncMessage.value = "Sign-in failed: ${o.message}"
            }
        }
    }

    fun onSignInResult(data: android.content.Intent?) {
        viewModelScope.launch {
            when (val o = googleAuth.tokenFromResolution(data)) {
                is AuthOutcome.Token -> sync.enable(o.account)
                is AuthOutcome.Failure -> _syncMessage.value = "Sign-in failed: ${o.message}"
                is AuthOutcome.NeedsResolution -> _syncMessage.value = "Google still needs approval"
            }
        }
    }

    // ---------------------------------------------------------------- user data
    private val _dataMessage = MutableStateFlow<String?>(null)
    /** Result line of the last export / import / backup action. */
    val dataMessage: StateFlow<String?> = _dataMessage.asStateFlow()

    private val _backupFiles = MutableStateFlow(backups.list().map { it.name })
    val backupFiles: StateFlow<List<String>> = _backupFiles.asStateFlow()

    private fun dataAction(block: suspend () -> String) {
        viewModelScope.launch {
            _dataMessage.value = try { block() } catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
            _backupFiles.value = backups.list().map { it.name }
        }
    }

    fun exportTo(uri: android.net.Uri) = dataAction { backups.exportTo(uri); "Exported marks, pins and respawn rules." }
    fun importFrom(uri: android.net.Uri) = dataAction { "Imported: ${backups.importFrom(uri)} entries changed." }
    fun backupNow() = dataAction { backups.ensureToday()?.let { "Backup written: ${it.name}" } ?: "Nothing to back up yet." }

    fun setLanguage(v: String) = viewModelScope.launch { settingsRepo.setLanguage(v) }
    fun setGameLanguage(v: String) = viewModelScope.launch {
        settingsRepo.setGameLanguage(v)
        runCatching { areaNames.refresh(v) }          // region names of the game's language for the area-name banner
    }
    fun setAutoMove(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoMoveToSecondary(v) }
    fun setNonFocusable(v: Boolean) = viewModelScope.launch { settingsRepo.setNonFocusable(v) }
    fun setAutoStartOverlay(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoStartOverlay(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { settingsRepo.setKeepScreenOn(v) }
    fun setHideFound(v: Boolean) = viewModelScope.launch { settingsRepo.setHideFound(v) }
    fun setShowHidden(v: Boolean) = viewModelScope.launch { settingsRepo.setShowHidden(v) }
}
