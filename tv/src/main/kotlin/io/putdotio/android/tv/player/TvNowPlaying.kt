package io.putdotio.android.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PutioFileType

internal const val TV_NOW_PLAYING_TAG = "tv-now-playing"
internal const val TV_NOW_PLAYING_ARTWORK_TAG = "tv-now-playing-artwork"

internal fun TvPlayerOverlay.skipTrack(forward: Boolean): TvPlayerTransition {
    if (exited || !nowPlaying) return TvPlayerTransition(this)
    val resume = if (scrub?.wasPlaying == true) listOf(TvPlayerCommand.Play) else emptyList()
    val skip = if (forward) TvPlayerCommand.PlayFollowing else TvPlayerCommand.PlayPrevious
    return TvPlayerTransition(copy(scrub = null, activity = activity + 1), resume + skip)
}

internal fun TvPlayerOverlay.queueEnded(next: PlaybackTarget?): TvPlayerTransition =
    when {
        exited -> TvPlayerTransition(this)
        next == null -> exit()
        else -> TvPlayerTransition(
            copy(exited = true, scrub = null, sheet = null),
            listOf(TvPlayerCommand.PlayChosen(next)),
        )
    }

internal val PlaybackNeighbours?.hasNextTrack: Boolean get() = this?.next != null

internal fun TvPlayerOverlay.focusesNextTrack(neighbours: PlaybackNeighbours?): Boolean =
    !nowPlaying || neighbours.hasNextTrack

internal class TvNowPlayingState(
    val title: String,
    val neighbours: PlaybackNeighbours?,
    val paused: Boolean,
    val scrubTargetMillis: Long?,
    val startPositionMillis: Long,
    val listingDurationMillis: Long?,
)

@Composable
internal fun TvNowPlaying(
    player: Player,
    state: TvNowPlayingState,
    chips: List<TvPlayerControl>,
    chipLabels: TvPlayerChipLabels,
    focused: TvPlayerControl,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .tvPlayerEdges()
            .testTag(TV_NOW_PLAYING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ARTWORK_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvNowPlayingArtwork(rememberArtwork(player))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = PutioTypeScale.tvTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                state.neighbours?.folderName?.let { folder ->
                    Text(
                        text = folder,
                        style = PutioTypeScale.tvBody,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TvNowPlayingProgress(player, state, seekBarFocused = focused == TvPlayerControl.SeekBar)
                TvNowPlayingTransport(state, focused)
                TvPlayerChips(chips = chips, labels = chipLabels, focused = focused)
            }
        }
    }
}

@Composable
private fun TvNowPlayingProgress(player: Player, state: TvNowPlayingState, seekBarFocused: Boolean) {
    val progress = rememberPlayerProgress(player, state.paused, state.startPositionMillis)
    val shownMillis = state.scrubTargetMillis ?: progress.positionMillis
    val duration = knownDurationMillis(progress.durationMillis, state.listingDurationMillis)
    val elapsed = playerTime(shownMillis)
    TvSeekBar(
        fraction = duration?.let { (shownMillis.toFloat() / it).coerceIn(0f, 1f) } ?: 0f,
        description = duration?.let {
            stringResource(R.string.tv_player_seek_bar, elapsed, playerTime(it))
        } ?: elapsed,
        scrubbing = state.scrubTargetMillis != null,
        focused = seekBarFocused,
        modifier = Modifier.padding(top = SEEK_TOP),
    )
    Box(Modifier.padding(top = 10.dp)) {
        TvPlayerTimes(elapsed = elapsed, remaining = duration?.let(::playerTime))
    }
}

@Composable
private fun TvNowPlayingTransport(state: TvNowPlayingState, focused: TvPlayerControl) {
    Row(
        modifier = Modifier.padding(vertical = TRANSPORT_PADDING),
        horizontalArrangement = Arrangement.spacedBy(TRANSPORT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvPlayerCircle(
            icon = R.drawable.ic_ph_skip_back_fill,
            description = stringResource(R.string.tv_player_previous_track),
            focused = focused == TvPlayerControl.Previous,
            size = SKIP_CIRCLE,
            restAlpha = REST_ALPHA,
        )
        TvPlayerCircle(
            icon = if (state.paused) DesignR.drawable.ic_ph_play_fill else DesignR.drawable.ic_ph_pause_fill,
            description = stringResource(if (state.paused) R.string.tv_player_play else R.string.tv_player_pause),
            focused = focused == TvPlayerControl.PlayPause,
            size = PLAY_CIRCLE,
            restAlpha = REST_ALPHA,
        )
        TvPlayerCircle(
            icon = R.drawable.ic_ph_skip_forward_fill,
            description = stringResource(R.string.tv_player_next_track),
            focused = focused == TvPlayerControl.Next,
            size = SKIP_CIRCLE,
            restAlpha = REST_ALPHA,
            enabled = state.neighbours.hasNextTrack,
        )
    }
}

@Composable
private fun TvNowPlayingArtwork(artwork: ByteArray?) {
    Box(
        modifier = Modifier
            .size(ARTWORK)
            .clip(RoundedCornerShape(PutioRadius.large))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(TV_NOW_PLAYING_ARTWORK_TAG),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork == null) {
            Icon(
                painter = painterResource(fileTypeIconRes(PutioFileType.AUDIO)),
                contentDescription = null,
                tint = PutioDesignTokens.yellowSolid,
                modifier = Modifier.size(ARTWORK_GLYPH),
            )
        } else {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun rememberArtwork(player: Player): ByteArray? {
    var artwork by remember(player) { mutableStateOf(player.mediaMetadata.artworkData) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                artwork = mediaMetadata.artworkData
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return artwork
}

private const val REST_ALPHA = 0.6f
private val ARTWORK = 240.dp
private val ARTWORK_GLYPH = 96.dp
private val ARTWORK_GAP = 40.dp
private val SEEK_TOP = 28.dp
private val TRANSPORT_PADDING = 20.dp
private val TRANSPORT_GAP = 32.dp
private val SKIP_CIRCLE = 56.dp
private val PLAY_CIRCLE = 72.dp
