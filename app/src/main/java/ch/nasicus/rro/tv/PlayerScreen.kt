package ch.nasicus.rro.tv

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
    onToggle: (Channel) -> Unit,
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
        // Autofocus the currently playing / selected channel, falling back to
        // the first card on a cold start.
        val currentIndex = CHANNELS.indexOfFirst { it.id == state.currentChannelId }
            .coerceAtLeast(0)
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(currentIndex) { runCatching { focusRequester.requestFocus() } }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
                .padding(48.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                NowPlayingHeader(state)
                ChannelRow(state, onToggle, focusRequester, currentIndex)
            }
        }
    }
}

@Composable
private fun NowPlayingHeader(state: PlayerUiState) {
    val ctx = LocalContext.current
    val channel = channelById(state.currentChannelId)
    val shortName = channel?.let { ctx.getString(it.nameRes) } ?: stringResource(R.string.idle)
    val headerName = channel?.longNameRes?.let { ctx.getString(it) } ?: shortName
    val song = state.title.takeIf { it.isNotBlank() && it != shortName && it != headerName }.orEmpty()
    val artist = state.artist.takeIf { it.isNotBlank() && it != shortName && it != headerName }.orEmpty()

    Column {
        Image(
            painter = painterResource(R.drawable.rro_wordmark),
            contentDescription = stringResource(R.string.app_name),
            colorFilter = ColorFilter.tint(Red),
            modifier = Modifier.height(32.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = headerName,
            color = OnSurface,
            fontSize = if (headerName.length > 15) 44.sp else 56.sp,
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
    onToggle: (Channel) -> Unit,
    focusRequester: FocusRequester,
    focusedIndex: Int,
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
                val isCurrent = ch.id == state.currentChannelId
                ChannelCard(
                    channel = ch,
                    isCurrent = isCurrent,
                    isPlaying = isCurrent && state.isPlaying,
                    isBuffering = isCurrent && state.isBuffering,
                    onClick = { onToggle(ch) },
                    modifier = if (i == focusedIndex) Modifier.focusRequester(focusRequester) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: Channel,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = when {
        isPlaying -> Red
        isCurrent -> RedDark
        else -> Color.Transparent
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .size(width = 220.dp, height = 150.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(3.dp, border, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Surface,
            contentColor = OnSurface,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(channel.nameRes),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = when {
                    isPlaying -> Red
                    isCurrent -> OnSurface
                    else -> OnSurfaceMuted
                },
                modifier = Modifier.size(28.dp),
            )
            if (isBuffering) {
                Text("…", color = OnSurfaceMuted, fontSize = 14.sp)
            }
        }
    }
}
