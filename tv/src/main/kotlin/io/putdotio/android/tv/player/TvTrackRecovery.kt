package io.putdotio.android.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsManifest
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
    data class DropSubtitles(val uris: Set<String>, val notify: Boolean) : TvTrackRecoveryStep

    data class RestoreAudio(val switch: TvAudioSwitch) : TvTrackRecoveryStep
}

internal class TvAudioSwitch(val parameters: TrackSelectionParameters, val selection: AudioSelection)

internal class TvTrackRecovery(
    private val sidecarUris: Set<String>,
    private val manifestSubtitleUris: () -> Set<String> = { emptySet() },
) {
    private val failingSubtitles = mutableMapOf<String, Long>()
    private var notified = false
    private var audioSwitch: TvAudioSwitch? = null
    private var audioSwitchedAtMillis = 0L

    fun loadFailed(trackType: Int, uri: String, gaveUp: Boolean, atMillis: Long): Boolean {
        if (!isSubtitle(trackType, uri)) return false
        forgetBefore(atMillis)
        failingSubtitles[uri] = atMillis
        val notify = gaveUp && !notified
        if (notify) notified = true
        return notify
    }

    fun loadCompleted(uri: String) {
        failingSubtitles -= uri
        if (failingSubtitles.isEmpty()) notified = false
    }

    private fun forgetBefore(atMillis: Long) {
        failingSubtitles.values.removeAll { atMillis - it > TRACK_FAILURE_WINDOW_MILLIS }
        if (failingSubtitles.isEmpty()) notified = false
    }

    fun audioSwitching(from: TvAudioSwitch, atMillis: Long) {
        audioSwitch = from
        audioSwitchedAtMillis = atMillis
    }

    fun audioPlaying() {
        audioSwitch = null
    }

    fun recover(errorCode: Int, atMillis: Long): TvTrackRecoveryStep? {
        val switch = audioSwitch?.takeIf { atMillis - audioSwitchedAtMillis <= TRACK_FAILURE_WINDOW_MILLIS }
        forgetBefore(atMillis)
        val subtitleFailing = failingSubtitles.isNotEmpty()
        return when {
            switch != null && errorCode in AUDIO_FAILURES -> {
                audioSwitch = null
                TvTrackRecoveryStep.RestoreAudio(switch)
            }
            subtitleFailing && errorCode in LOAD_FAILURES -> {
                val step = TvTrackRecoveryStep.DropSubtitles(failingSubtitles.keys intersect sidecarUris, !notified)
                failingSubtitles.clear()
                notified = false
                step
            }
            else -> null
        }
    }

    private fun isSubtitle(trackType: Int, uri: String): Boolean =
        trackType == C.TRACK_TYPE_TEXT || uri in sidecarUris || uri in manifestSubtitleUris()
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun TrackSelectionParameters.withAudioOf(before: TrackSelectionParameters): TrackSelectionParameters {
    val builder = buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_AUDIO in before.disabledTrackTypes)
    before.overrides.values.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach(builder::addOverride)
    return builder.build()
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun Player.manifestSubtitleUris(): Set<String> =
    (currentManifest as? HlsManifest)?.multivariantPlaylist?.subtitles.orEmpty().map { it.url.toString() }.toSet()

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

private const val TRACK_FAILURE_WINDOW_MILLIS = 10_000L
private val LOAD_FAILURES =
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED until PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
private val AUDIO_FAILURES =
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED until PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
