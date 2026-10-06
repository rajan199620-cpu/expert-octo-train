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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Paces a Breathe-tab exercise: one tap in, two taps out, a long buzz to hold. It runs as a
 * foreground service with a partial wake lock, like a sit, so the taps carry on with the phone
 * locked and your eyes closed; a screen-bound timer froze the moment the screen went off. The
 * closing bell rings from here too, on time, rather than from the screen whenever it is next unlocked.
 */
class BreathService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var buzzer: BreathBuzzer
    private lateinit var chime: Chime
    private var wakeLock: PowerManager.WakeLock? = null
    /** SystemClock.elapsedRealtime() when the exercise being paced began; 0 when none. */
    private var startedAt = 0L

    override fun onCreate() {
        super.onCreate()
        buzzer = BreathBuzzer(this)
        chime = Chime(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(
                intent.getStringExtra(EXTRA_PATTERN).orEmpty(),
                intent.getIntExtra(EXTRA_MINUTES, 3),
                intent.getLongExtra(EXTRA_STARTED_AT, SystemClock.elapsedRealtime()),
            )
            ACTION_STOP -> {
                _cancelled.value = startedAt
                shutdown()
            }
            else -> if (startedAt == 0L) stopSelf()
        }
        // Taps replayed after a crash would land at the wrong moment of a breath.
        return START_NOT_STICKY
    }

    private fun begin(patternName: String, minutes: Int, at: Long) {
        val pattern = BreathPattern.ALL.firstOrNull { it.name == patternName } ?: BreathPattern.ALL.first()
        val totalMs = pattern.breathsFor(minutes) * pattern.cycleMs
        val elapsed = SystemClock.elapsedRealtime() - at
        // Every startForegroundService call must be answered with startForeground, even a repeat.
        val notification = buildNotification(pattern, totalMs - elapsed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // The screen asks again when it is rebuilt (say, on rotation): carry on, don't restart.
        if (at == startedAt) return
        handler.removeCallbacksAndMessages(null)
        // The last exercise's closing bell may still be ringing out; its teardown must not end this one.
        chime.bell.onAllFinished = null
        startedAt = at
        pacing = at
        if (elapsed >= totalMs) return shutdown()
        acquireWakeLock(totalMs - elapsed + WAKE_LOCK_SLACK_MS)
        // The service starts a moment after Start is tapped, so the first cue is allowed a little grace.
        for ((cueAt, kind) in BreathBuzz.cues(pattern, totalMs)) {
            if (cueAt + START_GRACE_MS >= elapsed) handler.postDelayed({ buzzer.play(kind) }, (cueAt - elapsed).coerceAtLeast(0))
        }
        handler.postDelayed({ finish() }, totalMs - elapsed)
    }

    /** The exercise ran its course: the closing bell, then away once it has rung out. */
    private fun finish() {
        val at = startedAt
        handler.removeCallbacksAndMessages(null)
        chime.ring(Prefs(this).volume, AlertMode.BELL)
        _ended.value = at
        startedAt = 0L
        pacing = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!chime.isPlaying) return shutdown()
        chime.bell.onAllFinished = { shutdown() }
        // In case the player never reports the end, the service still goes (the bell is 9 s).
        handler.postDelayed({ shutdown() }, BELL_RING_OUT_MS)
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        startedAt = 0L
        pacing = 0L
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (pacing == startedAt) pacing = 0L
        chime.release()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MeditationTimer:breathe")
            .apply { acquire(timeoutMs) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(pattern: BreathPattern, remainingMs: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.breath_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
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
            this, 0,
            Intent(this, BreathService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meditation)
            .setContentTitle(getString(R.string.breath_notification_title, pattern.name))
            .setContentText(getString(R.string.breath_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(System.currentTimeMillis() + remainingMs)
            .setShowWhen(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_meditation), getString(R.string.breath_stop), stop).build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        private const val ACTION_START = "com.rajan.meditationtimer.BREATHE"
        private const val ACTION_STOP = "com.rajan.meditationtimer.BREATHE_STOP"
        private const val EXTRA_PATTERN = "pattern"
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_STARTED_AT = "started_at"
        private const val CHANNEL_ID = "breathe"
        /** Not 2: that is the daily reminder's, which would replace this one or be cleared by it. */
        private const val NOTIFICATION_ID = 3
        private const val WAKE_LOCK_SLACK_MS = 60_000L
        private const val START_GRACE_MS = 1_000L
        private const val BELL_RING_OUT_MS = 15_000L

        private val _cancelled = MutableStateFlow(0L)

        /** The start time of an exercise ended from its notification, so the screen can close it too. */
        val cancelled: StateFlow<Long> = _cancelled

        private val _ended = MutableStateFlow(0L)

        /** The start time of the last exercise that ran its course here, closing bell and all. */
        val ended: StateFlow<Long> = _ended

        /** The start time of the exercise being paced right now; 0 when none. */
        @Volatile private var pacing = 0L

        /** An exercise is under way, so the daily reminder holds off rather than buzz through it. */
        val busy: Boolean get() = pacing != 0L

        /**
         * Whether the closing bell of the exercise started at [startedAt] is this service's to ring
         * (or already rung): false only when the service never got to pace it.
         */
        fun ringsEnd(startedAt: Long): Boolean = pacing == startedAt || _ended.value == startedAt

        fun start(context: Context, pattern: BreathPattern, minutes: Int, startedAt: Long) {
            // Already pacing it: the screen was only rebuilt (rotation, dark mode at night).
            if (pacing == startedAt) return
            _cancelled.value = 0L
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BreathService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_PATTERN, pattern.name)
                        .putExtra(EXTRA_MINUTES, minutes)
                        .putExtra(EXTRA_STARTED_AT, startedAt),
                )
            } catch (e: IllegalStateException) {
                // Android 12+ refuses a start from the background; the screen still paces by sight.
            }
        }

        /** Safe from the background too: stopService, unlike startService, is never refused. */
        fun stop(context: Context) {
            context.stopService(Intent(context, BreathService::class.java))
        }
    }
}
