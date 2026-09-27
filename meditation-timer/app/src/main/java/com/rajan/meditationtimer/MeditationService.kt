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
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedAtWallMs = 0L

    override fun onCreate() {
        super.onCreate()
        chime = Chime(this)
        dnd = Dnd(this)
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
            ACTION_STOP -> {
                logEarlyEnd()
                SessionRepository.reset()
                shutdown()
            }
            else -> stopSelf()
        }
        // Never restart after a crash: bells replayed at the wrong moment are worse than none.
        return START_NOT_STICKY
    }

    private fun begin(config: SessionConfig, volume: Float, alertMode: AlertMode, autoDnd: Boolean) {
        handler.removeCallbacksAndMessages(null)
        // A previous session's final bell may still be fading; its teardown must not kill this one.
        chime.bell.onAllFinished = null
        chime.release()

        val start = SystemClock.elapsedRealtime()
        startedAtWallMs = System.currentTimeMillis()
        val notification = buildNotification(config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock(config.durationMs + WAKE_LOCK_SLACK_MS)
        if (autoDnd) dnd.engage()
        SessionRepository.started(start, config)

        for (cue in BellSchedule.cues(config)) {
            handler.postDelayed({ chime.ring(volume, alertMode) }, cue.atMs)
        }
        // Posted after the END bell (same delay, FIFO) so that bell is already ringing here.
        handler.postDelayed({ complete(config) }, config.durationMs)
    }

    private fun complete(config: SessionConfig) {
        SessionLog.get(this).add(SessionRecord(startedAtWallMs, config.durationSec, config.durationSec))
        SessionRepository.finished(config, startedAtWallMs)
        dnd.restore()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Let a ringing bell fade out naturally before tearing down.
        if (chime.isPlaying) chime.bell.onAllFinished = { shutdown() } else shutdown()
    }

    /** Ending early still counts: log the time actually sat, unless it was just a mis-tap. */
    private fun logEarlyEnd() {
        val running = SessionRepository.state.value as? SessionState.Running ?: return
        val satSec = ((SystemClock.elapsedRealtime() - running.startElapsedMs) / 1000)
            .coerceAtMost(running.config.durationSec.toLong()).toInt()
        if (satSec >= MIN_LOGGED_SEC) {
            SessionLog.get(this).add(SessionRecord(startedAtWallMs, running.config.durationSec, satSec))
        }
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        chime.release()
        dnd.restore()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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

    private fun buildNotification(config: SessionConfig): Notification {
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
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MeditationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meditation)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, config.durationSec / 60))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Live countdown drawn by the system; no per-second notification updates needed.
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(System.currentTimeMillis() + config.durationMs)
            .setShowWhen(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_meditation),
                    getString(R.string.end_session),
                    stop,
                ).build(),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        private const val ACTION_START = "com.rajan.meditationtimer.START"
        private const val ACTION_STOP = "com.rajan.meditationtimer.STOP"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_OPENING = "opening"
        private const val EXTRA_CLOSING = "closing"
        private const val EXTRA_END = "end"
        private const val EXTRA_VOLUME = "volume"
        private const val EXTRA_INTERVAL = "interval"
        private const val EXTRA_ALERT = "alert"
        private const val EXTRA_DND = "dnd"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_SLACK_MS = 60_000L

        fun start(context: Context, config: SessionConfig, volume: Float, alertMode: AlertMode, autoDnd: Boolean) {
            val intent = Intent(context, MeditationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DURATION, config.durationSec)
                .putExtra(EXTRA_OPENING, config.openingBellSec)
                .putExtra(EXTRA_CLOSING, config.closingBellSec)
                .putExtra(EXTRA_END, config.bellAtEnd)
                .putExtra(EXTRA_VOLUME, volume)
                .putExtra(EXTRA_INTERVAL, config.intervalMin)
                .putExtra(EXTRA_ALERT, alertMode.ordinal)
                .putExtra(EXTRA_DND, autoDnd)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MeditationService::class.java).setAction(ACTION_STOP))
        }

        private fun Intent.toConfig() = SessionConfig(
            durationSec = getIntExtra(EXTRA_DURATION, 20 * 60),
            openingBellSec = getIntExtra(EXTRA_OPENING, 5),
            closingBellSec = getIntExtra(EXTRA_CLOSING, 10),
            bellAtEnd = getBooleanExtra(EXTRA_END, false),
            intervalMin = getIntExtra(EXTRA_INTERVAL, 0),
        )
    }
}
