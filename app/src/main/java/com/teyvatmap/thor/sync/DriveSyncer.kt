package com.teyvatmap.thor.sync

import com.teyvatmap.thor.data.MarkRepository
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(
    /** Entries that changed locally because of the remote file. */
    val pulled: Int,
    /** True if the merged document was uploaded. */
    val pushed: Boolean,
)

/**
 * One sync round (PLAN §7): get the remote file -> merge -> save locally -> upload if anything changed.
 * The remote file is created on first use. The merge itself is [SyncEngine.merge] (per-key LWW).
 */
class DriveSyncRound(private val marks: MarkRepository, private val drive: DriveClient) {
    suspend fun run(): SyncResult {
        val remoteFile = drive.find()
        val remote = remoteFile?.let { MarksJson.decodeBytes(drive.download(it.id)) } ?: MarksDoc.EMPTY
        val local = marks.toDoc()
        val merged = SyncEngine.merge(local, remote)
        val toLocal = SyncEngine.diff(local, merged)
        marks.applyDoc(toLocal)
        val pushNeeded = remoteFile == null || !SyncEngine.sameContent(remote, merged)
        if (pushNeeded) {
            val bytes = MarksJson.gzip(merged.copy(device = local.device))
            if (remoteFile == null) drive.create(DriveClient.FILE_NAME, bytes) else drive.update(remoteFile.id, bytes)
        }
        return SyncResult(toLocal.marks.size + toLocal.pins.size + toLocal.respawn.size, pushNeeded)
    }
}

/** Builds [DriveSyncRound]s with a header-free HTTP client (the shared one adds HoYoLAB headers). */
@Singleton
class DriveSyncer @Inject constructor(
    private val marks: MarkRepository,
    private val auth: GoogleAuth,
    okHttp: OkHttpClient,
) {
    private val http = okHttp.newBuilder().apply { interceptors().clear() }.build()

    /** Thrown when the scope has to be (re)approved in the UI. */
    class NeedsSignIn(message: String) : Exception(message)

    suspend fun sync(): SyncResult {
        val drive = DriveClient(http, token = {
            when (val a = auth.authorize()) {
                is AuthOutcome.Token -> a.accessToken
                is AuthOutcome.NeedsResolution -> throw NeedsSignIn("Google Drive access needs to be approved again")
                is AuthOutcome.Failure -> throw NeedsSignIn(a.message)
            }
        })
        return DriveSyncRound(marks, drive).run()
    }
}
