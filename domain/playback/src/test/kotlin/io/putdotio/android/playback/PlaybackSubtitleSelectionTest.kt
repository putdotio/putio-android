package io.putdotio.android.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
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
import io.putdotio.android.PutioFailure

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

        assertFalse(forcedOnly.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in forcedOnly.disabledTrackTypes)
        assertTrue(automatic.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in automatic.disabledTrackTypes)
        // hide_subtitles outranks a retained pick; its picker is gone.
        assertFalse(retained.selectTextByDefault)
        assertTrue(C.TRACK_TYPE_TEXT in retained.disabledTrackTypes)
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
    fun loadingOrFailedAccountSettingsKeepSubtitlesOffAndThePickerStillSelects() {
        val start = AccountSettingsReducer.start()
        val failed =
            AccountSettingsReducer.reduce(
                start.state,
                AccountSettingsEvent.LoadFailed(
                    requireNotNull(start.effect).requestId,
                    AccountSettingsFailure.Putio(PutioFailure.Unexpected(IllegalStateException("offline"))),
                ),
            ).state
        val tracks = listOf(PlaybackSubtitleTrack(TextGroup, 0, label = "English", selected = false))

        for (settings in listOf(start.state, failed)) {
            val startup = startupParameters(settings)
            assertNull("$settings", selectedTextId(startup))
            assertEquals(
                "ordinary",
                selectedTextId(startup.withSubtitleSelection(SubtitleSelection.Track(tracks[0].identity), tracks)),
            )
            assertEquals(
                "ordinary",
                selectedTextId(startup.withSubtitleSelection(SubtitleSelection.Automatic, tracks, CaptionDefaults)),
            )
        }
    }

    @Test
    fun loadedShowSubtitlesDecidesWhetherTheDefaultTrackShows() {
        assertEquals("ordinary", startupTextTrack(loaded(showSubtitles = true)))
        assertNull(startupTextTrack(loaded(showSubtitles = false)))
    }

    @Test
    fun automaticSubtitlesPickTheServerDefaultOverTheDeviceCaptionLanguage() {
        val deviceDefaults = TrackSelectionParameters.Builder().setPreferredTextLanguages("tr").build()
        val flagged = serverTracks(defaultId = "en")
        val tracks = flagged.subtitleTracks()

        val automatic = deviceDefaults.withSubtitleSelection(SubtitleSelection.Automatic, tracks, deviceDefaults)

        assertEquals("en", selectedTextId(automatic, flagged))
    }

    @Test
    fun anOriginalsInFileLanguageOutranksTheDeviceAndTheContainerDefault() {
        val turkishDevice = TrackSelectionParameters.Builder().setPreferredTextLanguages("tr").build()
        val file = serverTracks(defaultId = "tr")
        val tracks = file.subtitleTracks()

        val deviceOnly = turkishDevice.withSubtitleSelection(SubtitleSelection.Automatic, tracks, turkishDevice)
        val ranked = turkishDevice.withSubtitleSelection(
            SubtitleSelection.Automatic,
            tracks,
            turkishDevice,
            inFileLanguage = "eng",
        )

        assertEquals("tr", selectedTextId(deviceOnly, file))
        assertEquals("en", selectedTextId(ranked, file))
    }

    @Test
    fun anUnlabelledServerDefaultStaysWhenNoDeviceLanguageMatches() {
        val deviceDefaults = TrackSelectionParameters.Builder().build()
        val flagged = TrackGroup(
            *listOf("unknown" to true, "en" to false).map { (language, default) ->
                Format.Builder()
                    .setId(language)
                    .setSampleMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(language)
                    .setLabel(if (default) "Unknown" else "English")
                    .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }.toTypedArray(),
        )

        val automatic = deviceDefaults.withSubtitleSelection(
            SubtitleSelection.Automatic,
            flagged.subtitleTracks(),
            deviceDefaults,
        )

        assertEquals("unknown", selectedTextId(automatic, flagged))
    }

    @Test
    fun anUnlabelledDefaultGivesWayToTheDevicesCaptionLanguageAndALabelledOneStays() {
        fun group(defaultLabel: String) = TrackGroup(
            *listOf(Triple("dflt", "und", defaultLabel), Triple("en", "en", "English"), Triple("tr", "tr", "Turkish"))
                .map { (id, language, label) ->
                    Format.Builder()
                        .setId(id)
                        .setSampleMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(language)
                        .setLabel(label)
                        .setSelectionFlags(if (id == "dflt") C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                }.toTypedArray(),
        )
        val turkishDevice = TrackSelectionParameters.Builder().setPreferredTextLanguages("tr").build()

        val unlabelled = group("Unknown")
        val automatic = turkishDevice.withSubtitleSelection(
            SubtitleSelection.Automatic,
            unlabelled.subtitleTracks(),
            turkishDevice,
        )
        assertEquals("tr", selectedTextId(automatic, unlabelled))

        val labelled = group("Blue.Lights.S04E01.srt")
        val kept = turkishDevice.withSubtitleSelection(
            SubtitleSelection.Automatic,
            labelled.subtitleTracks(),
            turkishDevice,
        )
        assertEquals("dflt", selectedTextId(kept, labelled))
    }

    @Test
    fun anUnlabelledDefaultGivesWayToTheSystemCaptionLanguage() {
        val group = TrackGroup(
            *listOf(Triple("dflt", "und", "Unknown"), Triple("en", "en", "English"), Triple("tr", "tr", "Turkish"))
                .map { (id, language, label) ->
                    Format.Builder()
                        .setId(id)
                        .setSampleMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(language)
                        .setLabel(label)
                        .setSelectionFlags(if (id == "dflt") C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                }.toTypedArray(),
        )
        val captioningDefaults =
            TrackSelectionParameters.Builder().setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings().build()

        val automatic = captioningDefaults.withSubtitleSelection(
            SubtitleSelection.Automatic,
            group.subtitleTracks(),
            captioningDefaults,
            captionLanguages = listOf("tr-TR"),
        )

        assertEquals("tr", selectedTextId(automatic, group))
    }

    @Test
    fun anUnlabelledDefaultNeverGivesWayToAForcedTrack() {
        val group = TrackGroup(
            *listOf(Triple("dflt", C.SELECTION_FLAG_DEFAULT, "und"), Triple("en", C.SELECTION_FLAG_FORCED, "en"))
                .plus(Triple("tr", 0, "tr"))
                .map { (id, flags, language) ->
                    Format.Builder()
                        .setId(id)
                        .setSampleMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(language)
                        .setSelectionFlags(flags)
                        .build()
                }.toTypedArray(),
        )
        val englishDevice = TrackSelectionParameters.Builder().setPreferredTextLanguages("en").build()

        val automatic = englishDevice.withSubtitleSelection(
            SubtitleSelection.Automatic,
            group.subtitleTracks(),
            englishDevice,
        )

        assertEquals("dflt", selectedTextId(automatic, group))
    }

    @Test
    fun automaticSubtitlesFollowTheDeviceWhenTheServerNamesNoDefault() {
        val deviceDefaults = TrackSelectionParameters.Builder().setPreferredTextLanguages("tr").build()
        val unflagged = serverTracks(defaultId = null)

        val automatic = deviceDefaults.withSubtitleSelection(
            SubtitleSelection.Automatic,
            unflagged.subtitleTracks(),
            deviceDefaults,
        )

        assertEquals("tr", selectedTextId(automatic, unflagged))
    }

    @Test
    fun arrivingTracksApplyTheServerDefaultOnlyWhileTheAccountAutoSelects() {
        val deviceDefaults = TrackSelectionParameters.Builder().setPreferredTextLanguages("tr").build()
        val flagged = serverTracks(defaultId = "en")
        val tracks = flagged.subtitleTracks()
        fun arrived(policy: SubtitleStartupPolicy, retained: SubtitleSelection? = null): TrackSelectionParameters =
            restoreSubtitleSelection(deviceDefaults, retained, policy)
                .withSubtitleTracks(retained, policy, tracks, deviceDefaults)

        assertEquals("en", selectedTextId(arrived(SubtitleStartupPolicy(true, autoSelectSubtitles = true)), flagged))
        // dont_autoselect_subtitles keeps forced tracks only; the default flag is ignored.
        assertNull(selectedTextId(arrived(SubtitleStartupPolicy(true, autoSelectSubtitles = false)), flagged))
        assertNull(selectedTextId(arrived(SubtitleStartupPolicy(false, autoSelectSubtitles = true)), flagged))
        // The viewer's pick still decides.
        val picked = SubtitleSelection.Track(tracks.single { it.identity.id == "tr" }.identity)
        assertEquals("tr", selectedTextId(arrived(SubtitleStartupPolicy(true, true), picked), flagged))
        // Except under hide_subtitles, which outranks it.
        assertNull(selectedTextId(arrived(SubtitleStartupPolicy(false, true), picked), flagged))
        // A retained Off outlives a recreated player even while the account auto-selects.
        assertNull(selectedTextId(arrived(SubtitleStartupPolicy(true, true), SubtitleSelection.Off), flagged))
    }

    @Test
    fun theDevicesRememberedPickOutranksTheAccountDefaultsButNotHiddenSubtitles() {
        val deviceDefaults = TrackSelectionParameters.Builder().setPreferredTextLanguages("tr").build()
        val flagged = serverTracks(defaultId = "en")
        val tracks = flagged.subtitleTracks()
        fun arrived(
            policy: SubtitleStartupPolicy,
            remembered: RememberedSubtitle?,
            retained: SubtitleSelection? = null,
        ): TrackSelectionParameters =
            restoreSubtitleSelection(deviceDefaults, retained, policy, remembered)
                .withSubtitleTracks(retained, policy, tracks, deviceDefaults, remembered = remembered)
        val turkish = RememberedSubtitle.Language("tr")

        assertEquals("tr", selectedTextId(arrived(SubtitleStartupPolicy(true, true), turkish), flagged))
        assertEquals("tr", selectedTextId(arrived(SubtitleStartupPolicy(true, false), turkish), flagged))
        assertEquals(
            "tr",
            selectedTextId(arrived(SubtitleStartupPolicy(true, true), RememberedSubtitle.Language("tur")), flagged),
        )
        assertEquals(
            "en",
            selectedTextId(arrived(SubtitleStartupPolicy(true, true), RememberedSubtitle.Language("de")), flagged),
        )
        assertNull(selectedTextId(arrived(SubtitleStartupPolicy(true, true), RememberedSubtitle.Off), flagged))
        assertNull(selectedTextId(arrived(SubtitleStartupPolicy(false, true), turkish), flagged))
        val english = SubtitleSelection.Track(tracks.single { it.identity.id == "en" }.identity)
        assertEquals("en", selectedTextId(arrived(SubtitleStartupPolicy(true, true), turkish, english), flagged))
    }

    @Test
    fun aPickIsRememberedOnTheDeviceByLanguage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tracks = serverTracks(defaultId = null).subtitleTracks()
        val english = SubtitleSelection.Track(tracks.single { it.identity.id == "en" }.identity)
        val undetermined = SubtitleSelection.Track(
            Format.Builder()
                .setId("und")
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(C.LANGUAGE_UNDETERMINED)
                .build()
                .toSubtitleTrackIdentity(),
        )

        assertNull(SubtitleLanguageStore(context).remembered)
        SubtitleLanguageStore(context).update(english)
        assertEquals(RememberedSubtitle.Language("en"), SubtitleLanguageStore(context).remembered)
        SubtitleLanguageStore(context).update(SubtitleSelection.Off)
        assertEquals(RememberedSubtitle.Off, SubtitleLanguageStore(context).remembered)
        SubtitleLanguageStore(context).update(undetermined)
        assertNull(SubtitleLanguageStore(context).remembered)
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

    @Test
    fun anUndeterminedSubtitleLanguageGivesAnUnlabelledTrackNoLabel() {
        val text = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage(C.LANGUAGE_UNDETERMINED).build()
        val tracks = Tracks(
            listOf(Tracks.Group(TrackGroup(text), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))),
        ).playbackSubtitleTracks()

        assertEquals(listOf(null), tracks.map { it.label })
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

    /** The text track DefaultTrackSelector picks from [text] for [parameters], or null when none shows. */
    private fun selectedTextId(parameters: TrackSelectionParameters, text: TrackGroup = TextGroup): String? {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val selector = DefaultTrackSelector(context, parameters)
        selector.init({ _ -> }, DefaultBandwidthMeter.getSingletonInstance(context))
        val result =
            selector.selectTracks(
                arrayOf(rendererCapabilities(C.TRACK_TYPE_AUDIO), rendererCapabilities(C.TRACK_TYPE_TEXT)),
                TrackGroupArray(TrackGroup(AudioFormat), text),
                MediaSource.MediaPeriodId(Any()),
                Timeline.EMPTY,
            )
        return result.selections[1]?.selectedFormat?.id.also { selector.release() }
    }

    /** put.io's subtitles for a Turkish-locale device: the account ranks English first. */
    private fun serverTracks(defaultId: String?): TrackGroup =
        TrackGroup(
            *listOf("tr" to "Turkish", "en" to "English").map { (language, label) ->
                Format.Builder()
                    .setId(language)
                    .setSampleMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(language)
                    .setLabel(label)
                    .setSelectionFlags(if (language == defaultId) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }.toTypedArray(),
        )

    private fun TrackGroup.subtitleTracks(): List<PlaybackSubtitleTrack> =
        (0 until length).map { PlaybackSubtitleTrack(this, it, label = getFormat(it).label, selected = false) }

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
