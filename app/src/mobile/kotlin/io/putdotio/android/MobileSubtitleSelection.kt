package io.putdotio.android

import android.os.Bundle
import android.view.accessibility.CaptioningManager
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi

internal data class SubtitleStartupPolicy(
    val showSubtitles: Boolean,
    val autoSelectSubtitles: Boolean,
)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal data class MobileSubtitleTrack(
    val group: TrackGroup,
    val trackIndex: Int,
    val identity: SubtitleTrackIdentity = group.getFormat(trackIndex).toSubtitleTrackIdentity(),
    val label: String?,
    val selected: Boolean,
)

internal data class SubtitleTrackIdentity(
    val id: String?,
    val language: String?,
    val label: String?,
    val sampleMimeType: String?,
    val roleFlags: Int,
    val selectionFlags: Int,
    val accessibilityChannel: Int,
) {
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun exactlyMatches(format: androidx.media3.common.Format): Boolean =
        id == format.id &&
            language == format.language &&
            label == format.label &&
            sampleMimeType == format.sampleMimeType &&
            roleFlags == format.roleFlags &&
            selectionFlags == format.selectionFlags &&
            accessibilityChannel == format.accessibilityChannel
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun List<MobileSubtitleTrack>.resolve(identity: SubtitleTrackIdentity): MobileSubtitleTrack? {
    val candidates = identity.id?.let { id -> filter { it.group.getFormat(it.trackIndex).id == id } }
    if (candidates?.size == 1) return candidates.single()
    return (candidates ?: this).singleOrNull { identity.exactlyMatches(it.group.getFormat(it.trackIndex)) }
}

internal sealed interface SubtitleSelection {
    data object Off : SubtitleSelection

    data object Automatic : SubtitleSelection

    data class Track(val identity: SubtitleTrackIdentity) : SubtitleSelection
}

internal fun Tracks.mobileSubtitleTracks(): List<MobileSubtitleTrack> =
    groups
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { group ->
            (0 until group.length)
                .filter { group.isTrackSupported(it) }
                .map { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    MobileSubtitleTrack(
                        group = group.mediaTrackGroup,
                        trackIndex = trackIndex,
                        identity = format.toSubtitleTrackIdentity(),
                        label = format.label ?: format.language,
                        selected = group.isTrackSelected(trackIndex),
                    )
                }
        }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun androidx.media3.common.Format.toSubtitleTrackIdentity(): SubtitleTrackIdentity =
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
internal fun TrackSelectionParameters.withSubtitleSelection(
    selection: SubtitleSelection,
    tracks: List<MobileSubtitleTrack>,
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
            if (textDefaults != null) {
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

internal fun TrackSelectionParameters.subtitlesEnabled(tracks: List<MobileSubtitleTrack>): Boolean =
    C.TRACK_TYPE_TEXT !in disabledTrackTypes &&
        (selectTextByDefault || tracks.any(MobileSubtitleTrack::selected))

internal fun restoreSubtitleSelection(
    defaults: TrackSelectionParameters,
    retained: SubtitleSelection?,
    systemCaptionsEnabled: Boolean,
    startupPolicy: SubtitleStartupPolicy? = null,
): TrackSelectionParameters =
    retained?.let { defaults.withSubtitleSelection(it, emptyList(), defaults) }
        ?: startupPolicy?.let { policy ->
            when {
                !policy.showSubtitles ->
                    defaults.withSubtitleSelection(SubtitleSelection.Off, emptyList())
                policy.autoSelectSubtitles ->
                    defaults.withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), defaults)
                else ->
                    defaults.withForcedSubtitlesOnly()
            }
        }
        ?: if (systemCaptionsEnabled) {
            defaults.withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), defaults)
        } else {
            defaults.withForcedSubtitlesOnly()
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

internal fun SubtitleSelection.toBundle(): Bundle =
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

internal fun Bundle.toSubtitleSelection(): SubtitleSelection? =
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

internal fun android.content.Context.systemCaptionsEnabled(): Boolean =
    (getSystemService(android.content.Context.CAPTIONING_SERVICE) as? CaptioningManager)?.isEnabled == true
