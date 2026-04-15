package ch.nasicus.rro.tv

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
 * Owns the ExoPlayer + MediaLibrarySession. Extends MediaLibraryService (not
 * plain MediaSessionService) because Google TV's Backdrop ambient does a
 * MediaBrowser probe at discovery time — services that return nothing get
 * filtered out of the "now playing" overlay. We expose the 3 channels as a
 * minimal browse tree purely so Backdrop recognizes us as an eligible media
 * app.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
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

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaLibrarySession.Builder(this, player, LibraryCallback(applicationContext))
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

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

private const val ROOT_ID = "root"

/**
 * Exposes the 3 channels as a flat browse tree for any MediaBrowser client
 * (Backdrop, Google Assistant, Android Auto). Returning a non-empty tree is
 * the signal Backdrop uses to decide whether to render the screensaver
 * overlay for this app.
 */
private class LibraryCallback(private val ctx: Context) : MediaLibrarySession.Callback {

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val rootMetadata = MediaMetadata.Builder()
            .setTitle(ctx.getString(R.string.app_name))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        val root = MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(rootMetadata)
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(root, params))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (parentId != ROOT_ID) {
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }
        val items = CHANNELS.map { channel ->
            MediaItem.Builder().withChannel(channel, ctx).build()
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val channel = channelById(mediaId)
            ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        val item = MediaItem.Builder().withChannel(channel, ctx).build()
        return Futures.immediateFuture(LibraryResult.ofItem(item, null))
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
 *
 * Artwork: Google TV's Backdrop ambient (and its tabletop "Now Playing"
 * card) only renders for MediaSessions that publish a bitmap — text-only
 * sessions get filtered out. We synthesize a channel-name badge per
 * channel on first use. 256px PNG keeps the IPC payload small enough
 * to survive the binder when MediaController forwards the MediaItem to
 * the service.
 */
fun MediaItem.Builder.withChannel(channel: Channel, ctx: Context): MediaItem.Builder {
    val name = ctx.getString(channel.nameRes)
    val md = MediaMetadata.Builder()
        .setTitle(name)
        .setArtist(name)
        .setAlbumTitle(ctx.getString(R.string.app_name))
        .setArtworkData(channelArtwork(name), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setExtras(Bundle().apply { putString(EXTRA_CHANNEL_ID, channel.id) })
        .build()
    return setMediaId(channel.id)
        .setUri(channel.streamUrl)
        .setMediaMetadata(md)
}

private const val ARTWORK_SIZE = 256
private const val RRO_RED = 0xFFE3000B.toInt()
private val artworkCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

private fun channelArtwork(channelName: String): ByteArray =
    artworkCache.getOrPut(channelName) { generateArtworkPng(channelName) }

private fun generateArtworkPng(channelName: String): ByteArray {
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
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    return out.toByteArray()
}
