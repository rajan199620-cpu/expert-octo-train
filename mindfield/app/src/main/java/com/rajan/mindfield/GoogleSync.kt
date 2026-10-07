package com.rajan.mindfield

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.edit
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.rajan.mindfield.core.Codec
import com.rajan.mindfield.core.LinkGuard
import com.rajan.mindfield.core.LinkMessages
import com.rajan.mindfield.core.Sync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What the account card shows about the Google link. */
data class CloudState(
    val email: String? = null,
    val lastSyncMs: Long = 0,
    val busy: Boolean = false,
    val message: String? = null,
    /** True when Google says this build isn't registered yet, so the setup steps should show. */
    val needsSetup: Boolean = false,
    /** True when backups have stopped (sign-in expired, an error) rather than just a note to show. */
    val problem: Boolean = false,
)

/**
 * Links the app to your Google account, like the Meditation Timer: your journal, predictions,
 * review schedule and settings are kept in the hidden app-data folder of your Google Drive.
 * That needs only the non-sensitive drive.appdata permission; the app never sees the rest of Drive.
 *
 * Connecting downloads any earlier backup and merges it in (so a new phone or a reinstall is
 * restored with one tap), and from then on every change is uploaded a few seconds later, merged
 * with whatever another phone put there first.
 *
 * Unlinking also releases Google's grant, so the next Connect shows the account chooser and you
 * can switch to another account. Work still running when you unlink is dropped (see [LinkGuard]).
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
    /**
     * Set on the sync's own thread while it merges Drive's copy in, so that merge's save doesn't
     * schedule another upload. Changes you make meanwhile, on any other thread, still do.
     */
    private val merging = ThreadLocal<Boolean>()
    private val guard = LinkGuard()

    /** Unlinking happened while this work was running; it stops without touching Drive or the link. */
    private class Unlinked : Exception()

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
        val ticket = guard.ticket()
        _state.value = _state.value.copy(busy = true, message = null, needsSetup = false)
        runCatching {
            Identity.getAuthorizationClient(context).authorize(request())
                .addOnSuccessListener { result ->
                    val intent = result.pendingIntent
                    if (result.hasResolution() && intent != null) {
                        if (!guard.isCurrent(ticket)) return@addOnSuccessListener
                        // Google's own screen covers the app now; "Connecting…" waits for its answer
                        // (onConsentResult), and isn't left stuck if that answer never comes back.
                        _state.value = _state.value.copy(busy = false)
                        // The card that asked may be gone by now (sheet closed, phone turned): then
                        // there's no screen to show Google's on, and nothing to crash.
                        runCatching { launch(IntentSenderRequest.Builder(intent.intentSender).build()) }
                    } else {
                        syncWith(context.applicationContext, result, ticket)
                    }
                }
                .addOnFailureListener { fail(it, ticket = ticket) }
        }.onFailure { fail(it, ticket = ticket) }
    }

    /** Google's consent screen answered. Backing out of it is a cancel, not an error. */
    fun onConsentResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            _state.value = _state.value.copy(busy = false, message = null)
            return
        }
        _state.value = _state.value.copy(busy = true)
        val result = runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data) }
        result.onSuccess { syncWith(context.applicationContext, it, guard.ticket()) }
            .onFailure { e ->
                val cancelled = (e as? ApiException)?.statusCode == CommonStatusCodes.CANCELED
                if (cancelled) _state.value = _state.value.copy(busy = false, message = null) else fail(e, ticket = guard.ticket())
            }
    }

    /** Download, merge and upload now (the "Sync now" button). */
    fun syncNow(context: Context) {
        val app = context.applicationContext
        val ticket = guard.ticket()
        _state.value = _state.value.copy(busy = true, message = null)
        runCatching {
            Identity.getAuthorizationClient(app).authorize(request())
                .addOnSuccessListener { result -> if (result.hasResolution()) needsReconnect(ticket = ticket) else syncWith(app, result, ticket) }
                .addOnFailureListener { fail(it, ticket = ticket) }
        }.onFailure { fail(it, ticket = ticket) }
    }

    /** Called after every change; uploads a few seconds later if connected. */
    fun backupSoon(context: Context) {
        load(context)
        if (_state.value.email == null || merging.get() == true) return
        val app = context.applicationContext
        // Remembered until a backup succeeds, so one that couldn't run (offline, the app closed)
        // is tried again when the app next comes to the front.
        prefs(app).edit { putBoolean(KEY_PENDING, true) }
        val ticket = guard.ticket()
        synchronized(this) {
            pendingUpload?.cancel(false)
            pendingUpload = worker.schedule(Runnable {
                if (!guard.isCurrent(ticket)) return@Runnable
                runCatching {
                    Identity.getAuthorizationClient(app).authorize(request())
                        .addOnSuccessListener { result ->
                            val token = result.accessToken
                            if (result.hasResolution() || token == null) {
                                needsReconnect(app, ticket)
                            } else {
                                // Merge Drive's copy in first: another phone may have uploaded since,
                                // and a plain upload would wipe its notes from the backup.
                                worker.execute {
                                    runCatching { pullMergePush(app, token, ticket) }
                                        .onSuccess { synced(app, null, ticket = ticket) }
                                        .onFailure { fail(it, app, ticket) }
                                }
                            }
                        }
                        .addOnFailureListener { fail(it, app, ticket) }
                }.onFailure { fail(it, app, ticket) }
            }, UPLOAD_DELAY_SEC, TimeUnit.SECONDS)
        }
    }

    /** The app came to the front: a backup that didn't get through last time tries again. */
    fun retryIfPending(context: Context) {
        load(context)
        val s = _state.value
        if (s.email == null || s.busy) return
        if (prefs(context).getBoolean(KEY_PENDING, false)) backupSoon(context)
    }

    /**
     * Forgets the account on this phone. The copy in Drive stays, ready for the next connect.
     * Google keeps its grant until told otherwise, and while it does the next Connect silently
     * reuses this account, so the grant is released too; that is what lets you switch accounts.
     */
    fun disconnect(context: Context) {
        load(context)
        val app = context.applicationContext
        val email = _state.value.email
        guard.unlink()
        synchronized(this) {
            pendingUpload?.cancel(false)
            pendingUpload = null
        }
        prefs(app).edit {
            remove(KEY_EMAIL)
            remove(KEY_LAST)
            remove(KEY_PENDING)
            email?.let { putString(KEY_PREVIOUS, it) }
        }
        _state.value = CloudState()
        if (email != null && "@" in email) release(app, email)
    }

    private fun release(app: Context, email: String) {
        val ticket = guard.ticket()
        val notConfirmed = {
            // Only worth saying if nothing has happened since (no new link, no other message).
            if (guard.isCurrent(ticket) && _state.value == CloudState()) {
                _state.value = CloudState(message = LinkMessages.unlinkNotConfirmed(email))
            }
        }
        runCatching {
            val request = RevokeAccessRequest.builder()
                .setAccount(Account(email, "com.google"))
                .setScopes(listOf(Scope(SCOPE)))
                .build()
            Identity.getAuthorizationClient(app).revokeAccess(request).addOnFailureListener { notConfirmed() }
        }.onFailure { notConfirmed() }
    }

    private fun syncWith(app: Context, result: AuthorizationResult, ticket: Long) {
        if (!guard.isCurrent(ticket)) return
        val token = result.accessToken ?: return fail(IllegalStateException("Google didn't grant access"), ticket = ticket)
        val sp = prefs(app)
        val previous = sp.getString(KEY_EMAIL, null) ?: sp.getString(KEY_PREVIOUS, null)
        worker.execute {
            runCatching {
                // Without an address the link would look unmade and background backups would never
                // run, so a failed lookup still links, under a plain label.
                val email = fetchEmail(token) ?: LinkMessages.UNKNOWN_ACCOUNT
                email to pullMergePush(app, token, ticket)
            }.onSuccess { (email, restored) ->
                synced(app, email, LinkMessages.afterConnect(previous, email, restored), ticket)
            }.onFailure { fail(it, ticket = ticket) }
        }
    }

    /**
     * Downloads Drive's copy, merges it in and uploads the result, so every upload carries
     * everything any phone has backed up. Returns how many field notes came from Drive.
     */
    private fun pullMergePush(app: Context, token: String, ticket: Long): Int {
        val store = Store.init(app)
        var restored = 0
        var id = findFile(token)
        if (id != null) {
            val remote = try {
                Codec.decode(http("GET", "$API/files/$id?alt=media", token))
            } catch (e: JSONException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (!guard.isCurrent(ticket)) throw Unlinked()
            if (remote == null) {
                // Drive's copy can't be read: keep it aside there, and start a fresh one from this phone.
                http("PATCH", "$API/files/$id", token, JSONObject().put("name", "mindfield-backup.unreadable-${System.currentTimeMillis()}.json").toString().toByteArray(), "application/json")
                id = null
            } else {
                // Merged inside update, so a note logged during the download isn't lost, and only
                // notes that really came from Drive are counted.
                merging.set(true)
                try {
                    store.update { local -> Sync.adopt(local, remote).also { restored = Sync.restoredEntries(local, it) } }
                } finally {
                    merging.remove()
                }
            }
        }
        if (!guard.isCurrent(ticket)) throw Unlinked()
        val sent = upload(token, id, ticket)
        // A change made while this ran (after the snapshot was sent) gets its own upload.
        if (store.state.value !== sent) backupSoon(app)
        return restored
    }

    private fun synced(app: Context, email: String?, message: String? = null, ticket: Long) {
        // Unlinked while this ran: don't bring the account back.
        if (!guard.isCurrent(ticket)) return
        val now = System.currentTimeMillis()
        prefs(app).edit {
            email?.let { putString(KEY_EMAIL, it) }
            putLong(KEY_LAST, now)
            remove(KEY_PENDING)
        }
        _state.value = _state.value.copy(
            email = email ?: _state.value.email, lastSyncMs = now, busy = false, message = message, needsSetup = false, problem = false,
        )
        // Working again: the "backup paused" notification no longer applies.
        Notifier.clearBackupProblem(app)
    }

    /**
     * Google wants a fresh sign-in (in "Testing" mode it asks every 7 days). Backups made in the
     * background then stop, so say so: on the card, with a banner, and once a day as a notification.
     */
    private fun needsReconnect(app: Context? = null, ticket: Long) {
        if (!guard.isCurrent(ticket)) return
        val message = "Google backup is paused: Google needs you to sign in again. Tap Sign in again."
        _state.value = _state.value.copy(busy = false, message = message, problem = true)
        if (app != null && Health.shouldNotifyBackup(app)) Notifier.backupProblem(app, message)
    }

    private fun fail(e: Throwable, app: Context? = null, ticket: Long) {
        // Work cut short by an unlink isn't a failure worth reporting.
        if (e is Unlinked || !guard.isCurrent(ticket)) return
        val setup = e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR
        val message = explain(e)
        // Being offline is normal and passes: say so on the card, but it isn't a stopped backup (the
        // three-day check still catches a long outage), and it's tried again when the app comes back.
        val offline = e is java.io.IOException || (e is ApiException && e.statusCode == CommonStatusCodes.NETWORK_ERROR)
        _state.value = _state.value.copy(busy = false, message = message, needsSetup = setup, problem = !offline)
        // Only tell the user about failures they can do something about.
        if (app != null && !offline && Health.shouldNotifyBackup(app)) Notifier.backupProblem(app, message)
    }

    /** Plain-language reasons for the failures a new setup actually hits. */
    private fun explain(e: Throwable): String = when {
        e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
            "Google doesn't know this app yet. Add an Android OAuth client for it (steps below), then tap Connect again."
        e is ApiException && e.statusCode == CommonStatusCodes.NETWORK_ERROR -> "No internet connection."
        e is DriveError && e.code == 403 && "accessNotConfigured" in e.body ->
            "The Google Drive API isn't turned on in the app's Google Cloud project."
        e is DriveError && e.code == 401 -> "Google sign-in expired: tap Sign in again."
        e is java.io.IOException -> "No internet connection; your journal will be backed up next time."
        e is IllegalArgumentException || e is JSONException -> "The backup in Google Drive couldn't be read."
        else -> "Google backup failed: ${e.message ?: e.javaClass.simpleName}"
    }

    // --- Drive REST calls (blocking; run on [worker]) ---

    /** Uploads this phone's journal to the file [id] (a new file if null). Returns the snapshot it sent. */
    private fun upload(token: String, id: String?, ticket: Long): com.rajan.mindfield.core.AppState {
        val sent = Store.state.value
        val json = Codec.encode(sent, System.currentTimeMillis())
        // Unlinked while this ran: nothing goes to that account.
        if (!guard.isCurrent(ticket)) throw Unlinked()
        if (id != null) {
            http("PATCH", "$UPLOAD/files/$id?uploadType=media", token, json.toByteArray(), "application/json")
        } else {
            val boundary = "mindfield-${System.nanoTime()}"
            val metadata = JSONObject().put("name", FILE_NAME).put("parents", JSONArray().put("appDataFolder"))
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$boundary\r\nContent-Type: application/json\r\n\r\n$json\r\n--$boundary--"
            http("POST", "$UPLOAD/files?uploadType=multipart", token, body.toByteArray(), "multipart/related; boundary=$boundary")
        }
        return sent
    }

    /** The backup file; the oldest if two phones once made one each at the same moment. */
    private fun findFile(token: String): String? {
        val q = URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8")
        val json = JSONObject(http("GET", "$API/files?spaces=appDataFolder&orderBy=createdTime&fields=files(id)&q=$q", token))
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
    /** A change hasn't reached Drive yet. */
    private const val KEY_PENDING = "pending"
    /** The account this phone backed up to before the last unlink, to say so when you switch. */
    private const val KEY_PREVIOUS = "previous_email"
}
