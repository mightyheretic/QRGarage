package com.example.qrgarage.backup

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when Drive needs (re-)consent that only the UI can obtain. */
class AuthRequiredException(message: String = "Google Drive access needs to be reconnected") : Exception(message)

/**
 * SET-BK-1: Google Identity Services Authorization API with the `drive.appdata` scope only
 * (the private app folder). The deprecated Google Sign-In API is not used.
 */
@Singleton
class DriveAuth @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client get() = Identity.getAuthorizationClient(context)
    private val scopes = listOf(Scope(DRIVE_APPDATA))

    sealed interface Outcome {
        data class Authorized(val accessToken: String) : Outcome
        /** The UI must launch this intent sender to show the consent screen. */
        data class NeedsConsent(val pendingIntent: PendingIntent) : Outcome
    }

    suspend fun authorize(): Outcome {
        val result = client.authorize(AuthorizationRequest.builder().setRequestedScopes(scopes).build()).await()
        return result.toOutcome()
    }

    /** Completes an authorization started with [Outcome.NeedsConsent]. */
    fun resultFromIntent(data: Intent?): Outcome =
        client.getAuthorizationResultFromIntent(data).toOutcome()

    /** Silent token for background work; throws [AuthRequiredException] if consent is needed. */
    suspend fun tokenOrThrow(): String = when (val o = authorize()) {
        is Outcome.Authorized -> o.accessToken
        is Outcome.NeedsConsent -> throw AuthRequiredException()
    }

    /** Drops a cached token the server rejected so the next [authorize] fetches a fresh one. */
    suspend fun invalidate(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    suspend fun revoke(email: String?, token: String?) {
        token?.let { invalidate(it) }
        if (email != null) {
            runCatching {
                client.revokeAccess(
                    RevokeAccessRequest.builder()
                        .setAccount(Account(email, "com.google"))
                        .setScopes(scopes)
                        .build()
                ).await()
            }
        }
    }

    private fun AuthorizationResult.toOutcome(): Outcome {
        val intent = pendingIntent
        return when {
            hasResolution() && intent != null -> Outcome.NeedsConsent(intent)
            accessToken != null -> Outcome.Authorized(accessToken!!)
            else -> throw AuthRequiredException("Google did not return an access token")
        }
    }

    companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }
}
