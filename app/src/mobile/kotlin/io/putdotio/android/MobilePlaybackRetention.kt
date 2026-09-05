package io.putdotio.android

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player as Media3Player

@Stable
internal class RetainedPlayerPreferences(
    resumeAfterLifecyclePause: Boolean = true,
    subtitleSelection: SubtitleSelection? = null,
    positionMillis: Long? = null,
) {
    var resumeAfterLifecyclePause by mutableStateOf(resumeAfterLifecyclePause)
    var subtitleSelection by mutableStateOf(subtitleSelection)
    var positionMillis by mutableStateOf(positionMillis)

    fun retainPlayback(playback: RetainedPlayback) {
        positionMillis = playback.positionMillis
        resumeAfterLifecyclePause = playback.resumeAfterLifecyclePause
    }

    fun retainPosition(positionMillis: Long) {
        this.positionMillis = positionMillis.coerceAtLeast(0L)
    }
}

private val RetainedPlayerPreferencesSaver =
    Saver<RetainedPlayerPreferences, Bundle>(
        save = { preferences ->
            Bundle().apply {
                putBoolean("resumeAfterLifecyclePause", preferences.resumeAfterLifecyclePause)
                preferences.subtitleSelection?.let { putBundle("subtitleSelection", it.toBundle()) }
                preferences.positionMillis?.let { putLong("positionMillis", it) }
            }
        },
        restore = Bundle::toRetainedPlayerPreferences,
    )

internal fun Bundle.toRetainedPlayerPreferences(): RetainedPlayerPreferences =
    RetainedPlayerPreferences(
        resumeAfterLifecyclePause = getBoolean("resumeAfterLifecyclePause", true),
        subtitleSelection = getBundle("subtitleSelection")?.toSubtitleSelection(),
        positionMillis = getLong("positionMillis").takeIf { containsKey("positionMillis") },
    )

@Composable
internal fun rememberRetainedPlayerPreferences(fileId: Long): RetainedPlayerPreferences =
    rememberSaveable(fileId, saver = RetainedPlayerPreferencesSaver) {
        RetainedPlayerPreferences()
    }

internal fun lifecycleAllowsAutoplay(
    state: Lifecycle.State,
    resumeAfterLifecyclePause: Boolean = true,
): Boolean = resumeAfterLifecyclePause && state.isAtLeast(Lifecycle.State.RESUMED)

internal fun Media3Player.shouldKeepScreenOn(): Boolean =
    playbackKeepsScreenOn(playWhenReady, playbackState, playbackSuppressionReason)

internal fun playbackKeepsScreenOn(
    playWhenReady: Boolean,
    playbackState: Int,
    playbackSuppressionReason: Int = Media3Player.PLAYBACK_SUPPRESSION_REASON_NONE,
): Boolean =
    playWhenReady &&
        playbackSuppressionReason == Media3Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
        playbackState != Media3Player.STATE_IDLE &&
        playbackState != Media3Player.STATE_ENDED

internal data class RetainedPlayback(
    val positionMillis: Long,
    val resumeAfterLifecyclePause: Boolean,
)

internal fun retainPlaybackOnPause(
    positionMillis: Long,
    playWhenReady: Boolean,
): RetainedPlayback =
    RetainedPlayback(
        positionMillis = positionMillis.coerceAtLeast(0L),
        resumeAfterLifecyclePause = playWhenReady,
    )

internal enum class PlayerRetentionEvent {
    LifecyclePause,
    PlayerError,
    PlayerDisposed,
    PlayIntentChanged,
}

internal sealed interface PlayerRetentionUpdate {
    val positionMillis: Long

    data class Playback(val retained: RetainedPlayback) : PlayerRetentionUpdate {
        override val positionMillis: Long = retained.positionMillis
    }

    data class Position(override val positionMillis: Long) : PlayerRetentionUpdate
}

internal fun playerRetentionUpdate(
    event: PlayerRetentionEvent,
    lifecycleState: Lifecycle.State,
    positionMillis: Long,
    playWhenReady: Boolean,
): PlayerRetentionUpdate {
    val retained = retainPlaybackOnPause(positionMillis, playWhenReady)
    return if (event == PlayerRetentionEvent.LifecyclePause ||
        lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    ) {
        PlayerRetentionUpdate.Playback(retained)
    } else {
        PlayerRetentionUpdate.Position(retained.positionMillis)
    }
}

internal fun PlayerRetentionUpdate.dispatch(
    onPlaybackRetained: (RetainedPlayback) -> Unit,
    onPositionChanged: (Long) -> Unit,
) {
    when (this) {
        is PlayerRetentionUpdate.Playback -> onPlaybackRetained(retained)
        is PlayerRetentionUpdate.Position -> onPositionChanged(positionMillis)
    }
}

internal fun replacementPositionMillis(
    activeFileId: Long?,
    replacementFileId: Long,
    livePositionMillis: Long,
    preparedPositionMillis: Long,
): Long =
    if (activeFileId == replacementFileId) {
        livePositionMillis.coerceAtLeast(0L)
    } else {
        preparedPositionMillis.coerceAtLeast(0L)
    }

internal fun retainedPositionOnDispose(
    failurePositionMillis: Long?,
    livePositionMillis: Long,
): Long = failurePositionMillis ?: livePositionMillis.coerceAtLeast(0L)
