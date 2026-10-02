package com.rajan.mindfield

import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.edit
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.rajan.mindfield.core.Codec
import com.rajan.mindfield.core.Sync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the account card shows about the Google link. */
data class CloudState(
    val email: String? = null,
    val lastSyncMs: Long = 0,
    val busy: Boolean = false,
    val message: String? = null,
    /** True when Google says this build isn't registered yet, so the setup steps should show. */
    val needsSetup: Boolean = false,
)

/**
 * Links the app to your Google account, like the Meditation Timer: your journal, predictions,
 * review schedule and settings are kept in the hidden app-data folder of your Google Drive.
 * That needs only the non-sensitive drive.appdata permission; the app never sees the rest of Drive.
 *
 * Connecting downloads any earlier backup and merges it in (so a new phone or a reinstall is
 * restored with one tap), and from then on every change is uploaded a few seconds later.
 */
object GoogleSync {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val FILE_NAME = "mindfield-backup.json"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val UPLOAD_DELAY_SEC = 4L

    /** What Google needs registered once (Cloud console, Android OAuth client). */
    const val PACKAGE = "com.rajan.mindfield"
    const val SHA1 = "06:AD:C4:19:CC:57:0D:B0:55:46:A3:F6:D1:62:E1:F0:92:42:44:2F"

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var pendingUpload: ScheduledFuture<*>? = null
    /** Set while a sync is merging, so the merge's own save doesn't schedule another upload. */
    private val syncing = AtomicBoolean(false)

    private val _state = MutableStateFlow(CloudState())
    val state: StateFlow<CloudState> = _state.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val sp = prefs(context)
        _state.value = CloudState(email = sp.getString(KEY_EMAIL, null), lastSyncMs = sp.getLong(KEY_LAST, 0))
    }

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Starts connecting. Google shows its own account picker and consent screen through [launch]. */
    fun connect(context: Context, launch: (IntentSenderRequest) -> Unit) {
        load(context)
        _state.value = _state.value.copy(busy = true, message = null, needsSetup = false)
        runCatching {
            Identity.getAuthorizationClient(context).authorize(request())
                .addOnSuccessListener { result ->
                    val intent = result.pendingIntent
                    if (result.hasResolution() && intent != null) {
                        launch(IntentSenderRequest.Builder(intent.intentSender).build())
                    } else {
                        syncWith(context.applicationContext, result)
                    }
                }
                .addOnFailureListener { fail(it) }
        }.onFailure { fail(it) }
    }

    fun onConsentResult(context: Context, data: Intent?) {
        val result = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data) }
        result.onSuccess { syncWith(context.applicationContext, it) }
            .onFailure { e ->
                val cancelled = (e as? ApiException)?.statusCode == CommonStatusCodes.CANCELED
                if (cancelled) _state.value = _state.value.copy(busy = false, message = null) else fail(e)
            }
    }

    /** Download, merge and upload now (the "Sync now" button). */
    fun syncNow(context: Context) {
        val app = context.applicationContext
        _state.value = _state.value.copy(busy = true, message = null)
        runCatching {
            Identity.getAuthorizationClient(app).authorize(request())
                .addOnSuccessListener { result -> if (result.hasResolution()) needsReconnect() else syncWith(app, result) }
                .addOnFailureListener { fail(it) }
        }.onFailure { fail(it) }
    }

    /** Called after every change; uploads a few seconds later if connected. */
    fun backupSoon(context: Context) {
        load(context)
        if (_state.value.email == null || syncing.get()) return
        val app = context.applicationContext
        synchronized(this) {
            pendingUpload?.cancel(false)
            pendingUpload = worker.schedule({
                runCatching {
                    Identity.getAuthorizationClient(app).authorize(request())
                        .addOnSuccessListener { result ->
                            val token = result.accessToken
                            if (result.hasResolution() || token == null) {
                                needsReconnect()
                            } else {
                                worker.execute { runCatching { upload(token) }.onSuccess { synced(app, null) }.onFailure { fail(it) } }
                            }
                        }
                        .addOnFailureListener { fail(it) }
                }.onFailure { fail(it) }
            }, UPLOAD_DELAY_SEC, TimeUnit.SECONDS)
        }
    }

    /** Forgets the account on this phone. The copy in Drive stays, ready for the next connect. */
    fun disconnect(context: Context) {
        prefs(context).edit { remove(KEY_EMAIL); remove(KEY_LAST) }
        _state.value = CloudState()
    }

    private fun syncWith(app: Context, result: AuthorizationResult) {
        val token = result.accessToken ?: return fail(IllegalStateException("Google didn't grant access"))
        worker.execute {
            runCatching {
                syncing.set(true)
                try {
                    val email = fetchEmail(token)
                    val store = Store.init(app)
                    val before = store.state.value
                    var restored = 0
                    findFile(token)?.let { id ->
                        val remote = Codec.decode(http("GET", "$API/files/$id?alt=media", token))
                        // Merged inside update, so a note logged during the download isn't lost.
                        store.update { Sync.merge(it, remote) }
                        restored = Sync.restoredEntries(before, store.state.value)
                    }
                    upload(token)
                    email to restored
                } finally {
                    syncing.set(false)
                }
            }.onSuccess { (email, restored) ->
                synced(app, email, if (restored > 0) "Restored $restored field ${if (restored == 1) "note" else "notes"} from Google Drive" else null)
            }.onFailure { fail(it) }
        }
    }

    private fun synced(app: Context, email: String?, message: String? = null) {
        val now = System.currentTimeMillis()
        prefs(app).edit {
            email?.let { putString(KEY_EMAIL, it) }
            putLong(KEY_LAST, now)
        }
        _state.value = _state.value.copy(
            email = email ?: _state.value.email, lastSyncMs = now, busy = false, message = message, needsSetup = false,
        )
    }

    private fun needsReconnect() {
        _state.value = _state.value.copy(busy = false, message = "Google needs you to sign in again: tap Connect.")
    }

    private fun fail(e: Throwable) {
        val setup = e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR
        _state.value = _state.value.copy(busy = false, message = explain(e), needsSetup = setup)
    }

    /** Plain-language reasons for the failures a new setup actually hits. */
    private fun explain(e: Throwable): String = when {
        e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
            "Google doesn't know this app yet. Add an Android OAuth client for it (steps below), then tap Connect again."
        e is ApiException && e.statusCode == CommonStatusCodes.NETWORK_ERROR -> "No internet connection."
        e is DriveError && e.code == 403 && "accessNotConfigured" in e.body ->
            "The Google Drive API isn't turned on in the app's Google Cloud project."
        e is DriveError && e.code == 401 -> "Google sign-in expired: tap Connect."
        e is java.io.IOException -> "No internet connection; your journal will be backed up next time."
        e is IllegalArgumentException -> "The backup in Google Drive couldn't be read."
        else -> "Google backup failed: ${e.message ?: e.javaClass.simpleName}"
    }

    // --- Drive REST calls (blocking; run on [worker]) ---

    private fun upload(token: String) {
        val json = Codec.encode(Store.state.value, System.currentTimeMillis())
        val id = findFile(token)
        if (id != null) {
            http("PATCH", "$UPLOAD/files/$id?uploadType=media", token, json.toByteArray(), "application/json")
        } else {
            val boundary = "mindfield-${System.nanoTime()}"
            val metadata = JSONObject().put("name", FILE_NAME).put("parents", JSONArray().put("appDataFolder"))
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$boundary\r\nContent-Type: application/json\r\n\r\n$json\r\n--$boundary--"
            http("POST", "$UPLOAD/files?uploadType=multipart", token, body.toByteArray(), "multipart/related; boundary=$boundary")
        }
    }

    private fun findFile(token: String): String? {
        val q = URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8")
        val json = JSONObject(http("GET", "$API/files?spaces=appDataFolder&fields=files(id)&q=$q", token))
        val files = json.optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun fetchEmail(token: String): String? = runCatching {
        JSONObject(http("GET", "$API/about?fields=user(emailAddress)", token)).getJSONObject("user").optString("emailAddress").ifBlank { null }
    }.getOrNull()

    private class DriveError(val code: Int, val body: String) : Exception("Google Drive error $code")

    private fun http(method: String, url: String, token: String, body: ByteArray? = null, contentType: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            try {
                c.requestMethod = method
            } catch (e: ProtocolException) {
                // Older HttpURLConnection rejects PATCH; Google APIs accept it as an override.
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", method)
            }
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw DriveError(code, text)
            return text
        } finally {
            c.disconnect()
        }
    }

    fun resetForTests(context: Context) {
        prefs(context).edit { clear() }
        loaded = false
        _state.value = CloudState()
    }

    fun setStateForTests(state: CloudState) {
        loaded = true
        _state.value = state
    }

    private fun prefs(context: Context) = context.getSharedPreferences("google_sync", Context.MODE_PRIVATE)
    private const val KEY_EMAIL = "email"
    private const val KEY_LAST = "last_sync"
}
