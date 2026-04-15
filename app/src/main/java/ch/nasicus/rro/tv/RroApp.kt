package ch.nasicus.rro.tv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService

/**
 * Pre-creates the media playback notification channel at IMPORTANCE_DEFAULT
 * before Media3's MediaSessionService lazily creates it at IMPORTANCE_LOW.
 * The channel ID must match Media3's DefaultMediaNotificationProvider
 * constant "default_channel_id" — once a channel exists Android locks its
 * importance, so pre-creating here wins. Higher importance appears to be
 * one requirement for Google TV's ambient now-playing card to surface.
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
