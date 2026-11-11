package io.putdotio.android.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import io.putdotio.android.R
import io.putdotio.android.playback.AudioSelection
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SubtitleSelection
import io.putdotio.android.playback.withSubtitleSelection
import io.putdotio.android.tv.TvSnackbarMessage
import io.putdotio.android.tv.TvSnackbarState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSubtitles

internal sealed interface TvTrackRecoveryStep {
    data class DropSubtitles(val uris: Set<String>) : TvTrackRecoveryStep

    data class RestoreAudio(val switch: TvAudioSwitch) : TvTrackRecoveryStep
}

internal class TvAudioSwitch(val parameters: TrackSelectionParameters, val selection: AudioSelection)

internal class TvTrackRecovery(private val sidecarUris: Set<String>) {
    private val failedSubtitles = mutableSetOf<String>()
    private var subtitleFailedAtMillis: Long? = null
    private var audioSwitch: TvAudioSwitch? = null

    fun loadFailed(trackType: Int, uri: String, atMillis: Long): Boolean {
        val subtitle = trackType == C.TRACK_TYPE_TEXT || uri in sidecarUris
        if (subtitle) {
            subtitleFailedAtMillis = atMillis
            if (uri in sidecarUris) failedSubtitles += uri
        }
        return subtitle
    }

    fun audioSwitching(from: TvAudioSwitch) {
        audioSwitch = from
    }

    fun audioPlaying() {
        audioSwitch = null
    }

    fun recover(errorCode: Int, atMillis: Long): TvTrackRecoveryStep? {
        val switch = audioSwitch
        val subtitleFailedAt = subtitleFailedAtMillis
        return when {
            switch != null && errorCode in AUDIO_FAILURES -> {
                audioSwitch = null
                TvTrackRecoveryStep.RestoreAudio(switch)
            }
            subtitleFailedAt != null && atMillis - subtitleFailedAt <= SUBTITLE_FAILURE_WINDOW_MILLIS &&
                errorCode in LOAD_FAILURES -> {
                subtitleFailedAtMillis = null
                TvTrackRecoveryStep.DropSubtitles(failedSubtitles.toSet()).also { failedSubtitles.clear() }
            }
            else -> null
        }
    }
}

internal fun MediaItem.sidecarSubtitleUris(): Set<String> =
    localConfiguration?.subtitleConfigurations.orEmpty().map { it.uri.toString() }.toSet()

internal fun Player.continueWithout(step: TvTrackRecoveryStep.DropSubtitles, positionMillis: Long) {
    trackSelectionParameters = trackSelectionParameters.withSubtitleSelection(SubtitleSelection.Off, emptyList())
    val item = currentMediaItem
    if (step.uris.isNotEmpty() && item != null) {
        val kept = item.localConfiguration?.subtitleConfigurations.orEmpty()
            .filterNot { it.uri.toString() in step.uris }
        setMediaItem(item.buildUpon().setSubtitleConfigurations(kept).build(), positionMillis)
    }
    prepare()
}

@Composable
internal fun TvSubtitlesUnavailableNotice(source: PlaybackSource, target: PlaybackTarget, snackbar: TvSnackbarState) {
    val message = stringResource(R.string.tv_player_subtitles_failed)
    val unavailable = target.mediaType == PlaybackMediaType.VIDEO && source.subtitles is PlaybackSubtitles.Unavailable
    LaunchedEffect(source, unavailable) {
        if (unavailable) snackbar.show(TvSnackbarMessage(message))
    }
}

private const val SUBTITLE_FAILURE_WINDOW_MILLIS = 10_000L
private val LOAD_FAILURES =
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED until PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
private val AUDIO_FAILURES =
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED until PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
