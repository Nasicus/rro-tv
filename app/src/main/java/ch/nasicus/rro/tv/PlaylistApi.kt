package ch.nasicus.rro.tv

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Tiny GraphQL client for RRO's public playlist API. The endpoint is open
 * (no auth, introspection enabled) and used by the pomona.ch web player
 * to render now-playing info — much richer than ICY (separate artist/title,
 * works for talk shows, gives `start_time + duration_ms` so we can schedule
 * the next poll exactly when the song ends instead of guessing).
 */
class PlaylistApi {

    suspend fun fetchPlayingNow(channelApiId: String): NowPlaying? = withContext(Dispatchers.IO) {
        val payload = """
            {"query":"query(${'$'}id:String!){channel(id:${'$'}id){playingnow{current{metadata{artist title TitleDisplay} start_time duration_ms status}}}}","variables":{"id":"$channelApiId"}}
        """.trimIndent()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(body)
        } catch (e: Exception) {
            Log.w(TAG, "fetchPlayingNow($channelApiId) failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): NowPlaying? {
        val current = JSONObject(json)
            .optJSONObject("data")
            ?.optJSONObject("channel")
            ?.optJSONObject("playingnow")
            ?.optJSONObject("current") ?: return null
        val md = current.optJSONObject("metadata")
        val title = md?.optStringOrNull("title")
        val artist = md?.optStringOrNull("artist")
        if (title.isNullOrBlank() && artist.isNullOrBlank()) return null
        return NowPlaying(
            artist = artist,
            title = title,
            startTimeMs = current.optStringOrNull("start_time")
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            durationMs = current.optInt("duration_ms", 0).takeIf { it > 0 }?.toLong(),
            status = current.optStringOrNull("status"),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() && it != "null" }

    companion object {
        private const val TAG = "RroPlaylistApi"
        private const val ENDPOINT = "https://rro.playlist-api.deliver.media/graphql"
    }
}

data class NowPlaying(
    val artist: String?,
    val title: String?,
    val startTimeMs: Long?,
    val durationMs: Long?,
    val status: String?,
) {
    /** Milliseconds until the current track ends, or null if we can't tell. */
    fun millisUntilEnd(now: Long = System.currentTimeMillis()): Long? {
        val start = startTimeMs ?: return null
        val dur = durationMs ?: return null
        return (start + dur - now).coerceAtLeast(0)
    }

    /**
     * The API keeps returning the last song's metadata while news / talk /
     * ads are on the air. Treat it as stale once we're past the expected
     * end by more than 10% of the duration (with a 10s floor for very short
     * tracks) — at that point we'd rather show just the channel name than
     * lie about a song that finished minutes ago.
     */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean {
        val start = startTimeMs ?: return false
        val dur = durationMs ?: return false
        val tolerance = (dur / 10).coerceAtLeast(10_000L)
        return now > start + dur + tolerance
    }
}
