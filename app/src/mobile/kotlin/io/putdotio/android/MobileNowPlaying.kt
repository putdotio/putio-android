package io.putdotio.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player as Media3Player
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.files.FilesItemId
import kotlinx.coroutines.delay

internal const val MOBILE_NOW_PLAYING_TAG = "mobile-now-playing"
internal const val MOBILE_NOW_PLAYING_TOGGLE_TAG = "mobile-now-playing-toggle"
internal const val MOBILE_NOW_PLAYING_DISMISS_TAG = "mobile-now-playing-dismiss"
internal const val MOBILE_NOW_PLAYING_OPEN_TAG = "mobile-now-playing-open"
private const val NOW_PLAYING_PROGRESS_TICK_MILLIS = 1_000L

/** What the audio session holds, read from the session player. */
internal data class NowPlaying(
    val fileId: FilesItemId,
    val title: String,
    val isPlaying: Boolean,
    val progress: Float?,
)

// The bar shows only prepared audio. A cleared or ended session leaves nothing to resume.
internal fun Media3Player.nowPlayingOrNull(): NowPlaying? {
    val fileId = activeSessionFileId() ?: return null
    if (playbackState == Media3Player.STATE_ENDED) return null
    val item = currentMediaItem ?: return null
    val durationMillis = duration.takeIf { it > 0L }
    return NowPlaying(
        fileId = FilesItemId(fileId),
        title = item.mediaMetadata.title?.toString().orEmpty().ifBlank { fileId.toString() },
        isPlaying = playWhenReady && playbackState != Media3Player.STATE_IDLE,
        progress = durationMillis?.let { (currentPosition.toFloat() / it).coerceIn(0f, 1f) },
    )
}

/**
 * Attaches to the audio session while the shell is visible and republishes what it
 * plays. A failed connection means nothing is playing; the bar simply stays hidden.
 */
@Composable
internal fun rememberNowPlaying(playerFactory: MobilePlayerFactory): NowPlayingHandle {
    val context = LocalContext.current
    var player by remember(playerFactory) { mutableStateOf<Media3Player?>(null) }
    var nowPlaying by remember(playerFactory) { mutableStateOf<NowPlaying?>(null) }
    DisposableEffect(context, playerFactory) {
        // A cancelled connection still answers; a late answer must not revive a disposed observer.
        var disposed = false
        val handle = playerFactory.connectAudio(context) { result ->
            if (!disposed) player = result.getOrNull()
        }
        onDispose {
            disposed = true
            handle.closeQuietly()
            player = null
            nowPlaying = null
        }
    }
    val attached = player
    DisposableEffect(attached) {
        if (attached == null) return@DisposableEffect onDispose {}
        nowPlaying = attached.nowPlayingOrNull()
        val listener =
            object : Media3Player.Listener {
                override fun onEvents(player: Media3Player, events: Media3Player.Events) {
                    nowPlaying = player.nowPlayingOrNull()
                }
            }
        attached.addListener(listener)
        onDispose { attached.removeListener(listener) }
    }
    // The composition survives a stopped Activity; the ticker must not.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(attached, nowPlaying?.isPlaying, lifecycle) {
        val live = attached ?: return@LaunchedEffect
        if (nowPlaying?.isPlaying != true) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            nowPlaying = live.nowPlayingOrNull()
            while (nowPlaying?.isPlaying == true) {
                delay(NOW_PLAYING_PROGRESS_TICK_MILLIS)
                nowPlaying = live.nowPlayingOrNull()
            }
        }
    }
    return remember(attached, nowPlaying) { NowPlayingHandle(nowPlaying, attached) }
}

internal class NowPlayingHandle(
    val nowPlaying: NowPlaying?,
    private val player: Media3Player?,
) {
    fun togglePlayback() {
        val live = player ?: return
        if (live.playWhenReady) live.pause() else live.play()
    }

    // Stopping drops the item and its position; the file's server-side start_from still applies next time.
    fun dismiss() {
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
    }
}

@Composable
internal fun MobileNowPlayingBar(
    nowPlaying: NowPlaying,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.testTag(MOBILE_NOW_PLAYING_TAG),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MOBILE_NOW_PLAYING_OPEN_TAG)
                    .clickable(
                        onClickLabel = stringResource(R.string.mobile_now_playing_open),
                        role = Role.Button,
                        onClick = onOpen,
                    )
                    .padding(start = 16.dp, end = 4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_file_audio_fill),
                    contentDescription = null,
                    tint = PutioDesignTokens.yellowSolid,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = nowPlaying.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.testTag(MOBILE_NOW_PLAYING_TOGGLE_TAG),
                ) {
                    Icon(
                        painter = painterResource(
                            if (nowPlaying.isPlaying) R.drawable.ic_ph_pause_fill else R.drawable.ic_ph_play_fill,
                        ),
                        contentDescription = stringResource(
                            if (nowPlaying.isPlaying) R.string.mobile_now_playing_pause else R.string.mobile_now_playing_play,
                        ),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(MOBILE_NOW_PLAYING_DISMISS_TAG),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ph_x),
                        contentDescription = stringResource(R.string.mobile_now_playing_dismiss),
                    )
                }
            }
            nowPlaying.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
