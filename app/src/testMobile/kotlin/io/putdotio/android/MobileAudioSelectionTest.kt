package io.putdotio.android

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
class MobileAudioSelectionTest {
    @Test
    fun listsOnlySupportedAudioTracksWithLabelsAndSelection() {
        val audio = TrackGroup(audioFormat("en", "en"), audioFormat("de", "de"), audioFormat("fr", "fr"))
        val text = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build())
        val tracks = Tracks(
            listOf(
                Tracks.Group(
                    audio,
                    false,
                    intArrayOf(C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_SUBTYPE, C.FORMAT_HANDLED),
                    booleanArrayOf(true, false, false),
                ),
                Tracks.Group(text, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
            ),
        ).mobileAudioTracks()

        assertEquals(listOf(0, 2), tracks.map { it.trackIndex })
        assertEquals(listOf("en", "fr"), tracks.map { it.label })
        assertEquals(listOf(true, false), tracks.map { it.selected })
        assertEquals(listOf(audio, audio), tracks.map { it.group })
    }

    @Test
    fun retainedIdentityResolvesRecreatedAndReorderedTracks() {
        val english = audioFormat("en", "en")
        val german = audioFormat("de", "de")
        val original = TrackGroup(english, german)
        val selection = AudioSelection.Track(german.toAudioTrackIdentity())
        val parameters = TrackSelectionParameters.Builder().build()
            .withAudioSelection(selection, listOf(track(original, 1)))
        val replacement = TrackGroup(german.buildUpon().setLabel("Deutsch").build(), english)

        val restored = parameters.withAudioSelection(selection, listOf(track(replacement, 0), track(replacement, 1)))

        assertFalse(original in restored.overrides)
        assertEquals(listOf(0), restored.overrides.getValue(replacement).trackIndices)
        assertFalse(C.TRACK_TYPE_AUDIO in restored.disabledTrackTypes)
    }

    @Test
    fun duplicateIdsRequireTheRemainingIdentityToMatch() {
        val english = audioFormat("audio", "en")
        val german = audioFormat("audio", "de")
        val englishGroup = TrackGroup("english", english)
        val germanGroup = TrackGroup("german", german)
        val selection = AudioSelection.Track(german.toAudioTrackIdentity())

        val selected = TrackSelectionParameters.Builder().build()
            .withAudioSelection(selection, listOf(track(englishGroup), track(germanGroup)))

        assertEquals(setOf(germanGroup), selected.overrides.keys)
    }

    @Test
    fun idlessTracksDistinguishAudioPropertiesAndFlags() {
        val original = audioFormat(null, "en")
        val identity = original.toAudioTrackIdentity()
        val variants = listOf(
            original.buildUpon().setChannelCount(6).build(),
            original.buildUpon().setSampleRate(48000).build(),
            original.buildUpon().setRoleFlags(C.ROLE_FLAG_COMMENTARY).build(),
            original.buildUpon().setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build(),
        )

        assertTrue(identity.exactlyMatches(original))
        variants.forEach { variant ->
            assertFalse(identity.exactlyMatches(variant))
            assertNull(listOf(track(TrackGroup(variant))).resolve(identity))
        }
    }

    @Test
    fun missingAndAmbiguousTracksFallBackWithoutMuting() {
        val format = audioFormat("audio", "en")
        val first = TrackGroup("first", format)
        val second = TrackGroup("second", format)
        val selection = AudioSelection.Track(format.toAudioTrackIdentity())
        val previous = TrackSelectionParameters.Builder()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setOverrideForType(TrackSelectionOverride(first, 0))
            .build()

        listOf(emptyList(), listOf(track(first), track(second))).forEach { tracks ->
            val fallback = previous.withAudioSelection(selection, tracks)
            assertTrue(fallback.overrides.isEmpty())
            assertFalse(C.TRACK_TYPE_AUDIO in fallback.disabledTrackTypes)
        }
        val resolved = previous.withAudioSelection(selection, emptyList())
            .withAudioSelection(selection, listOf(track(second)))
        assertEquals(listOf(0), resolved.overrides.getValue(second).trackIndices)
    }

    @Test
    fun audioSelectionAndAutomaticPreserveSubtitleOverridesAndPreferences() {
        val audio = TrackGroup(audioFormat("en", "en"))
        val text = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build())
        val subtitleOverride = TrackSelectionOverride(text, 0)
        val defaults = TrackSelectionParameters.Builder()
            .setOverrideForType(subtitleOverride)
            .setPreferredAudioLanguage("de")
            .setPreferredTextLanguage("fr")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        val selected = defaults.withAudioSelection(
            AudioSelection.Track(audio.getFormat(0).toAudioTrackIdentity()),
            listOf(track(audio)),
        )
        val automatic = selected.withAudioSelection(AudioSelection.Automatic, listOf(track(audio)))

        assertEquals(listOf(0), selected.overrides.getValue(audio).trackIndices)
        assertEquals(mapOf(text to subtitleOverride), automatic.overrides)
        listOf(selected, automatic).forEach { parameters ->
            assertEquals(subtitleOverride, parameters.overrides[text])
            assertEquals(listOf("de"), parameters.preferredAudioLanguages)
            assertEquals(listOf("fr"), parameters.preferredTextLanguages)
            assertTrue(C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes)
            assertFalse(C.TRACK_TYPE_AUDIO in parameters.disabledTrackTypes)
        }
    }

    @Test
    fun concreteDuplicateTrackSurvivesLiveUpdatesButNotAmbiguousRecreation() {
        val format = audioFormat("audio", "en")
        val audio = TrackGroup(format, format)
        val text = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build())
        val subtitle = TrackSelectionOverride(text, 0)
        val defaults = TrackSelectionParameters.Builder().setOverrideForType(subtitle).build()
        val first = track(audio, 0)
        val second = track(audio, 1)
        val selection = AudioSelection.Track(second.identity)

        val selected = defaults.withAudioTrack(second)
        val awaitingTracks = selected.withRetainedAudioSelection(selection, emptyList())
        val updated = awaitingTracks.withRetainedAudioSelection(selection, listOf(first, second))

        assertEquals(listOf(1), selected.overrides.getValue(audio).trackIndices)
        assertEquals(selected, awaitingTracks)
        assertEquals(selected, updated)
        assertEquals(subtitle, updated.overrides[text])

        val replacement = TrackGroup(format.buildUpon().build(), format.buildUpon().build())
        val prepared = updated.withAudioSelection(selection, emptyList())
        val recreated = prepared.withRetainedAudioSelection(
            requireNotNull(selection.toBundle().toAudioSelection()),
            listOf(track(replacement, 0), track(replacement, 1)),
        )

        assertEquals(mapOf(text to subtitle), recreated.overrides)
        assertFalse(C.TRACK_TYPE_AUDIO in recreated.disabledTrackTypes)
    }

    @Test
    fun nonemptyReplacementTracksAndAutomaticDiscardUnavailableLiveOverride() {
        val first = track(TrackGroup(audioFormat("first", "en")))
        val replacement = track(TrackGroup(audioFormat("replacement", "de")))
        val selection = AudioSelection.Track(first.identity)
        val selected = TrackSelectionParameters.Builder().build().withAudioTrack(first)

        val unavailable = selected.withRetainedAudioSelection(selection, listOf(replacement))
        val automatic = selected.withRetainedAudioSelection(AudioSelection.Automatic, emptyList())
        val different = selected.withRetainedAudioSelection(AudioSelection.Track(replacement.identity), emptyList())

        listOf(unavailable, automatic, different).forEach { parameters ->
            assertTrue(parameters.overrides.isEmpty())
            assertFalse(C.TRACK_TYPE_AUDIO in parameters.disabledTrackTypes)
        }
    }

    @Test
    fun bundleRoundTripPreservesAutomaticAndTrackIdentity() {
        val format = audioFormat("audio", "en").buildUpon()
            .setLabel("Commentary")
            .setRoleFlags(C.ROLE_FLAG_COMMENTARY)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val selections = listOf(
            AudioSelection.Automatic,
            AudioSelection.Track(format.toAudioTrackIdentity()),
            AudioSelection.Track(audioFormat(null, null).toAudioTrackIdentity()),
        )

        selections.forEach { selection ->
            assertEquals(selection, selection.toBundle().toAudioSelection())
        }
        assertNull(Bundle().toAudioSelection())
        assertNull(Bundle().apply { putString("kind", "off") }.toAudioSelection())
    }

    private fun audioFormat(id: String?, language: String?): Format =
        Format.Builder()
            .setId(id)
            .setLanguage(language)
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()

    private fun track(group: TrackGroup, index: Int = 0): MobileAudioTrack =
        MobileAudioTrack(group, index, label = null, selected = false)
}
