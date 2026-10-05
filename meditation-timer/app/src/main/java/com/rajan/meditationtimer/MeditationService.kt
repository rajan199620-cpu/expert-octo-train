package com.rajan.meditationtimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Runs the session. A foreground service plus a partial wake lock keeps the bells on time
 * with the screen off and the phone locked; an Activity-scoped timer would be frozen or
 * killed by Android a few minutes into the sit.
 */
class MeditationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chime: Chime
    private lateinit var dnd: Dnd
    private lateinit var ambient: AmbientPlayer
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedAtWallMs = 0L
    private var volume = 0.6f
    private var alertMode = AlertMode.BELL
    private var autoDnd = false
    /** The before-sit check-in, captured at start so a later sit can't overwrite it. */
    private var before = 0

    private val running: SessionState.Running?
        get() = SessionRepository.state.value as? SessionState.Running

    override fun onCreate() {
        super.onCreate()
        chime = Chime(this)
        dnd = Dnd(this)
        ambient = AmbientPlayer(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(
                intent.toConfig(),
                intent.getFloatExtra(EXTRA_VOLUME, 0.6f),
                AlertMode.entries.getOrElse(intent.getIntExtra(EXTRA_ALERT, 0)) { AlertMode.BELL },
                intent.getBooleanExtra(EXTRA_DND, false),
            )
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_END -> requestEnd()
            ACTION_END_NOW -> endEarly()
            // Nothing running (e.g. a stale notification action after the process died).
            else -> if (running == null) stopSelf()
        }
        // Never restart after a crash: bells replayed at the wrong moment are worse than none.
        return START_NOT_STICKY
    }

    private fun begin(config: SessionConfig, volume: Float, alertMode: AlertMode, autoDnd: Boolean) {
        handler.removeCallbacksAndMessages(null)
        // A previous session's final bell may still be fading; its teardown must not kill this one.
        chime.bell.onAllFinished = null
        chime.release()

        this.volume = volume
        this.alertMode = alertMode
        this.autoDnd = autoDnd
        before = SessionRepository.pendingBefore.also { SessionRepository.pendingBefore = 0 }
        startedAtWallMs = System.currentTimeMillis()
        val session = SessionState.Running(SessionClock(SystemClock.elapsedRealtime()), config)
        val notification = buildNotification(session)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (autoDnd) dnd.engage()
        SessionRepository.update(session)
        schedule(session)
        val prefs = Prefs(this)
        ambient.start(prefs.ambience, prefs.ambienceVolume, prefs.ambienceUri?.let(android.net.Uri::parse))
    }

    /** Posts the bells still to come and the finish, measured from where the clock stands now. */
    private fun schedule(session: SessionState.Running) {
        handler.removeCallbacksAndMessages(null)
        val elapsed = session.clock.elapsedAt(SystemClock.elapsedRealtime())
        val config = session.config
        acquireWakeLock(config.durationMs - elapsed + WAKE_LOCK_SLACK_MS)
        for (cue in BellSchedule.cues(config)) {
            if (cue.atMs > elapsed) handler.postDelayed({ ambient.duck(); chime.ring(volume, alertMode) }, cue.atMs - elapsed)
        }
        // Settle-in breaths: a light buzz at each in- and out-breath until the opening bell.
        // (A few ms pass between Begin and here, so the very first buzz is allowed a little grace.)
        for ((at, phase) in Settle.ticks(Settle.lengthMs(config))) {
            if (at + TICK_GRACE_MS >= elapsed) handler.postDelayed({ chime.breathTick(phase) }, (at - elapsed).coerceAtLeast(0))
        }
        // Posted after the END bell (same delay, FIFO) so that bell is already ringing here.
        handler.postDelayed({ complete(config) }, (config.durationMs - elapsed).coerceAtLeast(0))
    }

    /** Freezes the clock and holds the bells. Do Not Disturb lifts so a call can reach you meanwhile. */
    private fun pause(): SessionState.Running? {
        val session = running ?: return null
        if (session.clock.isPaused) return session
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        dnd.restore()
        ambient.pause()
        val paused = session.copy(clock = session.clock.pause(SystemClock.elapsedRealtime()))
        publish(paused)
        return paused
    }

    private fun resume() {
        val session = running ?: return
        val resumed = session.copy(clock = session.clock.resume(SystemClock.elapsedRealtime()), endingAtMs = null)
        if (autoDnd) dnd.engage()
        ambient.resume()
        publish(resumed)
        schedule(resumed)
    }

    /** Tapping End pauses the sit and gives you a few seconds to change your mind. */
    private fun requestEnd() {
        val paused = pause() ?: return
        if (paused.isEnding) return
        val endingAt = SystemClock.elapsedRealtime() + SessionClock.END_CONFIRM_MS
        publish(paused.copy(endingAtMs = endingAt))
        // Hold the CPU just long enough for the window to close on time with the screen off.
        acquireWakeLock(SessionClock.END_CONFIRM_MS + WAKE_LOCK_SLACK_MS)
        handler.postDelayed({ endEarly() }, SessionClock.END_CONFIRM_MS)
    }

    private fun publish(session: SessionState.Running) {
        SessionRepository.update(session)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(session))
    }

    private fun complete(config: SessionConfig) {
        SessionLog.get(this).add(record(config, config.durationSec))
        SessionRepository.finished(config, startedAtWallMs, config.durationSec, before, noticedCount())
        // The sound eases away under the final bell rather than stopping with it.
        ambient.stop(AmbientPlayer.END_FADE_MS)
        dnd.restore()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Let a ringing bell fade out naturally before tearing down.
        if (chime.isPlaying) chime.bell.onAllFinished = { shutdown() } else shutdown()
    }

    /** Ending early still counts: log the time actually sat, unless it was just a mis-tap. */
    private fun endEarly() {
        val session = running ?: return shutdown()
        val satSec = (session.clock.elapsedAt(SystemClock.elapsedRealtime()) / 1000)
            .coerceAtMost(session.config.durationSec.toLong()).toInt()
        if (satSec >= MIN_LOGGED_SEC) {
            SessionLog.get(this).add(record(session.config, satSec))
            // Early sits get the same reflection screen: the rough ones are the most worth noting.
            SessionRepository.finished(session.config, startedAtWallMs, satSec, before, noticedCount())
        } else {
            SessionRepository.reset()
            Toast.makeText(this, R.string.too_short_to_log, Toast.LENGTH_SHORT).show()
        }
        shutdown()
    }

    private fun noticedCount(): Int = if (SessionRepository.counting) SessionRepository.noticed.value else -1

    private fun record(config: SessionConfig, satSec: Int) =
        SessionRecord(startedAtWallMs, config.durationSec, satSec, before = before, noticed = noticedCount())

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        ambient.stop(1_500)
        chime.release()
        dnd.restore()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ambient.stop(1_500)
        chime.release()
        dnd.restore()
        releaseWakeLock()
        if (SessionRepository.state.value is SessionState.Running) SessionRepository.reset()
        super.onDestroy()
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeditationTimer:session")
            .apply { acquire(timeoutMs) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(session: SessionState.Running): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val remaining = session.config.durationMs - session.clock.elapsedAt(SystemClock.elapsedRealtime())
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meditation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        when {
            session.isEnding -> builder
                .setContentTitle(getString(R.string.notification_ending_title))
                .setContentText(getString(R.string.notification_ending_text))
                .countDownTo(System.currentTimeMillis() + SessionClock.END_CONFIRM_MS)
                .addAction(action(R.string.keep_sitting, ACTION_RESUME, 2))
                .addAction(action(R.string.end_now, ACTION_END_NOW, 3))
            session.isPaused -> builder
                .setContentTitle(getString(R.string.notification_paused_title))
                .setContentText(getString(R.string.notification_paused_text, formatClock(remaining)))
                .setShowWhen(false)
                .addAction(action(R.string.resume, ACTION_RESUME, 2))
                .addAction(action(R.string.end_session, ACTION_END, 4))
            else -> builder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text, session.config.durationSec / 60))
                // Live countdown drawn by the system; no per-second notification updates needed.
                .countDownTo(System.currentTimeMillis() + remaining)
                .addAction(action(R.string.pause, ACTION_PAUSE, 5))
                .addAction(action(R.string.end_session, ACTION_END, 4))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun Notification.Builder.countDownTo(wallMs: Long) = this
        .setUsesChronometer(true)
        .setChronometerCountDown(true)
        .setWhen(wallMs)
        .setShowWhen(true)

    private fun action(label: Int, serviceAction: String, requestCode: Int) = Notification.Action.Builder(
        Icon.createWithResource(this, R.drawable.ic_stat_meditation),
        getString(label),
        PendingIntent.getService(
            this, requestCode,
            Intent(this, MeditationService::class.java).setAction(serviceAction),
            PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    companion object {
        private const val ACTION_START = "com.rajan.meditationtimer.START"
        private const val ACTION_PAUSE = "com.rajan.meditationtimer.PAUSE"
        private const val ACTION_RESUME = "com.rajan.meditationtimer.RESUME"
        private const val ACTION_END = "com.rajan.meditationtimer.END"
        private const val ACTION_END_NOW = "com.rajan.meditationtimer.END_NOW"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_OPENING = "opening"
        private const val EXTRA_CLOSING = "closing"
        private const val EXTRA_END = "end"
        private const val EXTRA_VOLUME = "volume"
        private const val EXTRA_INTERVAL = "interval"
        private const val EXTRA_SETTLE = "settle"
        private const val EXTRA_ALERT = "alert"
        private const val EXTRA_DND = "dnd"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_SLACK_MS = 60_000L
        private const val TICK_GRACE_MS = 250L

        fun start(context: Context, config: SessionConfig, volume: Float, alertMode: AlertMode, autoDnd: Boolean) {
            val intent = Intent(context, MeditationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DURATION, config.durationSec)
                .putExtra(EXTRA_OPENING, config.openingBellSec)
                .putExtra(EXTRA_CLOSING, config.closingBellSec)
                .putExtra(EXTRA_END, config.bellAtEnd)
                .putExtra(EXTRA_VOLUME, volume)
                .putExtra(EXTRA_INTERVAL, config.intervalMin)
                .putExtra(EXTRA_SETTLE, config.settleSec)
                .putExtra(EXTRA_ALERT, alertMode.ordinal)
                .putExtra(EXTRA_DND, autoDnd)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)

        /** Opens the few-second "keep sitting?" window rather than ending outright. */
        fun end(context: Context) = send(context, ACTION_END)
        fun endNow(context: Context) = send(context, ACTION_END_NOW)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, MeditationService::class.java).setAction(action))
        }

        private fun Intent.toConfig() = SessionConfig(
            durationSec = getIntExtra(EXTRA_DURATION, 20 * 60),
            openingBellSec = getIntExtra(EXTRA_OPENING, 5),
            closingBellSec = getIntExtra(EXTRA_CLOSING, 10),
            bellAtEnd = getBooleanExtra(EXTRA_END, false),
            intervalMin = getIntExtra(EXTRA_INTERVAL, 0),
            settleSec = getIntExtra(EXTRA_SETTLE, 0),
        )
    }
}
