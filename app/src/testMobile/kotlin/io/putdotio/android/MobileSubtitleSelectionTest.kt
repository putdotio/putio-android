package io.putdotio.android

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private fun rendererCapabilities(trackType: Int): RendererCapabilities =
    object : RendererCapabilities {
        override fun getName(): String = "test-$trackType"

        override fun getTrackType(): Int = trackType

        override fun supportsFormat(format: Format): Int =
            RendererCapabilities.create(
                if (MimeTypes.getTrackType(format.sampleMimeType) == trackType) {
                    C.FORMAT_HANDLED
                } else {
                    C.FORMAT_UNSUPPORTED_TYPE
                },
            )

        override fun supportsMixedMimeTypeAdaptation(): Int = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
class MobileSubtitleSelectionTest {
    @Test
    fun subtitleSelectionCanEnableDisableAndReenableUnflaggedText() {
        val defaults = TrackSelectionParameters.Builder().build()
        val enabled = defaults.withSubtitleSelection(SubtitleSelection.Automatic, emptyList())
        assertTrue(enabled.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in enabled.disabledTrackTypes)

        val disabled = enabled.withSubtitleSelection(SubtitleSelection.Off, emptyList())
        assertFalse(disabled.selectTextByDefault)
        assertTrue(C.TRACK_TYPE_TEXT in disabled.disabledTrackTypes)

        val reenabled = disabled.withSubtitleSelection(SubtitleSelection.Automatic, emptyList())
        assertTrue(reenabled.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in reenabled.disabledTrackTypes)
    }

    @Test
    fun retainedSubtitleSelectionWinsWhenThePlayerIsRecreated() {
        val defaults = TrackSelectionParameters.Builder().build()

        val restored =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = SubtitleSelection.Off,
                systemCaptionsEnabled = true,
            )

        assertFalse(restored.selectTextByDefault)
        assertTrue(C.TRACK_TYPE_TEXT in restored.disabledTrackTypes)

        val group =
            TrackGroup(
                Format.Builder().setId("en").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
                Format.Builder().setId("de").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        val track =
            MobileSubtitleTrack(
                group = group,
                trackIndex = 1,
                label = "German",
                selected = false,
            )
        val restoredSelection =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = SubtitleSelection.Track(track.identity),
                systemCaptionsEnabled = false,
            ).withSubtitleSelection(SubtitleSelection.Track(track.identity), listOf(track))

        assertEquals(listOf(1), restoredSelection.overrides.getValue(group).trackIndices)
    }

    @Test
    fun playerDefaultsPreserveTheSystemCaptionPreference() {
        val captionsDisabled =
            TrackSelectionParameters.Builder()
                .setSelectTextByDefault(false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()

        val restored =
            restoreSubtitleSelection(
                captionsDisabled,
                retained = null,
                systemCaptionsEnabled = false,
            )
        val captionsEnabled =
            restoreSubtitleSelection(
                captionsDisabled,
                retained = null,
                systemCaptionsEnabled = true,
            )

        assertFalse(C.TRACK_TYPE_TEXT in restored.disabledTrackTypes)
        assertFalse(restored.subtitlesEnabled(emptyList()))
        assertTrue(captionsEnabled.selectTextByDefault)

        val automatic = restored.withSubtitleSelection(SubtitleSelection.Automatic, emptyList())
        assertEquals(0, automatic.ignoredTextSelectionFlags)
        assertTrue(automatic.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in captionsEnabled.disabledTrackTypes)
    }

    @Test
    fun accountSubtitlePolicySeedsPlayerDefaults() {
        val defaults = TrackSelectionParameters.Builder().build()
        val hidden =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                systemCaptionsEnabled = true,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = true),
            )
        val forcedOnly =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                systemCaptionsEnabled = true,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false),
            )
        val automatic =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                systemCaptionsEnabled = false,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true),
            )
        val retained =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = SubtitleSelection.Automatic,
                systemCaptionsEnabled = false,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = false),
            )

        assertFalse(hidden.selectTextByDefault)
        assertTrue(C.TRACK_TYPE_TEXT in hidden.disabledTrackTypes)
        assertFalse(forcedOnly.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in forcedOnly.disabledTrackTypes)
        assertTrue(automatic.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in automatic.disabledTrackTypes)
        assertTrue(retained.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in retained.disabledTrackTypes)
    }

    @Test
    fun forcedOnlyPolicySelectsForcedTrackInsteadOfDefaultCaption() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val defaults =
            TrackSelectionParameters.Builder()
                .setPreferredTextLanguages("en")
                .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
                .setPreferredTextLabels("English")
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED)
                .setSelectUndeterminedTextLanguage(true)
                .build()
        val audio =
            Format.Builder()
                .setId("audio")
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setLanguage("en")
                .build()
        val ordinary =
            Format.Builder()
                .setId("ordinary")
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .setLanguage("en")
                .setLabel("English")
                .setRoleFlags(C.ROLE_FLAG_CAPTION)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        val forced =
            Format.Builder()
                .setId("forced")
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .setLanguage("en")
                .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                .build()
        val trackGroups = TrackGroupArray(TrackGroup(audio), TrackGroup(ordinary, forced))
        fun selectedTextId(parameters: TrackSelectionParameters): String? {
            val selector = DefaultTrackSelector(context, parameters)
            selector.init({ _ -> }, DefaultBandwidthMeter.getSingletonInstance(context))
            val result =
                selector.selectTracks(
                    arrayOf(rendererCapabilities(C.TRACK_TYPE_AUDIO), rendererCapabilities(C.TRACK_TYPE_TEXT)),
                    trackGroups,
                    MediaSource.MediaPeriodId(Any()),
                    Timeline.EMPTY,
                )
            return result.selections[1]?.selectedFormat?.id.also { selector.release() }
        }
        val forcedOnly =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                systemCaptionsEnabled = true,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false),
            )
        val noPolicyFallback =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                systemCaptionsEnabled = false,
            )
        val automatic =
            forcedOnly
                .withSubtitleSelection(SubtitleSelection.Off, emptyList(), defaults)
                .withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), defaults)

        assertEquals("forced", selectedTextId(forcedOnly))
        assertEquals("forced", selectedTextId(noPolicyFallback))
        assertEquals("ordinary", selectedTextId(automatic))

    }

    @Test
    fun automaticSelectionRestoresExplicitAndSystemCaptionPreferences() {
        val defaults =
            TrackSelectionParameters.Builder()
                .setPreferredTextLanguages("en")
                .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
                .setPreferredTextLabels("English")
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED)
                .setSelectUndeterminedTextLanguage(true)
                .build()
        val automatic =
            defaults
                .withSubtitleSelection(SubtitleSelection.Off, emptyList(), defaults)
                .withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), defaults)

        assertEquals(listOf("en"), automatic.preferredTextLanguages)
        assertEquals(C.ROLE_FLAG_CAPTION, automatic.preferredTextRoleFlags)
        assertEquals(listOf("English"), automatic.preferredTextLabels)
        assertEquals(C.SELECTION_FLAG_FORCED, automatic.ignoredTextSelectionFlags)
        assertTrue(automatic.selectUndeterminedTextLanguage)

        val captionManagerDefaults =
            TrackSelectionParameters.Builder()
                .setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings()
                .build()
        val captionManagerAutomatic =
            captionManagerDefaults
                .withSubtitleSelection(SubtitleSelection.Off, emptyList(), captionManagerDefaults)
                .withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), captionManagerDefaults)
        assertTrue(captionManagerAutomatic.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager)
    }

    @Test
    fun selectingSubtitleTrackEnablesTextAndPinsTheRequestedTrack() {
        val group =
            TrackGroup(
                Format.Builder().setId("en").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
                Format.Builder().setId("de").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        val selected =
            TrackSelectionParameters.Builder().build().withSubtitleSelection(
                SubtitleSelection.Track(group.getFormat(1).toSubtitleTrackIdentity()),
                listOf(
                    MobileSubtitleTrack(
                        group = group,
                        trackIndex = 1,
                        label = "German",
                        selected = false,
                    ),
                ),
            )

        assertTrue(selected.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in selected.disabledTrackTypes)
        assertEquals(listOf(1), selected.overrides.getValue(group).trackIndices)
    }

    @Test
    fun retainedSubtitleIdentityResolvesAgainstReplacementTracks() {
        val oldGroup =
            TrackGroup(
                Format.Builder().setId("en").setLanguage("en").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
                Format.Builder().setId("de").setLanguage("de").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        val replacementGroup =
            TrackGroup(
                Format.Builder()
                    .setId("en")
                    .setLanguage("en")
                    .setLabel("English")
                    .setSampleMimeType(MimeTypes.TEXT_VTT)
                    .build(),
                Format.Builder()
                    .setId("de")
                    .setLanguage("de")
                    .setLabel("Deutsch")
                    .setSampleMimeType(MimeTypes.TEXT_VTT)
                    .build(),
            )
        val selection = SubtitleSelection.Track(oldGroup.getFormat(1).toSubtitleTrackIdentity())
        val oldParameters =
            TrackSelectionParameters.Builder().build().withSubtitleSelection(
                selection,
                listOf(MobileSubtitleTrack(oldGroup, 1, label = "German", selected = false)),
            )

        val replacementParameters =
            oldParameters.withSubtitleSelection(
                selection,
                listOf(MobileSubtitleTrack(replacementGroup, 1, label = "German", selected = false)),
            )

        assertFalse(oldGroup in replacementParameters.overrides)
        assertEquals(listOf(1), replacementParameters.overrides.getValue(replacementGroup).trackIndices)
    }

    @Test
    fun unmatchedRetainedSubtitleStaysDisabledUntilItsTrackAppears() {
        val selectedFormat =
            Format.Builder()
                .setId("de")
                .setLanguage("de")
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .build()
        val selection = SubtitleSelection.Track(selectedFormat.toSubtitleTrackIdentity())

        val pending =
            TrackSelectionParameters.Builder().build().withSubtitleSelection(selection, emptyList())

        assertFalse(pending.selectTextByDefault)
        assertTrue(C.TRACK_TYPE_TEXT in pending.disabledTrackTypes)
        assertTrue(pending.overrides.isEmpty())

        val replacementGroup = TrackGroup(selectedFormat)
        val resolved =
            pending.withSubtitleSelection(
                selection,
                listOf(MobileSubtitleTrack(replacementGroup, 0, label = "German", selected = false)),
            )

        assertTrue(resolved.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in resolved.disabledTrackTypes)
        assertEquals(listOf(0), resolved.overrides.getValue(replacementGroup).trackIndices)
    }

    @Test
    fun idlessSubtitleIdentityDistinguishesFlagsAndAccessibilityChannel() {
        val forced =
            Format.Builder()
                .setLanguage("en")
                .setLabel("English")
                .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                .setAccessibilityChannel(1)
                .build()
        val full =
            forced.buildUpon()
                .setSelectionFlags(0)
                .setAccessibilityChannel(2)
                .build()
        val forcedIdentity = forced.toSubtitleTrackIdentity()

        assertTrue(forcedIdentity.exactlyMatches(forced))
        assertFalse(forcedIdentity.exactlyMatches(full))
        assertNull(
            listOf(MobileSubtitleTrack(TrackGroup(full), 0, label = "English", selected = false))
                .resolve(forcedIdentity),
        )
    }

    @Test
    fun duplicateSubtitleIdsRequireTheRemainingIdentityToMatch() {
        val selected =
            Format.Builder()
                .setId("subtitle")
                .setLanguage("en")
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .build()
        val duplicate =
            selected.buildUpon()
                .setLanguage("de")
                .build()
        val selectedGroup = TrackGroup(selected)
        val duplicateGroup = TrackGroup(duplicate)

        val resolved =
            listOf(
                MobileSubtitleTrack(duplicateGroup, 0, label = "German", selected = false),
                MobileSubtitleTrack(selectedGroup, 0, label = "English", selected = false),
            ).resolve(selected.toSubtitleTrackIdentity())

        assertEquals(selectedGroup, resolved?.group)
    }
}
