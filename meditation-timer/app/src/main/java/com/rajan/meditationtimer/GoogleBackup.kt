package com.rajan.meditationtimer

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the History screen shows about the Google backup. */
data class CloudState(
    val email: String? = null,
    val lastSyncMs: Long = 0,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * Backs the whole history (journal notes and settings included) up to the user's Google Drive,
 * in the hidden app-data folder that only this app can read. That needs only the non-sensitive
 * drive.appdata permission: the app never sees the rest of the Drive.
 *
 * Connecting downloads any earlier backup and merges it in, so a reinstall or a new phone is
 * restored with one tap; after that every change is uploaded a few seconds later, merged with
 * whatever another phone put there first.
 */
object GoogleBackup {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val FILE_NAME = "meditation-history.csv"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val UPLOAD_DELAY_SEC = 3L

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

    /** Forgets what was loaded, so a test starts from what its own preferences say. */
    internal fun resetForTests() {
        loaded = false
        _state.value = CloudState()
    }

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /**
     * Starts connecting. Google shows its own account picker and permission screen through
     * [launch]; the answer comes back in [onConsentResult].
     */
    fun connect(context: Context, launch: (IntentSenderRequest) -> Unit) {
        load(context)
        _state.value = _state.value.copy(busy = true, message = null)
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
    }

    fun onConsentResult(context: Context, data: Intent?) {
        val result = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data) }
        result.onSuccess { syncWith(context.applicationContext, it) }
            .onFailure { e ->
                val cancelled = (e as? ApiException)?.statusCode == CommonStatusCodes.CANCELED
                if (cancelled) _state.value = _state.value.copy(busy = false, message = null) else fail(e)
            }
    }

    /** Download, merge and upload now (the "Back up now" button). */
    fun syncNow(context: Context) {
        val app = context.applicationContext
        _state.value = _state.value.copy(busy = true, message = null)
        Identity.getAuthorizationClient(app).authorize(request())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) needsReconnect() else syncWith(app, result)
            }
            .addOnFailureListener { fail(it) }
    }

    /** Called after every change to the history; uploads a few seconds later if connected. */
    fun backupSoon(context: Context) {
        load(context)
        if (_state.value.email == null || syncing.get()) return
        val app = context.applicationContext
        synchronized(this) {
            pendingUpload?.cancel(false)
            pendingUpload = worker.schedule({
                Identity.getAuthorizationClient(app).authorize(request())
                    .addOnSuccessListener { result ->
                        val token = result.accessToken
                        if (result.hasResolution() || token == null) needsReconnect()
                        // Merge Drive's copy in first: another phone may have uploaded since, and a
                        // plain upload would wipe its sits from the backup.
                        else worker.execute { runCatching { pullMergePush(app, token) }.onSuccess { synced(app, null) }.onFailure { fail(it) } }
                    }
                    .addOnFailureListener { fail(it) }
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
                val email = fetchEmail(token)
                email to pullMergePush(app, token)
            }.onSuccess { (email, restored) ->
                synced(app, email, if (restored > 0) "Restored $restored ${if (restored == 1) "sit" else "sits"} from Google Drive" else null)
            }.onFailure { fail(it) }
        }
    }

    /**
     * Downloads Drive's copy, merges it in and uploads the result, so every upload carries every
     * sit any phone has backed up. Returns how many sits came from Drive.
     */
    private fun pullMergePush(app: Context, token: String): Int {
        syncing.set(true)
        try {
            val log = SessionLog.get(app)
            val wasEmpty = log.records.value.isEmpty()
            var restored = 0
            findFile(token)?.let { id ->
                val text = http("GET", "$API/files/$id?alt=media", token)
                restored = log.merge(History.fromCsv(text, ZoneId.systemDefault()))
                // A fresh install also gets its usual-sit settings back.
                if (wasEmpty) History.settingsFrom(text)?.let { Prefs(app).importSettings(it) }
            }
            upload(app, token)
            return restored
        } finally {
            syncing.set(false)
        }
    }

    private fun synced(app: Context, email: String?, message: String? = null) {
        val now = System.currentTimeMillis()
        prefs(app).edit {
            email?.let { putString(KEY_EMAIL, it) }
            putLong(KEY_LAST, now)
        }
        _state.value = _state.value.copy(email = email ?: _state.value.email, lastSyncMs = now, busy = false, message = message)
    }

    private fun needsReconnect() {
        _state.value = _state.value.copy(busy = false, message = "Google needs you to sign in again: tap Connect.")
    }

    private fun fail(e: Throwable) {
        _state.value = _state.value.copy(busy = false, message = explain(e))
    }

    /** Plain-language reasons for the failures a new setup actually hits. */
    private fun explain(e: Throwable): String = when {
        e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
            "This app isn't registered with Google yet (its Android OAuth client is missing or has a different SHA-1)."
        e is ApiException && e.statusCode == CommonStatusCodes.NETWORK_ERROR -> "No internet connection."
        e is DriveError && e.code == 403 && "accessNotConfigured" in e.body ->
            "The Google Drive API isn't turned on in the app's Google Cloud project."
        e is DriveError && e.code == 401 -> "Google sign-in expired: tap Connect."
        e is java.io.IOException -> "No internet connection, so the backup will retry after your next sit."
        else -> "Google backup failed: ${e.message ?: e.javaClass.simpleName}"
    }

    // --- Drive REST calls (blocking; run on [worker]) ---

    private fun upload(app: Context, token: String) {
        val log = SessionLog.get(app)
        // The app folder is private to this app and account, so notes are included.
        val csv = History.toCsv(log.records.value, ZoneId.systemDefault(), Prefs(app).exportSettings())
        val id = findFile(token)
        if (id != null) {
            http("PATCH", "$UPLOAD/files/$id?uploadType=media", token, csv.toByteArray(), "text/csv")
        } else {
            val boundary = "meditation-${System.nanoTime()}"
            val metadata = JSONObject().put("name", FILE_NAME).put("parents", org.json.JSONArray().put("appDataFolder"))
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$boundary\r\nContent-Type: text/csv\r\n\r\n$csv\r\n--$boundary--"
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

    private fun prefs(context: Context) = context.getSharedPreferences("google_backup", Context.MODE_PRIVATE)
    private const val KEY_EMAIL = "email"
    private const val KEY_LAST = "last_sync"
}
