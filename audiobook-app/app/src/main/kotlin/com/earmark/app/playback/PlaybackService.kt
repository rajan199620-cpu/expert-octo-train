package com.earmark.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media.session.MediaButtonReceiver
import com.earmark.app.R
import com.earmark.app.earmark
import com.earmark.app.ui.MainActivity
import com.earmark.core.player.PlaybackStatus
import com.earmark.core.player.PlayerState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps narration alive with the screen off, and exposes it to the lock screen, notification,
 * Bluetooth/wired headsets, car head units and smartwatches through a MediaSession.
 */
class PlaybackService : LifecycleService() {
    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false

    private val hub get() = earmark.hub

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSessionCompat(this, "Earmark").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = hub.play()
                override fun onPause() = hub.pause()
                override fun onStop() = hub.pause()
                // Headset double-tap / "next" = bookmark what you just heard (configurable).
                override fun onSkipToNext() = hub.headsetNext()
                override fun onSkipToPrevious() { hub.skipSentences(-1) }
                override fun onFastForward() { hub.forwardSeconds(30) }
                override fun onRewind() { hub.rewindSeconds(15) }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        lifecycleScope.launch {
            hub.session.combine(hub.playerState) { s, p -> s to p }.collect { (s, p) ->
                if (s == null || p == null) {
                    stopForegroundCompat(remove = true)
                    stopSelf()
                } else {
                    update(s, p)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        val s = hub.session.value
        val p = hub.playerState.value
        // startForegroundService() must be answered with startForeground() promptly.
        if (s != null && p != null) goForeground(buildNotification(s, p)) else goForeground(placeholderNotification())
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun update(s: Session, p: PlayerState) {
        val playing = p.status == PlaybackStatus.PLAYING
        val chapter = s.book.chapterOf(p.position)
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapter?.title ?: s.book.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, s.book.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.book.author ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, ((chapter?.index ?: 0) + 1).toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, s.book.chapters.size.toLong())
                .build(),
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_STOP,
                )
                .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (playing) p.speed else 0f)
                .build(),
        )
        val notification = buildNotification(s, p)
        if (playing) {
            acquireWakeLock()
            goForeground(notification)
        } else {
            releaseWakeLock()
            // Paused: keep the notification (so the user can resume) but let it be swiped away.
            stopForegroundCompat(remove = false)
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
        }
    }

    private fun buildNotification(s: Session, p: PlayerState): Notification {
        val playing = p.status == PlaybackStatus.PLAYING
        val chapter = s.book.chapterOf(p.position)
        val page = s.book.sentences.getOrNull(p.position)?.page
        val bookmarkLabel = if (hub.settingsStore.current.headsetNextBookmarks) "Bookmark" else "Next"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.book.title)
            .setContentText(listOfNotNull(chapter?.title, page?.let { "page $it" }).joinToString(" · "))
            .setContentIntent(openAppIntent())
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_rew, "Back 15 seconds", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_REWIND))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE),
            )
            .addAction(R.drawable.ic_notification, bookmarkLabel, MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun placeholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(openAppIntent())
            .build()

    private fun goForeground(notification: Notification) {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
            inForeground = true
        } catch (e: Exception) {
            // Android 12+ refuses foreground starts from the background in some cases (e.g. resuming
            // after a phone call). Playback continues; it just isn't protected from being killed.
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
        }
    }

    private fun stopForegroundCompat(remove: Boolean) {
        if (!inForeground && !remove) return
        ServiceCompat.stopForeground(this, if (remove) ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH)
        inForeground = false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Earmark:narration").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L) // safety timeout; re-acquired on every playing update
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 42
    }
}
