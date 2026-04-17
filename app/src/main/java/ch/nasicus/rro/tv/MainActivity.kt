package ch.nasicus.rro.tv

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {

    private lateinit var browser: MediaBrowserCompat
    private var controller: MediaControllerCompat? = null
    private val state = MutableStateFlow(PlayerUiState())

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val c = MediaControllerCompat(this@MainActivity, browser.sessionToken)
            c.registerCallback(controllerCallback)
            controller = c
            MediaControllerCompat.setMediaController(this@MainActivity, c)
            refreshState()

            val s = c.playbackState?.state
            val active = s == PlaybackStateCompat.STATE_PLAYING ||
                s == PlaybackStateCompat.STATE_BUFFERING ||
                s == PlaybackStateCompat.STATE_CONNECTING
            if (!active) {
                val currentId = c.metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
                if (currentId == null) {
                    Log.d(TAG, "auto-starting ${CHANNELS.first().id}")
                    c.transportControls.playFromMediaId(CHANNELS.first().id, null)
                } else {
                    Log.d(TAG, "auto-resuming $currentId")
                    c.transportControls.play()
                }
            }
        }

        override fun onConnectionFailed() { Log.w(TAG, "MediaBrowser connection failed") }
        override fun onConnectionSuspended() { Log.w(TAG, "MediaBrowser connection suspended") }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = refreshState()
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ui by state.asStateFlow().collectAsState()
            PlayerScreen(state = ui, onToggle = ::toggle)
        }
        browser = MediaBrowserCompat(
            this,
            ComponentName(this, PlaybackService::class.java),
            connectionCallback,
            null,
        )
    }

    override fun onStart() {
        super.onStart()
        browser.connect()
    }

    override fun onStop() {
        super.onStop()
        controller?.unregisterCallback(controllerCallback)
        controller = null
        browser.disconnect()
    }

    private fun toggle(channel: Channel) {
        val c = controller ?: return
        val isPlaying = c.playbackState?.state == PlaybackStateCompat.STATE_PLAYING
        val currentId = c.metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        if (isPlaying && currentId == channel.id) {
            c.transportControls.pause()
        } else {
            c.transportControls.playFromMediaId(channel.id, null)
        }
    }

    private fun refreshState() {
        val c = controller ?: return
        val md = c.metadata
        val s = c.playbackState?.state
        state.update {
            PlayerUiState(
                currentChannelId = md?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID),
                isPlaying = s == PlaybackStateCompat.STATE_PLAYING,
                isBuffering = s == PlaybackStateCompat.STATE_BUFFERING ||
                    s == PlaybackStateCompat.STATE_CONNECTING,
                title = md?.getString(MediaMetadataCompat.METADATA_KEY_TITLE).orEmpty(),
                artist = md?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty(),
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
