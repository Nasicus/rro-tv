package ch.nasicus.rro.tv

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {

    private var controller: MediaController? = null
    private val state = MutableStateFlow(PlayerUiState())

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refreshState()
        override fun onMediaMetadataChanged(metadata: MediaMetadata) = refreshState()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refreshState()
        override fun onPlaybackStateChanged(playbackState: Int) = refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ui by state.asStateFlow().collectAsState()
            PlayerScreen(state = ui, onToggle = ::toggle)
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        // Main executor: MediaController methods must be called on the looper
        // it was built with (the activity's main thread here). With a direct
        // executor the completion callback runs on the binder thread and
        // setMediaItem silently no-ops.
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: run {
                Log.w(TAG, "MediaController build failed")
                return@addListener
            }
            controller = c
            c.addListener(playerListener)
            refreshState()
            // Opening the app should equal "radio on". If something is
            // already playing (e.g. user backgrounded us mid-song and came
            // back) we leave it alone. Otherwise resume the currently-loaded
            // channel, or fall back to RRO on a cold start.
            if (!c.isPlaying) {
                if (c.mediaItemCount == 0) {
                    Log.d(TAG, "auto-starting ${CHANNELS.first().id}")
                    play(CHANNELS.first())
                } else {
                    Log.d(TAG, "auto-resuming ${c.currentMediaItem?.mediaId}")
                    c.play()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
    }

    private fun play(channel: Channel) {
        val c = controller ?: return
        val current = c.currentMediaItem?.mediaId
        if (current != channel.id) {
            c.setMediaItem(MediaItem.Builder().withChannel(channel, this).build())
            c.prepare()
        }
        c.play()
    }

    /**
     * Single-button UX: clicking the currently playing channel pauses it;
     * clicking anything else switches to that channel and plays.
     */
    private fun toggle(channel: Channel) {
        val c = controller ?: return
        if (c.isPlaying && c.currentMediaItem?.mediaId == channel.id) {
            c.pause()
        } else {
            play(channel)
        }
    }

    private fun refreshState() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId
        val md = c.mediaMetadata
        state.update {
            PlayerUiState(
                currentChannelId = id,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                title = md.title?.toString().orEmpty(),
                artist = md.artist?.toString().orEmpty(),
            )
        }
    }
}

data class PlayerUiState(
    val currentChannelId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val title: String = "",
    val artist: String = "",
)

private const val TAG = "RroActivity"
