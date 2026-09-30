package io.putdotio.android.playback

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
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsState
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
class PlaybackSubtitleSelectionTest {
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
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true),
            )

        assertFalse(restored.selectTextByDefault)
        assertTrue(C.TRACK_TYPE_TEXT in restored.disabledTrackTypes)

        val group =
            TrackGroup(
                Format.Builder().setId("en").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
                Format.Builder().setId("de").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        val track =
            PlaybackSubtitleTrack(
                group = group,
                trackIndex = 1,
                label = "German",
                selected = false,
            )
        val restoredSelection =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = SubtitleSelection.Track(track.identity),
                startupPolicy = null,
            ).withSubtitleSelection(SubtitleSelection.Track(track.identity), listOf(track))

        assertEquals(listOf(1), restoredSelection.overrides.getValue(group).trackIndices)
    }

    @Test
    fun forcedOnlyStartupReadsAsOffUntilTheViewerPicksAutomatic() {
        val captionsDisabled =
            TrackSelectionParameters.Builder()
                .setSelectTextByDefault(false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()

        val forcedOnly =
            restoreSubtitleSelection(
                captionsDisabled,
                retained = null,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false),
            )

        assertFalse(C.TRACK_TYPE_TEXT in forcedOnly.disabledTrackTypes)
        assertFalse(forcedOnly.subtitlesEnabled(emptyList()))

        val automatic = forcedOnly.withSubtitleSelection(SubtitleSelection.Automatic, emptyList())
        assertEquals(0, automatic.ignoredTextSelectionFlags)
        assertTrue(automatic.selectTextByDefault)
    }

    @Test
    fun accountSubtitlePolicySeedsPlayerDefaults() {
        val defaults = TrackSelectionParameters.Builder().build()
        val hidden =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = true),
            )
        val forcedOnly =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false),
            )
        val automatic =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true),
            )
        val retained =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = SubtitleSelection.Automatic,
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
        val forcedOnly =
            restoreSubtitleSelection(
                defaults = CaptionDefaults,
                retained = null,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false),
            )
        val automatic =
            forcedOnly
                .withSubtitleSelection(SubtitleSelection.Off, emptyList(), CaptionDefaults)
                .withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), CaptionDefaults)

        assertEquals("forced", selectedTextId(forcedOnly))
        assertEquals("ordinary", selectedTextId(automatic))
    }

    @Test
    fun subtitlesStayOffWhileAccountSettingsLoad() {
        val loading = AccountSettingsReducer.start().state

        assertNull(startupTextTrack(loading))
    }

    @Test
    fun failedAccountSettingsKeepSubtitlesOffAndThePickerStillSelects() {
        val start = AccountSettingsReducer.start()
        val failed =
            AccountSettingsReducer.reduce(
                start.state,
                AccountSettingsEvent.LoadFailed(
                    requireNotNull(start.effect).requestId,
                    AccountSettingsFailure.Unexpected(IllegalStateException("offline")),
                ),
            ).state
        val startup = startupParameters(failed)
        val tracks = listOf(PlaybackSubtitleTrack(TextGroup, 0, label = "English", selected = false))

        assertNull(selectedTextId(startup))
        assertEquals(
            "ordinary",
            selectedTextId(startup.withSubtitleSelection(SubtitleSelection.Track(tracks[0].identity), tracks)),
        )
        assertEquals(
            "ordinary",
            selectedTextId(startup.withSubtitleSelection(SubtitleSelection.Automatic, tracks, CaptionDefaults)),
        )
    }

    @Test
    fun loadedHideSubtitlesKeepsSubtitlesOff() {
        assertNull(startupTextTrack(loaded(showSubtitles = false)))
    }

    @Test
    fun loadedShowSubtitlesSelectsTheDefaultTrack() {
        assertEquals("ordinary", startupTextTrack(loaded(showSubtitles = true)))
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
                    PlaybackSubtitleTrack(
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
                listOf(PlaybackSubtitleTrack(oldGroup, 1, label = "German", selected = false)),
            )

        val replacementParameters =
            oldParameters.withSubtitleSelection(
                selection,
                listOf(PlaybackSubtitleTrack(replacementGroup, 1, label = "German", selected = false)),
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
                listOf(PlaybackSubtitleTrack(replacementGroup, 0, label = "German", selected = false)),
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
            listOf(PlaybackSubtitleTrack(TrackGroup(full), 0, label = "English", selected = false))
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
                PlaybackSubtitleTrack(duplicateGroup, 0, label = "German", selected = false),
                PlaybackSubtitleTrack(selectedGroup, 0, label = "English", selected = false),
            ).resolve(selected.toSubtitleTrackIdentity())

        assertEquals(selectedGroup, resolved?.group)
    }

    private fun loaded(showSubtitles: Boolean): AccountSettingsState {
        val start = AccountSettingsReducer.start()
        return AccountSettingsReducer.reduce(
            start.state,
            AccountSettingsEvent.LoadSucceeded(
                requireNotNull(start.effect).requestId,
                AccountSettingsPreferences(
                    historyEnabled = true,
                    trashEnabled = true,
                    showSubtitles = showSubtitles,
                    autoSelectSubtitles = true,
                ),
            ),
        ).state
    }

    private fun startupParameters(settings: AccountSettingsState): TrackSelectionParameters =
        restoreSubtitleSelection(CaptionDefaults, retained = null, startupPolicy = settings.subtitleStartupPolicy())

    private fun startupTextTrack(settings: AccountSettingsState): String? = selectedTextId(startupParameters(settings))

    /** The text track DefaultTrackSelector picks for [parameters], or null when none shows. */
    private fun selectedTextId(parameters: TrackSelectionParameters): String? {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val selector = DefaultTrackSelector(context, parameters)
        selector.init({ _ -> }, DefaultBandwidthMeter.getSingletonInstance(context))
        val result =
            selector.selectTracks(
                arrayOf(rendererCapabilities(C.TRACK_TYPE_AUDIO), rendererCapabilities(C.TRACK_TYPE_TEXT)),
                TrackGroupArray(TrackGroup(AudioFormat), TextGroup),
                MediaSource.MediaPeriodId(Any()),
                Timeline.EMPTY,
            )
        return result.selections[1]?.selectedFormat?.id.also { selector.release() }
    }

    private companion object {
        // A device whose caption settings ask for English captions.
        val CaptionDefaults: TrackSelectionParameters =
            TrackSelectionParameters.Builder()
                .setPreferredTextLanguages("en")
                .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
                .setPreferredTextLabels("English")
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED)
                .setSelectUndeterminedTextLanguage(true)
                .build()
        val AudioFormat: Format =
            Format.Builder()
                .setId("audio")
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setLanguage("en")
                .build()
        val TextGroup =
            TrackGroup(
                Format.Builder()
                    .setId("ordinary")
                    .setSampleMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage("en")
                    .setLabel("English")
                    .setRoleFlags(C.ROLE_FLAG_CAPTION)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build(),
                Format.Builder()
                    .setId("forced")
                    .setSampleMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage("en")
                    .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                    .build(),
            )
    }
}
