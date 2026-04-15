package ch.nasicus.rro.tv

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the ExoPlayer + MediaSession. Lives independently of the activity so
 * audio keeps playing once Google TV's ambient screensaver kicks in. The
 * active MediaSession is what the system "now playing" overlay reads, so
 * NowPlayingPoller's metadata updates surface there for free.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var nowPlayingPoller: NowPlayingPoller? = null

    override fun onCreate() {
        super.onCreate()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("RroTv/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)

        val player = ExoPlayer.Builder(this)
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

        nowPlayingPoller = NowPlayingPoller(player, applicationContext, PlaylistApi())
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        nowPlayingPoller?.release()
        nowPlayingPoller = null
        session?.run {
            player.release()
            release()
            session = null
        }
        super.onDestroy()
    }
}

private const val TAG = "RroPlayback"
const val EXTRA_CHANNEL_ID = "channel_id"

/**
 * Polls the GraphQL playlist API for the current track and rewrites the
 * MediaItem's title/artist so the system now-playing overlay (incl. Google
 * TV ambient) shows live song info. Schedules each poll for the moment the
 * current track is expected to end (`start_time + duration_ms`) so we don't
 * burn requests on a fixed timer. Channel name is preserved when nothing
 * musical is playing (talk shows, news), since the API simply returns no
 * current track.
 */
private class NowPlayingPoller(
    private val player: Player,
    private val ctx: Context,
    private val api: PlaylistApi,
) : Player.Listener {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null
    private var lastChannelId: String? = null

    init {
        player.addListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        restart(mediaItem)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) restart(player.currentMediaItem) else stop()
    }

    private fun restart(item: MediaItem?) {
        val channelId = item?.mediaMetadata?.extras?.getString(EXTRA_CHANNEL_ID)
        if (channelId == lastChannelId && pollJob?.isActive == true) return
        stop()
        val channel = channelById(channelId) ?: return
        lastChannelId = channelId
        pollJob = scope.launch { pollLoop(channel) }
    }

    private fun stop() {
        pollJob?.cancel()
        pollJob = null
        lastChannelId = null
    }

    private suspend fun pollLoop(channel: Channel) {
        while (scope.isActive) {
            val nowPlaying = withContext(Dispatchers.IO) { api.fetchPlayingNow(channel.playlistApiId) }
            if (nowPlaying != null) {
                Log.d(TAG, "now playing on ${channel.id}: ${nowPlaying.artist} – ${nowPlaying.title}")
                applyMetadata(channel, nowPlaying)
            } else {
                resetToChannelName(channel)
            }
            delay(nextPollDelayMs(nowPlaying))
        }
    }

    private fun applyMetadata(channel: Channel, np: NowPlaying) {
        val item = player.currentMediaItem ?: return
        if (item.mediaId != channel.id) return  // user switched away mid-fetch
        val title = np.title ?: ctx.getString(channel.nameRes)
        val artist = np.artist ?: ctx.getString(channel.nameRes)
        if (item.mediaMetadata.title?.toString() == title &&
            item.mediaMetadata.artist?.toString() == artist
        ) return
        replaceMetadata(item, channel.id, title, artist)
    }

    private fun resetToChannelName(channel: Channel) {
        val item = player.currentMediaItem ?: return
        if (item.mediaId != channel.id) return
        val name = ctx.getString(channel.nameRes)
        if (item.mediaMetadata.title?.toString() == name &&
            item.mediaMetadata.artist?.toString() == name
        ) return
        replaceMetadata(item, channel.id, name, name)
    }

    private fun replaceMetadata(item: MediaItem, channelId: String, title: String, artist: String) {
        val md = item.mediaMetadata.buildUpon()
            .setTitle(title)
            .setArtist(artist)
            .setExtras(Bundle().apply { putString(EXTRA_CHANNEL_ID, channelId) })
            .build()
        val newItem = item.buildUpon().setMediaMetadata(md).build()
        player.replaceMediaItem(player.currentMediaItemIndex, newItem)
    }

    private fun nextPollDelayMs(np: NowPlaying?): Long {
        val timeToEnd = np?.millisUntilEnd() ?: return DEFAULT_POLL_MS
        // Add a small buffer past the end so the new song's metadata is ready
        // in the API by the time we ask for it.
        return (timeToEnd + 3_000).coerceIn(MIN_POLL_MS, MAX_POLL_MS)
    }

    fun release() {
        player.removeListener(this)
        scope.cancel()
    }

    companion object {
        private const val MIN_POLL_MS = 5_000L
        private const val MAX_POLL_MS = 5 * 60_000L
        private const val DEFAULT_POLL_MS = 30_000L
    }
}

/**
 * Builds the MediaItem for a channel. The title/artist start as the channel
 * name and get rewritten by NowPlayingPoller as soon as the API responds.
 */
fun MediaItem.Builder.withChannel(channel: Channel, ctx: Context): MediaItem.Builder {
    val name = ctx.getString(channel.nameRes)
    val md = MediaMetadata.Builder()
        .setTitle(name)
        .setArtist(name)
        .setAlbumTitle(ctx.getString(R.string.app_name))
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setExtras(Bundle().apply { putString(EXTRA_CHANNEL_ID, channel.id) })
        .build()
    return setMediaId(channel.id)
        .setUri(channel.streamUrl)
        .setMediaMetadata(md)
}
