package io.putdotio.android.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.hls.HlsManifest

public sealed interface PlaybackTrackRecoveryStep {
    public data class DropSubtitles(
        public val uris: Set<String>,
        public val notify: Boolean,
    ) : PlaybackTrackRecoveryStep

    public data class RestoreAudio(public val switch: PlaybackAudioSwitch) : PlaybackTrackRecoveryStep
}

public class PlaybackTrackFailure(
    public val code: Int,
    public val uri: String? = null,
    public val audioRenderer: Boolean? = null,
)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
public fun PlaybackException.toTrackFailure(): PlaybackTrackFailure =
    PlaybackTrackFailure(
        code = errorCode,
        uri = (cause as? HttpDataSource.HttpDataSourceException)?.dataSpec?.uri?.toString(),
        audioRenderer = (this as? ExoPlaybackException)
            ?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
            ?.rendererFormat?.sampleMimeType?.let(MimeTypes::isAudio),
    )

public class PlaybackAudioSwitch(
    public val parameters: TrackSelectionParameters,
    public val selection: AudioSelection,
)

public class PlaybackTrackRecovery(
    private val sidecarUris: () -> Set<String>,
    private val manifestAudioUris: () -> Set<String> = { emptySet() },
    private val manifestSubtitleUris: () -> Set<String> = { emptySet() },
) {
    public constructor(
        sidecarUris: Set<String>,
        manifestAudioUris: () -> Set<String> = { emptySet() },
        manifestSubtitleUris: () -> Set<String> = { emptySet() },
    ) : this({ sidecarUris }, manifestAudioUris, manifestSubtitleUris)

    private val failingSubtitles = mutableMapOf<String, Long>()
    private val failingAudio = mutableMapOf<String, Long>()
    private var droppedAtMillis: Long? = null
    private var droppedUris = emptySet<String>()
    private var audioSwitch: PlaybackAudioSwitch? = null
    private var audioSwitchedAtMillis = 0L

    public fun loadFailed(
        trackType: Int,
        uri: String,
        gaveUp: Boolean,
        atMillis: Long,
    ): PlaybackTrackRecoveryStep.DropSubtitles? {
        forgetBefore(atMillis)
        if (isAudio(trackType, uri)) failingAudio[uri] = atMillis
        if (!isSubtitle(trackType, uri)) return null
        failingSubtitles[uri] = atMillis
        return if (gaveUp) drop(atMillis) else null
    }

    public fun loadCompleted(uri: String) {
        failingSubtitles -= uri
        failingAudio -= uri
    }

    public fun audioSwitching(from: PlaybackAudioSwitch, atMillis: Long) {
        audioSwitch = from
        audioSwitchedAtMillis = atMillis
    }

    public fun audioPlaying() {
        audioSwitch = null
    }

    public fun recover(failure: PlaybackTrackFailure, atMillis: Long): PlaybackTrackRecoveryStep? {
        val switch = audioSwitch?.takeIf { atMillis - audioSwitchedAtMillis <= TRACK_FAILURE_WINDOW_MILLIS }
        forgetBefore(atMillis)
        val justDropped = droppedAtMillis?.let { atMillis - it <= TRACK_FAILURE_WINDOW_MILLIS } == true
        val dropped = droppedUris.takeIf { justDropped }.orEmpty()
        droppedAtMillis = null
        droppedUris = emptySet()
        val subtitleLoad = failure.uri
            ?.let { it in failingSubtitles || it in dropped || isSubtitle(C.TRACK_TYPE_UNKNOWN, it) } != false
        return when {
            switch != null && isAudioFailure(failure) -> {
                audioSwitch = null
                failingAudio.clear()
                PlaybackTrackRecoveryStep.RestoreAudio(switch)
            }
            failure.code in LOAD_FAILURES && subtitleLoad && (failingSubtitles.isNotEmpty() || justDropped) ->
                drop(atMillis = null)
            else -> null
        }
    }

    private fun isAudioFailure(failure: PlaybackTrackFailure): Boolean =
        when (failure.code) {
            in AUDIO_FAILURES -> failure.audioRenderer != false
            in LOAD_FAILURES -> failure.uri?.let { it in failingAudio || isAudio(C.TRACK_TYPE_UNKNOWN, it) }
                ?: failingAudio.isNotEmpty()
            else -> false
        }

    private fun drop(atMillis: Long?): PlaybackTrackRecoveryStep.DropSubtitles {
        val step = PlaybackTrackRecoveryStep.DropSubtitles(
            uris = failingSubtitles.keys intersect sidecarUris(),
            notify = failingSubtitles.isNotEmpty(),
        )
        droppedUris = failingSubtitles.keys.toSet()
        failingSubtitles.clear()
        droppedAtMillis = atMillis
        return step
    }

    private fun forgetBefore(atMillis: Long) {
        failingSubtitles.values.removeAll { atMillis - it > TRACK_FAILURE_WINDOW_MILLIS }
        failingAudio.values.removeAll { atMillis - it > TRACK_FAILURE_WINDOW_MILLIS }
    }

    private fun isSubtitle(trackType: Int, uri: String): Boolean =
        trackType == C.TRACK_TYPE_TEXT || uri in sidecarUris() || uri in manifestSubtitleUris()

    private fun isAudio(trackType: Int, uri: String): Boolean =
        trackType == C.TRACK_TYPE_AUDIO || uri in manifestAudioUris()
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
public fun TrackSelectionParameters.withAudioOf(before: TrackSelectionParameters): TrackSelectionParameters {
    val builder = buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_AUDIO in before.disabledTrackTypes)
    before.overrides.values.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach(builder::addOverride)
    return builder.build()
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
public fun Player.manifestSubtitleUris(): Set<String> =
    (currentManifest as? HlsManifest)?.multivariantPlaylist?.subtitles.orEmpty().map { it.url.toString() }.toSet()

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
public fun Player.manifestAudioUris(): Set<String> =
    (currentManifest as? HlsManifest)?.multivariantPlaylist?.audios.orEmpty()
        .mapNotNull { it.url?.toString() }.toSet()

public fun MediaItem.sidecarSubtitleUris(): Set<String> =
    localConfiguration?.subtitleConfigurations.orEmpty().map { it.uri.toString() }.toSet()

public fun Player.continueWithout(step: PlaybackTrackRecoveryStep.DropSubtitles) {
    val positionMillis = currentPosition.coerceAtLeast(0L)
    trackSelectionParameters = trackSelectionParameters.withSubtitleSelection(SubtitleSelection.Off, emptyList())
    val item = currentMediaItem
    if (step.uris.isNotEmpty() && item != null) {
        val kept = item.localConfiguration?.subtitleConfigurations.orEmpty()
            .filterNot { it.uri.toString() in step.uris }
        setMediaItem(item.buildUpon().setSubtitleConfigurations(kept).build(), positionMillis)
    }
    prepare()
}

private const val TRACK_FAILURE_WINDOW_MILLIS = 10_000L
private val LOAD_FAILURES =
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED until PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
private val AUDIO_FAILURES =
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED until PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
