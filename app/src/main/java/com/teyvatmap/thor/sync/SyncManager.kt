package com.teyvatmap.thor.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.teyvatmap.thor.data.MarkRepository
import com.teyvatmap.thor.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncPhase { SIGNED_OUT, IDLE, SYNCING, ERROR, NEEDS_SIGN_IN }

data class SyncUiState(
    val phase: SyncPhase = SyncPhase.SIGNED_OUT,
    val lastSynced: Long = 0,
    val error: String? = null,
    val account: String? = null,
) {
    val enabled: Boolean get() = phase != SyncPhase.SIGNED_OUT
}

/** Scheduling + status of the Drive sync (PLAN §7: 10 s after changes, at start, every 6 h, manual). */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: SettingsRepository,
    private val marks: MarkRepository,
) {
    private val wm = WorkManager.getInstance(context)
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    val state: Flow<SyncUiState> = combine(
        settings.longFlow(SyncKeys.ENABLED),
        settings.longFlow(SyncKeys.LAST_OK),
        settings.longFlow(SyncKeys.NEEDS_SIGN_IN),
        combine(settings.stringFlow(SyncKeys.ERROR), settings.stringFlow(SyncKeys.ACCOUNT)) { e, a -> e to a },
        combine(wm.getWorkInfosForUniqueWorkFlow(UNIQUE_DEBOUNCED), wm.getWorkInfosForUniqueWorkFlow(UNIQUE_NOW)) { a, b -> a + b },
    ) { enabled, lastOk, needs, (error, account), infos ->
        val syncing = infos.any { it.state == WorkInfo.State.RUNNING }
        SyncUiState(
            phase = when {
                enabled == 0L -> SyncPhase.SIGNED_OUT
                syncing -> SyncPhase.SYNCING
                needs != 0L -> SyncPhase.NEEDS_SIGN_IN
                error != null -> SyncPhase.ERROR
                else -> SyncPhase.IDLE
            },
            lastSynced = lastOk, error = error, account = account,
        )
    }

    suspend fun isEnabled(): Boolean = settings.getLong(SyncKeys.ENABLED) != 0L

    /** After a successful sign-in / scope approval. */
    suspend fun enable(account: String?) {
        settings.putLong(SyncKeys.ENABLED, 1)
        settings.putLong(SyncKeys.NEEDS_SIGN_IN, 0)
        settings.putString(SyncKeys.ERROR, null)
        settings.putString(SyncKeys.ACCOUNT, account)
        schedulePeriodic()
        syncNow()
    }

    suspend fun disable() {
        settings.putLong(SyncKeys.ENABLED, 0)
        settings.putString(SyncKeys.ERROR, null)
        settings.putString(SyncKeys.ACCOUNT, null)
        wm.cancelUniqueWork(UNIQUE_PERIODIC); wm.cancelUniqueWork(UNIQUE_DEBOUNCED); wm.cancelUniqueWork(UNIQUE_NOW)
    }

    /** Manual "Sync now" / right after sign-in. */
    fun syncNow() {
        wm.enqueueUniqueWork(
            UNIQUE_NOW, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DriveSyncWorker>().setConstraints(net).build(),
        )
    }

    /** Re-enqueueing with REPLACE restarts the 10 s countdown, i.e. a debounce. */
    fun scheduleDebounced() {
        wm.enqueueUniqueWork(
            UNIQUE_DEBOUNCED, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DriveSyncWorker>().setConstraints(net).setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS).build(),
        )
    }

    fun schedulePeriodic() {
        wm.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DriveSyncWorker>(6, TimeUnit.HOURS).setConstraints(net).build(),
        )
    }

    /** Called once from [com.teyvatmap.thor.App]: sync at start and after every local change. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            if (isEnabled()) {
                schedulePeriodic()
                syncNow()
            }
        }
        scope.launch {
            marks.changes.collect { if (isEnabled()) scheduleDebounced() }
        }
    }

    companion object {
        const val UNIQUE_PERIODIC = "drive_sync_periodic"
        const val UNIQUE_DEBOUNCED = "drive_sync_debounced"
        const val UNIQUE_NOW = "drive_sync_now"
        const val DEBOUNCE_SECONDS = 10L
    }
}
