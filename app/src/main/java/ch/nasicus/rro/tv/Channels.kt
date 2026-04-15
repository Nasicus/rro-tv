package ch.nasicus.rro.tv

import androidx.annotation.StringRes

data class Channel(
    val id: String,
    @StringRes val nameRes: Int,
    val streamUrl: String,
    /**
     * UUID used by RRO's public GraphQL playlist API
     * (https://rro.playlist-api.deliver.media/graphql) to identify the
     * channel. Lets us resolve "what's playing now" — including for talk
     * shows where the audio stream's ICY metadata is empty.
     */
    val playlistApiId: String,
)

val CHANNELS = listOf(
    Channel(
        id = "rro",
        nameRes = R.string.ch_rro,
        streamUrl = "https://streaming.rro.ch/rro/mp3_128",
        playlistApiId = "019970c7-1127-7288-a49c-a25accbfc2ee",
    ),
    Channel(
        id = "swissmelody",
        nameRes = R.string.ch_swissmelody,
        streamUrl = "https://streaming.rro.ch/rro_swissmelody/mp3_128",
        playlistApiId = "019970c7-2cf7-7f2f-9af1-3fa3e477c97b",
    ),
    Channel(
        id = "musigpur",
        nameRes = R.string.ch_muesigpur,
        streamUrl = "https://streaming.rro.ch/rro_musigpur/mp3_128",
        // API name "Hitbox" — same channel content as Müsig Pur.
        playlistApiId = "019970c7-7360-769f-83ec-a6d5c05bbe00",
    ),
)

fun channelById(id: String?): Channel? = CHANNELS.firstOrNull { it.id == id }
