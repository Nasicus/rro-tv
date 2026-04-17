package ch.nasicus.rro.tv

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaBrowserServiceCompat() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSessionCompat
    private var currentChannel: Channel? = null
    private var poller: NowPlayingPoller? = null
    private var inForeground = false

    override fun onCreate() {
        super.onCreate()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("RroTv/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(PlayerBridge())

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val mbrComponent = ComponentName(this, MediaButtonReceiver::class.java)
        session = MediaSessionCompat(this, "RroPlayback", mbrComponent, null).apply {
            setSessionActivity(sessionActivity)
            setCallback(SessionCallback())
            setPlaybackState(buildState(PlaybackStateCompat.STATE_NONE))
            isActive = true
        }
        sessionToken = session.sessionToken

        poller = NowPlayingPoller(
            currentChannel = { currentChannel },
            onNowPlaying = ::setNowPlayingMetadata,
            api = PlaylistApi(),
        )
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) =
        BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }
        val items = CHANNELS.map { ch ->
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(ch.id)
                .setTitle(getString(ch.nameRes))
                .setSubtitle(ch.longNameRes?.let(::getString))
                .setIconBitmap(channelArtworkBitmap(getString(ch.nameRes)))
                .build()
            MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }
        result.sendResult(items.toMutableList())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        poller?.release()
        poller = null
        session.isActive = false
        session.release()
        player.release()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            val ch = currentChannel
            if (ch == null) {
                playChannel(CHANNELS.first())
            } else {
                ContextCompat.startForegroundService(
                    this@PlaybackService,
                    Intent(this@PlaybackService, PlaybackService::class.java),
                )
                player.play()
            }
        }

        override fun onPause() {
            player.pause()
        }

        override fun onStop() {
            player.stop()
            player.clearMediaItems()
            currentChannel = null
            session.setMetadata(null)
            stopSelf()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val ch = channelById(mediaId) ?: return
            if (currentChannel == ch) {
                player.play()
            } else {
                playChannel(ch)
            }
        }
    }

    private fun playChannel(ch: Channel) {
        currentChannel = ch
        // Promote to a started service so we survive the activity unbinding
        // (pressing Home). MediaBrowserServiceCompat is bind-only by default.
        ContextCompat.startForegroundService(this, Intent(this, PlaybackService::class.java))
        setNowPlayingMetadata(ch, null)
        player.setMediaItem(MediaItem.fromUri(ch.streamUrl))
        player.prepare()
        player.play()
        poller?.onChannelChanged(ch)
    }

    private fun setNowPlayingMetadata(ch: Channel, song: NowPlaying?) {
        val shortName = getString(ch.nameRes)
        val longName = ch.longNameRes?.let(::getString) ?: shortName
        val title = song?.title ?: shortName
        val artist = song?.artist ?: longName
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, ch.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, longName)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, channelArtworkBitmap(shortName))
            .build()
        session.setMetadata(md)
        if (inForeground) updateForegroundNotification()
    }

    private inner class PlayerBridge : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
        override fun onPlaybackStateChanged(state: Int) = refresh()
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "player error", error)
            refresh()
        }

        private fun refresh() {
            val state = when {
                player.playerError != null -> PlaybackStateCompat.STATE_ERROR
                player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
                player.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
                player.playWhenReady -> PlaybackStateCompat.STATE_CONNECTING
                else -> PlaybackStateCompat.STATE_PAUSED
            }
            session.setPlaybackState(buildState(state))
            updateForeground(state)
        }
    }

    private fun buildState(state: Int): PlaybackStateCompat {
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        return PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
    }

    private fun updateForeground(state: Int) {
        val active = state == PlaybackStateCompat.STATE_PLAYING ||
            state == PlaybackStateCompat.STATE_BUFFERING ||
            state == PlaybackStateCompat.STATE_CONNECTING
        if (active) {
            val notification = buildNotification(state)
            if (!inForeground) {
                startForeground(NOTIFICATION_ID, notification)
                inForeground = true
            } else {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
        } else if (inForeground) {
            stopForeground(STOP_FOREGROUND_DETACH)
            inForeground = false
        }
    }

    private fun updateForegroundNotification() {
        val state = session.controller.playbackState?.state ?: return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: Int): Notification {
        val ch = currentChannel
        val title = ch?.let { getString(it.longNameRes ?: it.nameRes) } ?: getString(R.string.app_name)
        val metadataText = session.controller.metadata?.let {
            val t = it.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
            val a = it.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
            if (!t.isNullOrBlank() && t != a) "$a — $t" else a
        }
        val isPlaying = state == PlaybackStateCompat.STATE_PLAYING
        val toggle = NotificationCompat.Action(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            getString(if (isPlaying) R.string.pause else R.string.play),
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE),
        )
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0)
        return NotificationCompat.Builder(this, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(metadataText)
            .setLargeIcon(ch?.let { channelArtworkBitmap(getString(it.nameRes)) })
            .setContentIntent(session.controller.sessionActivity)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(toggle)
            .setStyle(mediaStyle)
            .build()
    }

    companion object {
        const val ROOT_ID = "root"
        private const val NOTIFICATION_ID = 1
        private const val MEDIA_CHANNEL_ID = "default_channel_id"
        private const val TAG = "RroPlayback"
    }
}

private const val POLL_TAG = "RroPoller"

/**
 * Polls the GraphQL playlist API for the current track and pushes it to the
 * MediaSessionCompat as updated metadata. Schedules each poll for the moment
 * the current track is expected to end (start_time + duration_ms) so we
 * don't burn requests on a fixed timer. Channel name stays visible when
 * nothing musical is playing (talk, news, ads) — the API returns no track.
 */
private class NowPlayingPoller(
    private val currentChannel: () -> Channel?,
    private val onNowPlaying: (Channel, NowPlaying?) -> Unit,
    private val api: PlaylistApi,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null

    fun onChannelChanged(channel: Channel) {
        pollJob?.cancel()
        pollJob = scope.launch { pollLoop(channel) }
    }

    private suspend fun pollLoop(channel: Channel) {
        while (scope.isActive) {
            val nowPlaying = withContext(Dispatchers.IO) { api.fetchPlayingNow(channel.playlistApiId) }
            if (currentChannel() != channel) return
            Log.d(POLL_TAG, "${channel.id}: ${nowPlaying?.artist} – ${nowPlaying?.title}")
            onNowPlaying(channel, nowPlaying)
            delay(nextPollDelayMs(nowPlaying))
        }
    }

    private fun nextPollDelayMs(np: NowPlaying?): Long {
        val timeToEnd = np?.millisUntilEnd() ?: return DEFAULT_POLL_MS
        return (timeToEnd + 3_000).coerceIn(MIN_POLL_MS, MAX_POLL_MS)
    }

    fun release() {
        scope.cancel()
    }

    companion object {
        private const val MIN_POLL_MS = 5_000L
        private const val MAX_POLL_MS = 5 * 60_000L
        private const val DEFAULT_POLL_MS = 30_000L
    }
}

private const val ARTWORK_SIZE = 256
private const val RRO_RED = 0xFFE3000B.toInt()
private val artworkCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

private fun channelArtworkBitmap(channelName: String): Bitmap =
    artworkCache.getOrPut(channelName) { generateArtworkBitmap(channelName) }

private fun generateArtworkBitmap(channelName: String): Bitmap {
    val bmp = Bitmap.createBitmap(ARTWORK_SIZE, ARTWORK_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(RRO_RED)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val lines = if (' ' in channelName) channelName.split(' ') else listOf(channelName)
    val maxWidth = ARTWORK_SIZE * 0.85f
    var size = 120f
    while (size > 20f && lines.maxOf { paint.apply { textSize = size }.measureText(it) } > maxWidth) {
        size -= 4f
    }
    paint.textSize = size
    val fm = paint.fontMetrics
    val lineHeight = fm.descent - fm.ascent
    var y = ARTWORK_SIZE / 2f - (lineHeight * lines.size) / 2f - fm.ascent
    for (line in lines) {
        canvas.drawText(line, ARTWORK_SIZE / 2f, y, paint)
        y += lineHeight
    }
    return bmp
}
