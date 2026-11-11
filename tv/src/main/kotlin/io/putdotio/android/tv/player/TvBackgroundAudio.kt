package io.putdotio.android.tv.player

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import io.putdotio.android.playback.PlaybackMediaType
import java.io.Closeable

internal data class TvBackgroundAudio(
    val hidden: Boolean,
    val onBackground: (() -> Unit)?,
    val miniPlayer: TvMiniPlayerState?,
) {
    fun keepsPlaying(mediaType: PlaybackMediaType): Boolean =
        mediaType == PlaybackMediaType.AUDIO && onBackground != null
}

@Composable
internal fun BindMiniPlayer(mini: TvMiniPlayerState?, player: Player, paused: Boolean, setPlaying: (Boolean) -> Unit) {
    if (mini == null) return
    val currentSetPlaying by rememberUpdatedState(setPlaying)
    SideEffect { mini.show(paused) }
    DisposableEffect(mini, player) {
        val control: (Boolean) -> Unit = { play -> currentSetPlaying(play) }
        mini.bind(control)
        onDispose { mini.unbind(control) }
    }
}

internal fun leaveForeground(
    overlay: () -> TvPlayerOverlay,
    apply: (TvPlayerTransition) -> Unit,
    keepsPlaying: Boolean,
) {
    apply(overlay().cancelUpNext())
    if (!keepsPlaying) apply(overlay().setPlaying(play = false))
    apply(overlay().reveal())
}

internal fun Lifecycle.sessionPlayer(
    player: Player,
    keepsPlaying: Boolean,
    onSkip: ((Boolean) -> Unit)?,
    onPlaying: (Boolean) -> Unit,
): Player = tvSessionPlayer(player, onSkipRequested = onSkip?.let(::whileStarted)) { play ->
    if (!play || keepsPlaying || currentState.isAtLeast(Lifecycle.State.STARTED)) onPlaying(play)
}

internal fun TvPlayerFactory.publishSession(context: Context, player: Player, inBackground: Boolean): Closeable =
    if (inBackground) publishInBackground(context, player) else publish(context, player)

internal fun TvPlayerOverlay.holdsFocusWhenShown(hidden: Boolean): Boolean = !hidden && holdsPlayerFocus
