package com.teyvatmap.thor.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Outcome of asking Google for a Drive `appdata` access token. */
sealed interface AuthOutcome {
    data class Token(val accessToken: String, val account: String?) : AuthOutcome
    /** The user has to approve the scope once: launch [intent] from an Activity (an IntentSender request). */
    data class NeedsResolution(val intent: PendingIntent) : AuthOutcome
    data class Failure(val message: String) : AuthOutcome
}

/**
 * Google authorization through Play Services' `AuthorizationClient` (PLAN §7). No OAuth client id is needed in code:
 * for Android the client is identified by package name + signing certificate SHA-1 registered in the user's Google
 * Cloud project. [authorize] is silent once the scope was granted, so background workers can call it too; when it
 * answers [AuthOutcome.NeedsResolution] only the UI can continue (see [tokenFromResolution]).
 */
@Singleton
class GoogleAuth @Inject constructor(@ApplicationContext private val context: Context) {

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE))).build()

    suspend fun authorize(): AuthOutcome = suspendCancellableCoroutine { cont ->
        try {
            Identity.getAuthorizationClient(context).authorize(request())
                .addOnSuccessListener { r -> if (cont.isActive) cont.resume(map(r)) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resume(AuthOutcome.Failure(e.message ?: e.javaClass.simpleName)) }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(AuthOutcome.Failure(e.message ?: e.javaClass.simpleName))
        }
    }

    /** Result of the resolution dialog (`StartIntentSenderForResult` data). */
    fun tokenFromResolution(data: Intent?): AuthOutcome = try {
        map(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data!!))
    } catch (e: Exception) {
        AuthOutcome.Failure(e.message ?: "Authorization cancelled")
    }

    private fun map(r: AuthorizationResult): AuthOutcome {
        val pi = r.pendingIntent
        if (r.hasResolution() && pi != null) return AuthOutcome.NeedsResolution(pi)
        val token = r.accessToken ?: return AuthOutcome.Failure("Google returned no access token")
        val account = runCatching { r.toGoogleSignInAccount()?.email }.getOrNull()
        return AuthOutcome.Token(token, account)
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
