package ch.nasicus.rro.tv

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the ExoPlayer + MediaSession. Lives independently of the activity so
 * audio keeps playing once Google TV's ambient screensaver kicks in. The
 * active MediaSession is what the system "now playing" overlay reads, so any
 * ICY metadata ExoPlayer extracts from the stream surfaces there for free.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Icecast servers only emit StreamTitle chunks if the client opts in
        // via the `Icy-MetaData: 1` request header. ExoPlayer's default data
        // source doesn't set it, so without this the now-playing overlay only
        // ever sees the channel name we hand-set on the MediaItem.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("RroTv/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
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
            .apply { addListener(IcyTitleApplier(this)) }

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
 * Reads ICY `StreamTitle` from the live stream and rewrites the current
 * MediaItem's metadata so the system now-playing overlay shows
 * "<song> · <channel>". When the broadcaster pushes an empty StreamTitle
 * (between songs / commercials), we keep the channel name so the overlay
 * never goes blank.
 */
private class IcyTitleApplier(private val player: Player) : Player.Listener {

    override fun onMetadata(metadata: Metadata) {
        val icyTitle = (0 until metadata.length())
            .map { metadata.get(it) }
            .filterIsInstance<IcyInfo>()
            .firstNotNullOfOrNull { it.title?.takeIf(String::isNotBlank) }
            ?: return
        Log.d(TAG, "ICY title=$icyTitle")
        applyTitle(icyTitle)
    }

    private fun applyTitle(songTitle: String) {
        val item = player.currentMediaItem ?: return
        val channelId = item.mediaMetadata.extras?.getString(EXTRA_CHANNEL_ID) ?: return
        if (item.mediaMetadata.title?.toString() == songTitle) return

        val newMd = item.mediaMetadata.buildUpon()
            .setTitle(songTitle)
            .setExtras(Bundle().apply { putString(EXTRA_CHANNEL_ID, channelId) })
            .build()
        val newItem = item.buildUpon().setMediaMetadata(newMd).build()
        player.replaceMediaItem(player.currentMediaItemIndex, newItem)
    }
}

/**
 * Builds the MediaItem for a channel. ExoPlayer merges ICY stream metadata
 * over this MediaMetadata at runtime: ICY usually sends only `title`
 * (= "Artist - Song"), so we keep `artist` set to the channel name — that
 * field survives the merge and shows up next to the song in the system
 * now-playing overlay.
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
