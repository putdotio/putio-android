package io.putdotio.android

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal data class MobileAudioTrack(
    val group: TrackGroup,
    val trackIndex: Int,
    val identity: AudioTrackIdentity = group.getFormat(trackIndex).toAudioTrackIdentity(),
    val label: String?,
    val selected: Boolean,
)

internal data class AudioTrackIdentity(
    val id: String?,
    val language: String?,
    val label: String?,
    val sampleMimeType: String?,
    val roleFlags: Int,
    val selectionFlags: Int,
    val channelCount: Int,
    val sampleRate: Int,
) {
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun exactlyMatches(format: Format): Boolean =
        id == format.id &&
            language == format.language &&
            label == format.label &&
            sampleMimeType == format.sampleMimeType &&
            roleFlags == format.roleFlags &&
            selectionFlags == format.selectionFlags &&
            channelCount == format.channelCount &&
            sampleRate == format.sampleRate
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun List<MobileAudioTrack>.resolve(identity: AudioTrackIdentity): MobileAudioTrack? {
    val candidates = identity.id?.let { id -> filter { it.group.getFormat(it.trackIndex).id == id } }
    if (candidates?.size == 1) return candidates.single()
    return (candidates ?: this).singleOrNull { identity.exactlyMatches(it.group.getFormat(it.trackIndex)) }
}

internal sealed interface AudioSelection {
    data object Automatic : AudioSelection

    data class Track(val identity: AudioTrackIdentity) : AudioSelection
}

internal fun Tracks.mobileAudioTracks(): List<MobileAudioTrack> =
    groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { group ->
            (0 until group.length)
                .filter { group.isTrackSupported(it) }
                .map { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    MobileAudioTrack(
                        group = group.mediaTrackGroup,
                        trackIndex = trackIndex,
                        identity = format.toAudioTrackIdentity(),
                        label = format.label ?: format.language,
                        selected = group.isTrackSelected(trackIndex),
                    )
                }
        }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun Format.toAudioTrackIdentity(): AudioTrackIdentity =
    AudioTrackIdentity(
        id = id,
        language = language,
        label = label,
        sampleMimeType = sampleMimeType,
        roleFlags = roleFlags,
        selectionFlags = selectionFlags,
        channelCount = channelCount,
        sampleRate = sampleRate,
    )

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun TrackSelectionParameters.withAudioSelection(
    selection: AudioSelection,
    tracks: List<MobileAudioTrack>,
): TrackSelectionParameters {
    val builder =
        buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
    if (selection is AudioSelection.Track) {
        tracks.resolve(selection.identity)?.let { track ->
            builder.setOverrideForType(TrackSelectionOverride(track.group, track.trackIndex))
        }
    }
    return builder.build()
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun TrackSelectionParameters.withAudioTrack(track: MobileAudioTrack): TrackSelectionParameters =
    buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        .setOverrideForType(TrackSelectionOverride(track.group, track.trackIndex))
        .build()

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun TrackSelectionParameters.withRetainedAudioSelection(
    selection: AudioSelection,
    tracks: List<MobileAudioTrack>,
): TrackSelectionParameters {
    val liveTrack = tracks.singleOrNull { track ->
        overrides[track.group]?.trackIndices == listOf(track.trackIndex)
    }
    // The live override identifies a concrete row even when format metadata collides.
    // Fresh preparation clears it before retained identities resolve against new tracks.
    return if (selection is AudioSelection.Track && liveTrack != null && liveTrack.identity == selection.identity) {
        withAudioTrack(liveTrack)
    } else {
        withAudioSelection(selection, tracks)
    }
}

internal fun AudioSelection.toBundle(): Bundle =
    Bundle().apply {
        when (this@toBundle) {
            AudioSelection.Automatic -> putString("kind", "automatic")
            is AudioSelection.Track -> {
                putString("kind", "track")
                putString("id", identity.id)
                putString("language", identity.language)
                putString("label", identity.label)
                putString("sampleMimeType", identity.sampleMimeType)
                putInt("roleFlags", identity.roleFlags)
                putInt("selectionFlags", identity.selectionFlags)
                putInt("channelCount", identity.channelCount)
                putInt("sampleRate", identity.sampleRate)
            }
        }
    }

internal fun Bundle.toAudioSelection(): AudioSelection? =
    when (getString("kind")) {
        "automatic" -> AudioSelection.Automatic
        "track" ->
            AudioSelection.Track(
                AudioTrackIdentity(
                    id = getString("id"),
                    language = getString("language"),
                    label = getString("label"),
                    sampleMimeType = getString("sampleMimeType"),
                    roleFlags = getInt("roleFlags"),
                    selectionFlags = getInt("selectionFlags"),
                    channelCount = getInt("channelCount"),
                    sampleRate = getInt("sampleRate"),
                ),
            )
        else -> null
    }
