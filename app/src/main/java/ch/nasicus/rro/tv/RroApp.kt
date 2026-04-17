package ch.nasicus.rro.tv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService

/**
 * Creates the media playback notification channel. Shared with
 * PlaybackService's foreground notification (id "default_channel_id").
 * IMPORTANCE_DEFAULT keeps the notification visible in the system tray
 * without making noise.
 */
class RroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            MEDIA_CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Playback controls"
            setShowBadge(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        // Must match androidx.media3 DefaultMediaNotificationProvider's id.
        private const val MEDIA_CHANNEL_ID = "default_channel_id"
    }
}
