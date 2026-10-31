package io.putdotio.android.tv.player

import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import io.putdotio.android.playback.AudioSelection
import io.putdotio.android.playback.PLAYBACK_SPEEDS
import io.putdotio.android.playback.SUBTITLE_OFFSET_LIMIT_MILLIS
import io.putdotio.android.playback.SubtitleSelection
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.playbackAudioTracks
import io.putdotio.android.playback.playbackSubtitleTracks
import io.putdotio.android.playback.restoreSubtitleSelection
import io.putdotio.android.playback.toAudioSelection
import io.putdotio.android.playback.toBundle
import io.putdotio.android.playback.toSubtitleSelection
import io.putdotio.android.playback.withRetainedAudioSelection
import io.putdotio.android.playback.withSubtitleSelection
import io.putdotio.android.playback.withSubtitleTracks

internal fun tvPlayerChips(audioTracks: Int, subtitlesHidden: Boolean, nextEpisode: Boolean): List<TvPlayerControl> =
    buildList {
        if (audioTracks > 0) add(TvPlayerControl.Audio)
        if (!subtitlesHidden) add(TvPlayerControl.Subtitles)
        add(TvPlayerControl.Speed)
        if (nextEpisode) add(TvPlayerControl.NextEpisode)
    }

internal data class TvPlaybackOptions(
    val speed: Float = 1f,
    val audio: AudioSelection = AudioSelection.Automatic,
    val subtitles: SubtitleSelection? = null,
    val subtitleOffsetMillis: Long = 0L,
)

internal val TvPlaybackOptionsSaver: Saver<TvPlaybackOptions, Bundle> = Saver(
    save = { options ->
        Bundle().apply {
            putFloat(SPEED_KEY, options.speed)
            putLong(SUBTITLE_OFFSET_KEY, options.subtitleOffsetMillis)
            putBundle(AUDIO_KEY, options.audio.toBundle())
            options.subtitles?.let { putBundle(SUBTITLES_KEY, it.toBundle()) }
        }
    },
    restore = { bundle ->
        TvPlaybackOptions(
            speed = bundle.getFloat(SPEED_KEY, 1f).takeIf { it in PLAYBACK_SPEEDS } ?: 1f,
            audio = bundle.getBundle(AUDIO_KEY)?.toAudioSelection() ?: AudioSelection.Automatic,
            subtitles = bundle.getBundle(SUBTITLES_KEY)?.toSubtitleSelection(),
            subtitleOffsetMillis = bundle.getLong(SUBTITLE_OFFSET_KEY, 0L)
                .coerceIn(-SUBTITLE_OFFSET_LIMIT_MILLIS, SUBTITLE_OFFSET_LIMIT_MILLIS),
        )
    },
)

internal fun Player.keepChoices(
    current: Tracks,
    options: TvPlaybackOptions,
    startupPolicy: SubtitleStartupPolicy?,
    textDefaults: TrackSelectionParameters,
) {
    val withSubtitles = trackSelectionParameters.withSubtitleTracks(
        retained = options.subtitles,
        startupPolicy = startupPolicy,
        tracks = current.playbackSubtitleTracks(),
        textDefaults = textDefaults,
    )
    val withAudio = withSubtitles.withRetainedAudioSelection(options.audio, current.playbackAudioTracks())
    if (withAudio != trackSelectionParameters) trackSelectionParameters = withAudio
}

internal fun Player.followSubtitlePolicy(
    policy: SubtitleStartupPolicy?,
    picked: SubtitleSelection?,
    textDefaults: TrackSelectionParameters,
) {
    if (policy == null) return
    if (picked != null && policy.showSubtitles) return
    val current = trackSelectionParameters
    val updated = if (policy.showSubtitles && policy.autoSelectSubtitles) {
        current.withSubtitleSelection(
            SubtitleSelection.Automatic,
            currentTracks.playbackSubtitleTracks(),
            textDefaults,
        )
    } else {
        restoreSubtitleSelection(current, null, policy)
    }
    if (updated != current) trackSelectionParameters = updated
}

private const val SPEED_KEY = "speed"
private const val AUDIO_KEY = "audio"
private const val SUBTITLES_KEY = "subtitles"
private const val SUBTITLE_OFFSET_KEY = "subtitle-offset"
