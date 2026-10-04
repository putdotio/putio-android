package io.putdotio.android.tv.player

import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import io.putdotio.android.playback.AudioSelection
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
import java.util.Locale

/** The Speed picker's choices, as the RN player offered them (tv-native `VideoPlayer.android.tsx`). */
internal val TV_PLAYBACK_SPEEDS = listOf(
    QUARTER_SPEED,
    HALF_SPEED,
    THREE_QUARTERS_SPEED,
    1f,
    ONE_AND_A_QUARTER_SPEED,
    ONE_AND_A_HALF_SPEED,
    ONE_AND_THREE_QUARTERS_SPEED,
    2f,
)

private const val QUARTER_SPEED = 0.25f
private const val HALF_SPEED = 0.5f
private const val THREE_QUARTERS_SPEED = 0.75f
private const val ONE_AND_A_QUARTER_SPEED = 1.25f
private const val ONE_AND_A_HALF_SPEED = 1.5f
private const val ONE_AND_THREE_QUARTERS_SPEED = 1.75f

/**
 * The option buttons above the seek bar, left to right, as the RN player showed them:
 * Language only with more than one audio track, Subtitles only with a subtitle track, Speed always.
 */
internal fun tvOptionButtons(audioTracks: Int, subtitleTracks: Int): List<TvPlayerControl> =
    buildList {
        if (audioTracks > 1) add(TvPlayerControl.Language)
        if (subtitleTracks > 0) add(TvPlayerControl.Subtitles)
        add(TvPlayerControl.Speed)
    }

/** A track's name and language as Media3 reports them. */
internal data class TvTrackName(val label: String?, val language: String?)

/**
 * Picker labels. A track shows its own name, else its language's name, as the RN player's
 * `getTrackLabel` did. MP4 subtitles are the RN player's sidecar tracks, which it relabelled
 * `LANGUAGE - name` (`LANGUAGE` alone without a name). A track with neither takes its
 * [numbered] label. Labels shared by several tracks need [repeatedTrackLabels] disambiguation.
 */
internal fun tvTrackLabels(
    tracks: List<TvTrackName>,
    languageFirst: Boolean,
    locale: Locale,
    numbered: List<String>,
): List<String> =
    tracks.mapIndexed { index, track ->
        val label = track.label?.takeIf(String::isNotBlank)
        if (languageFirst) {
            val language = (track.language ?: UNKNOWN_LANGUAGE).uppercase(Locale.ROOT)
            label?.let { "$language - $it" } ?: language
        } else {
            label ?: track.language?.let { languageName(it, locale) } ?: numbered[index]
        }
    }

/** Labels more than one track shares; the picker adds each one's position, as mobile does. */
internal fun repeatedTrackLabels(labels: List<String>): Set<String> =
    labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

private fun languageName(tag: String, locale: Locale): String {
    val name = Locale.forLanguageTag(tag).getDisplayLanguage(locale)
    return name.takeIf { it.isNotBlank() }?.replaceFirstChar { it.titlecase(locale) } ?: tag
}

/** A speed as the picker and the Speed button label it: `0.25×`, `1×`. */
internal fun tvSpeedValue(speed: Float): String = speed.toString().removeSuffix(".0")

private const val UNKNOWN_LANGUAGE = "unknown"

/**
 * The viewer's choices for one file's playback. Speed and audio start over with each file, as
 * the RN player's did. [subtitles] is null until the viewer picks; the account's subtitle
 * settings decide until then. A pick is kept across track changes, seeks and a rebuilt player.
 */
internal data class TvPlaybackOptions(
    val speed: Float = 1f,
    val audio: AudioSelection = AudioSelection.Automatic,
    val subtitles: SubtitleSelection? = null,
)

internal val TvPlaybackOptionsSaver: Saver<TvPlaybackOptions, Bundle> = Saver(
    save = { options ->
        Bundle().apply {
            putFloat(SPEED_KEY, options.speed)
            putBundle(AUDIO_KEY, options.audio.toBundle())
            options.subtitles?.let { putBundle(SUBTITLES_KEY, it.toBundle()) }
        }
    },
    restore = { bundle ->
        TvPlaybackOptions(
            speed = bundle.getFloat(SPEED_KEY, 1f).takeIf { it in TV_PLAYBACK_SPEEDS } ?: 1f,
            audio = bundle.getBundle(AUDIO_KEY)?.toAudioSelection() ?: AudioSelection.Automatic,
            subtitles = bundle.getBundle(SUBTITLES_KEY)?.toSubtitleSelection(),
        )
    },
)

/** Applies the viewer's [options] to [current], a new track list, keeping its choices. */
internal fun Player.keepChoices(
    current: Tracks,
    options: TvPlaybackOptions,
    startupPolicy: SubtitleStartupPolicy?,
    textDefaults: TrackSelectionParameters,
) {
    // A picked subtitle track is found again in each new track list, and automatic
    // subtitles find the account's default; Off stays off because the text type stays
    // disabled whatever the tracks do (#45).
    val withSubtitles = trackSelectionParameters.withSubtitleTracks(
        retained = options.subtitles,
        startupPolicy = startupPolicy,
        tracks = current.playbackSubtitleTracks(),
        textDefaults = textDefaults,
    )
    val withAudio = withSubtitles.withRetainedAudioSelection(options.audio, current.playbackAudioTracks())
    if (withAudio != trackSelectionParameters) trackSelectionParameters = withAudio
}

/** Applies the account's subtitle [policy] unless the viewer [picked] while subtitles show. */
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
