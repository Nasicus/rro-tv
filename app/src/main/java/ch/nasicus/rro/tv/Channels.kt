package ch.nasicus.rro.tv

import androidx.annotation.StringRes

data class Channel(
    val id: String,
    @StringRes val nameRes: Int,
    val streamUrl: String,
)

val CHANNELS = listOf(
    Channel("rro", R.string.ch_rro, "https://streaming.rro.ch/rro/mp3_128"),
    Channel("swissmelody", R.string.ch_swissmelody, "https://streaming.rro.ch/rro_swissmelody/mp3_128"),
    Channel("musigpur", R.string.ch_muesigpur, "https://streaming.rro.ch/rro_musigpur/mp3_128"),
)

fun channelById(id: String?): Channel? = CHANNELS.firstOrNull { it.id == id }
