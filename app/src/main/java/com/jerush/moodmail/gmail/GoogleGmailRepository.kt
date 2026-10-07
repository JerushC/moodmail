package com.jerush.moodmail.gmail

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.jerush.moodmail.model.Email
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gmail access through Google Identity Services authorization plus the Gmail REST API.
 *
 * Requests the `gmail.readonly` scope only. There is no client ID in code: Google matches the
 * Android OAuth client by package name (`com.jerush.moodmail`) and the SHA-1 of the signing
 * certificate. Before this works, the Google Cloud project needs the Gmail API enabled, an OAuth
 * consent screen (Testing mode is fine, with the user added as a test user), and an OAuth client
 * of type Android registered with that package name and SHA-1. A debug build and a release build
 * are signed with different keys, so each needs its own SHA-1 registered.
 */
class GoogleGmailRepository(context: Context) : GmailRepository {

    private val appContext: Context = context.applicationContext

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun authRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GMAIL_READONLY)))
            .build()

    override suspend fun authorize(activity: ComponentActivity): AuthResult {
        val client = Identity.getAuthorizationClient(activity)
        return try {
            val result = client.authorize(authRequest()).awaitTask()
            if (!result.hasResolution()) {
                return tokenOrFailure(result)
            }
            val pendingIntent = result.pendingIntent
                ?: return AuthResult.Failed("Google asked for consent but returned no consent screen")
            val activityResult = launchConsent(activity, pendingIntent)
            if (activityResult.resultCode != Activity.RESULT_OK) {
                return AuthResult.Failed("Sign-in was cancelled")
            }
            val data = activityResult.data
                ?: return AuthResult.Failed("Consent screen returned no data")
            tokenOrFailure(client.getAuthorizationResultFromIntent(data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            AuthResult.Failed(describe(e))
        } catch (e: Exception) {
            AuthResult.Failed("Authorization failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override suspend fun silentToken(): String? = try {
        val result = Identity.getAuthorizationClient(appContext).authorize(authRequest()).awaitTask()
        if (result.hasResolution()) null else result.accessToken
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override suspend fun fetchRecent(accessToken: String, max: Int): List<Email> = withContext(Dispatchers.IO) {
        val listUrl = "$API_BASE/messages".toHttpUrl().newBuilder()
            .addQueryParameter("maxResults", max.coerceIn(1, 500).toString())
            .addQueryParameter("labelIds", "INBOX")
            .build()
        val ids = getJson(listUrl, accessToken).optJSONArray("messages")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf(String::isNotEmpty) }
        }.orEmpty()

        ids.map { id ->
            val url = "$API_BASE/messages".toHttpUrl().newBuilder()
                .addPathSegment(id)
                .addQueryParameter("format", "full")
                .build()
            GmailParser.parseMessage(getJson(url, accessToken))
        }.sortedByDescending { it.sentAt }
    }

    private fun getJson(url: HttpUrl, accessToken: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == 401 -> throw IllegalStateException(
                    "Gmail rejected the access token (401). It has expired or been revoked; sign in again."
                )
                !response.isSuccessful -> throw IOException(
                    "Gmail API ${response.code} for ${url.encodedPath}: ${body.take(300)}"
                )
            }
            return JSONObject(body)
        }
    }

    /**
     * Shows Google's consent UI. Uses the non-lifecycle `register` overload because this runs
     * after onCreate, and unregisters as soon as a result arrives or the caller is cancelled.
     */
    private suspend fun launchConsent(activity: ComponentActivity, pendingIntent: PendingIntent): ActivityResult =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                lateinit var launcher: ActivityResultLauncher<IntentSenderRequest>
                launcher = activity.activityResultRegistry.register(
                    "moodmail-gmail-auth-${UUID.randomUUID()}",
                    ActivityResultContracts.StartIntentSenderForResult(),
                ) { result ->
                    launcher.unregister()
                    if (cont.isActive) cont.resume(result)
                }
                cont.invokeOnCancellation { activity.runOnUiThread { launcher.unregister() } }
                try {
                    launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } catch (e: Exception) {
                    launcher.unregister()
                    cont.resumeWithException(e)
                }
            }
        }

    private fun tokenOrFailure(result: AuthorizationResult): AuthResult {
        val token = result.accessToken
        return if (token.isNullOrEmpty()) {
            AuthResult.Failed("Google returned no access token")
        } else {
            AuthResult.Authorized(token)
        }
    }

    private fun describe(e: ApiException): String = when (e.statusCode) {
        // CommonStatusCodes.DEVELOPER_ERROR: package name or SHA-1 does not match an Android OAuth client.
        10 -> "OAuth client not configured for this app (check package name and SHA-1 in Google Cloud Console)"
        // CommonStatusCodes.CANCELED
        16 -> "Sign-in was cancelled"
        // CommonStatusCodes.NETWORK_ERROR
        7 -> "Network error while contacting Google"
        else -> "Google authorization error ${e.statusCode}: ${e.message}"
    }

    private companion object {
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        const val API_BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
    }
}

/** Hand-written Task bridge, because kotlinx-coroutines-play-services is not on the classpath. */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    addOnCanceledListener { cont.cancel() }
}
