package ch.nasicus.rro.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Red = Color(0xFFE3000B)
private val RedDark = Color(0xFFA30008)
private val BgDark = Color(0xFF0E0E10)
private val Surface = Color(0xFF1A1A1D)
private val OnSurface = Color(0xFFF2F2F2)
private val OnSurfaceMuted = Color(0xFFA0A0A0)

@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onSelect: (Channel) -> Unit,
    onTogglePlayPause: () -> Unit,
) {
    val colors = darkColorScheme(
        primary = Red,
        onPrimary = Color.White,
        secondary = RedDark,
        background = BgDark,
        surface = Surface,
        onBackground = OnSurface,
        onSurface = OnSurface,
    )
    MaterialTheme(colorScheme = colors) {
        val firstChannelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { firstChannelFocus.requestFocus() } }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(48.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                NowPlayingHeader(state)
                ChannelRow(state, onSelect, firstChannelFocus)
                TransportRow(state, onTogglePlayPause)
            }
        }
    }
}

@Composable
private fun NowPlayingHeader(state: PlayerUiState) {
    val ctx = LocalContext.current
    val channel = channelById(state.currentChannelId)
    val channelName = channel?.let { ctx.getString(it.nameRes) } ?: stringResource(R.string.idle)
    val song = state.title.takeIf { it.isNotBlank() && it != channelName }.orEmpty()
    val artist = state.artist.takeIf { it.isNotBlank() && it != channelName }.orEmpty()

    Column {
        Text(
            text = stringResource(R.string.app_name),
            color = Red,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = channelName,
            color = OnSurface,
            fontSize = 56.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(8.dp))
        when {
            state.isBuffering -> {
                Text("…", color = OnSurfaceMuted, fontSize = 22.sp)
            }
            song.isNotBlank() -> {
                Text(song, color = OnSurface, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                if (artist.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(artist, color = OnSurfaceMuted, fontSize = 20.sp)
                }
            }
            state.isPlaying -> {
                Text(stringResource(R.string.now_playing), color = OnSurfaceMuted, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun ChannelRow(
    state: PlayerUiState,
    onSelect: (Channel) -> Unit,
    firstFocus: FocusRequester,
) {
    Column {
        Text(
            text = stringResource(R.string.select_channel),
            color = OnSurfaceMuted,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CHANNELS.forEachIndexed { i, ch ->
                ChannelCard(
                    channel = ch,
                    selected = ch.id == state.currentChannelId,
                    isPlaying = state.isPlaying && ch.id == state.currentChannelId,
                    onClick = { onSelect(ch) },
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: Channel,
    selected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = when {
        isPlaying -> Red
        selected -> RedDark
        else -> Color.Transparent
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .size(width = 220.dp, height = 130.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(3.dp, border, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Surface,
            contentColor = OnSurface,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(channel.nameRes),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (isPlaying) {
                Spacer(Modifier.height(6.dp))
                Text("●", color = Red, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun TransportRow(state: PlayerUiState, onTogglePlayPause: () -> Unit) {
    val enabled = state.currentChannelId != null
    Button(
        onClick = onTogglePlayPause,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Red,
            contentColor = OnSurface,
            disabledContainerColor = Surface,
            disabledContentColor = OnSurfaceMuted,
        ),
        modifier = Modifier.height(56.dp),
    ) {
        Icon(
            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (state.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
