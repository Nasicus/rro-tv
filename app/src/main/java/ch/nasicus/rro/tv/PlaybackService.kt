package ch.nasicus.rro.tv

import android.content.Context
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
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

        val player = ExoPlayer.Builder(this)
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

const val EXTRA_CHANNEL_ID = "channel_id"

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
