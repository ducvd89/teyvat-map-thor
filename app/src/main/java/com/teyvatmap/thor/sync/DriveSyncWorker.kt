package com.teyvatmap.thor.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.teyvatmap.thor.data.prefs.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Runs one Drive sync round: debounced after changes, at app start, every 6 h, or from "Sync now". */
@HiltWorker
class DriveSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncer: DriveSyncer,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (settings.getLong(SyncKeys.ENABLED) == 0L) return Result.success()
        return try {
            val r = syncer.sync()
            settings.putLong(SyncKeys.LAST_OK, System.currentTimeMillis())
            settings.putString(SyncKeys.ERROR, null)
            settings.putLong(SyncKeys.NEEDS_SIGN_IN, 0)
            Result.success(workDataOf("pulled" to r.pulled, "pushed" to r.pushed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveSyncer.NeedsSignIn) {
            settings.putLong(SyncKeys.NEEDS_SIGN_IN, 1)
            settings.putString(SyncKeys.ERROR, e.message)
            Result.success() // retrying cannot help: the user has to sign in again
        } catch (e: DriveAuthException) {
            settings.putLong(SyncKeys.NEEDS_SIGN_IN, 1)
            settings.putString(SyncKeys.ERROR, "Google rejected the access token: sign in again")
            Result.success()
        } catch (e: IOException) {
            settings.putString(SyncKeys.ERROR, e.message ?: "network error")
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } catch (e: Exception) {
            settings.putString(SyncKeys.ERROR, e.message ?: e.javaClass.simpleName)
            Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}

/** DataStore keys of the sync status (via [SettingsRepository.getLong] / [SettingsRepository.putString]). */
object SyncKeys {
    const val ENABLED = "sync_enabled"
    const val LAST_OK = "sync_last_ok"
    const val ERROR = "sync_error"
    const val ACCOUNT = "sync_account"
    const val NEEDS_SIGN_IN = "sync_needs_sign_in"
}
