package io.putdotio.android.playback

import android.os.Bundle
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi

public data class SubtitleStartupPolicy(
    val showSubtitles: Boolean,
    val autoSelectSubtitles: Boolean,
)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
public data class PlaybackSubtitleTrack(
    val group: TrackGroup,
    val trackIndex: Int,
    val identity: SubtitleTrackIdentity = group.getFormat(trackIndex).toSubtitleTrackIdentity(),
    val label: String?,
    val selected: Boolean,
)

public data class SubtitleTrackIdentity(
    val id: String?,
    val language: String?,
    val label: String?,
    val sampleMimeType: String?,
    val roleFlags: Int,
    val selectionFlags: Int,
    val accessibilityChannel: Int,
) {
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    internal fun exactlyMatches(format: androidx.media3.common.Format): Boolean =
        id == format.id &&
            language == format.language &&
            label == format.label &&
            sampleMimeType == format.sampleMimeType &&
            roleFlags == format.roleFlags &&
            selectionFlags == format.selectionFlags &&
            accessibilityChannel == format.accessibilityChannel
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun List<PlaybackSubtitleTrack>.resolve(identity: SubtitleTrackIdentity): PlaybackSubtitleTrack? {
    val candidates = identity.id?.let { id -> filter { it.group.getFormat(it.trackIndex).id == id } }
    if (candidates?.size == 1) return candidates.single()
    return (candidates ?: this).singleOrNull { identity.exactlyMatches(it.group.getFormat(it.trackIndex)) }
}

public sealed interface SubtitleSelection {
    public data object Off : SubtitleSelection

    public data object Automatic : SubtitleSelection

    public data class Track(val identity: SubtitleTrackIdentity) : SubtitleSelection
}

public fun Tracks.playbackSubtitleTracks(): List<PlaybackSubtitleTrack> =
    groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { group ->
            (0 until group.length)
                .filter { group.isTrackSupported(it) }
                .map { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    PlaybackSubtitleTrack(
                        group = group.mediaTrackGroup,
                        trackIndex = trackIndex,
                        identity = format.toSubtitleTrackIdentity(),
                        label = format.label ?: format.language,
                        selected = group.isTrackSelected(trackIndex),
                    )
                }
        }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
public fun androidx.media3.common.Format.toSubtitleTrackIdentity(): SubtitleTrackIdentity =
    SubtitleTrackIdentity(
        id = id,
        language = language,
        label = label,
        sampleMimeType = sampleMimeType,
        roleFlags = roleFlags,
        selectionFlags = selectionFlags,
        accessibilityChannel = accessibilityChannel,
    )

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
public fun TrackSelectionParameters.withSubtitleSelection(
    selection: SubtitleSelection,
    tracks: List<PlaybackSubtitleTrack>,
    textDefaults: TrackSelectionParameters? = null,
): TrackSelectionParameters {
    val builder =
        buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setIgnoredTextSelectionFlags(0)
    return when (selection) {
        SubtitleSelection.Off ->
            builder
                .setSelectTextByDefault(false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()

        SubtitleSelection.Automatic -> {
            if (tracks.any(PlaybackSubtitleTrack::isServerDefault)) {
                // Media3 ranks the device's caption language above a track's default flag; with no
                // preference left, the account's default subtitle wins (#237).
                builder
                    .setPreferredTextLanguages()
                    .setPreferredTextRoleFlags(0)
                    .setPreferredTextLabels()
                    .setSelectUndeterminedTextLanguage(false)
            } else if (textDefaults != null) {
                if (textDefaults.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager) {
                    builder.setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings()
                } else {
                    // Media3 accepts the complete language preference list only through this vararg setter.
                    @Suppress("SpreadOperator")
                    builder.setPreferredTextLanguages(*textDefaults.preferredTextLanguages.toTypedArray())
                    builder.setPreferredTextRoleFlags(textDefaults.preferredTextRoleFlags)
                }
                // Media3 exposes no collection overload for labels; repeated calls would replace the list.
                @Suppress("SpreadOperator")
                builder.setPreferredTextLabels(*textDefaults.preferredTextLabels.toTypedArray())
                builder.setIgnoredTextSelectionFlags(textDefaults.ignoredTextSelectionFlags)
                builder.setSelectUndeterminedTextLanguage(textDefaults.selectUndeterminedTextLanguage)
            }
            builder
                .setSelectTextByDefault(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }

        is SubtitleSelection.Track -> {
            val track = tracks.resolve(selection.identity)
            if (track == null) {
                builder
                    .setSelectTextByDefault(false)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            } else {
                builder
                    .setSelectTextByDefault(true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(track.group, track.trackIndex))
                    .build()
            }
        }
    }
}

/**
 * A track the media marks default. put.io marks the account's default subtitle this way: the
 * HLS master with `DEFAULT=YES`, and [toMediaItem] for the sidecar list's `default`.
 */
internal val PlaybackSubtitleTrack.isServerDefault: Boolean
    get() = (identity.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0

/**
 * Reapplies the subtitle choice in effect to [tracks] as they arrive: off while the account hides
 * subtitles, whatever was picked (#237), else the viewer's pick, else automatic selection when the
 * account auto-selects. Off and forced-only need no tracks.
 */
public fun TrackSelectionParameters.withSubtitleTracks(
    retained: SubtitleSelection?,
    startupPolicy: SubtitleStartupPolicy?,
    tracks: List<PlaybackSubtitleTrack>,
    textDefaults: TrackSelectionParameters,
): TrackSelectionParameters {
    if (startupPolicy?.showSubtitles == false) return withSubtitleSelection(SubtitleSelection.Off, tracks)
    val autoSelects = startupPolicy?.showSubtitles == true && startupPolicy.autoSelectSubtitles
    val selection = retained ?: SubtitleSelection.Automatic.takeIf { autoSelects }
    return when (selection) {
        is SubtitleSelection.Track, SubtitleSelection.Automatic ->
            withSubtitleSelection(selection, tracks, textDefaults)
        SubtitleSelection.Off, null -> this
    }
}

public fun TrackSelectionParameters.subtitlesEnabled(tracks: List<PlaybackSubtitleTrack>): Boolean =
    C.TRACK_TYPE_TEXT !in disabledTrackTypes &&
        (selectTextByDefault || tracks.any(PlaybackSubtitleTrack::selected))

public fun restoreSubtitleSelection(
    defaults: TrackSelectionParameters,
    retained: SubtitleSelection?,
    startupPolicy: SubtitleStartupPolicy?,
): TrackSelectionParameters =
    when {
        // hide_subtitles outranks any pick: its picker is gone, so nothing could turn one off (#237).
        startupPolicy?.showSubtitles == false -> defaults.withSubtitleSelection(SubtitleSelection.Off, emptyList())
        retained != null -> defaults.withSubtitleSelection(retained, emptyList(), defaults)
        // No policy means account settings are loading or failed to load; until they say
        // otherwise a `hide_subtitles` account must not see subtitles (#229).
        startupPolicy == null -> defaults.withSubtitleSelection(SubtitleSelection.Off, emptyList())
        startupPolicy.autoSelectSubtitles ->
            defaults.withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), defaults)
        else -> defaults.withForcedSubtitlesOnly()
    }

private fun TrackSelectionParameters.withForcedSubtitlesOnly(): TrackSelectionParameters =
    buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setPreferredTextLanguages()
        .setPreferredTextRoleFlags(0)
        .setPreferredTextLabels()
        .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .setSelectUndeterminedTextLanguage(false)
        .setSelectTextByDefault(false)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()

public fun SubtitleSelection.toBundle(): Bundle =
    Bundle().apply {
        when (this@toBundle) {
            SubtitleSelection.Off -> putString("kind", "off")
            SubtitleSelection.Automatic -> putString("kind", "automatic")
            is SubtitleSelection.Track -> {
                putString("kind", "track")
                putString("id", identity.id)
                putString("language", identity.language)
                putString("label", identity.label)
                putString("sampleMimeType", identity.sampleMimeType)
                putInt("roleFlags", identity.roleFlags)
                putInt("selectionFlags", identity.selectionFlags)
                putInt("accessibilityChannel", identity.accessibilityChannel)
            }
        }
    }

public fun Bundle.toSubtitleSelection(): SubtitleSelection? =
    when (getString("kind")) {
        "off" -> SubtitleSelection.Off
        "automatic" -> SubtitleSelection.Automatic
        "track" ->
            SubtitleSelection.Track(
                SubtitleTrackIdentity(
                    id = getString("id"),
                    language = getString("language"),
                    label = getString("label"),
                    sampleMimeType = getString("sampleMimeType"),
                    roleFlags = getInt("roleFlags"),
                    selectionFlags = getInt("selectionFlags"),
                    accessibilityChannel = getInt("accessibilityChannel"),
                ),
            )
        else -> null
    }
