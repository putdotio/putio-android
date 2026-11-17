package io.putdotio.android.playback

import android.content.Context
import android.content.res.Resources
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItemId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
@UnstableApi
class PlaybackTrackLabelsTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun unlabelledTracksInAFileTakeTheirFormatElseANumber() {
        val tracks = tracks(
            embedded("1", "en", MimeTypes.APPLICATION_SUBRIP),
            embedded("2", null, MimeTypes.APPLICATION_SUBRIP),
            embedded("3", null, MimeTypes.TEXT_SSA),
            embedded("4", null, null),
        )

        assertEquals(
            listOf("English", "SRT", "SSA", "Subtitle 4"),
            resources.subtitleTrackLabels(tracks, emptyList(), inFile = true),
        )
        assertEquals(
            listOf("SRT", "Embedded", "Embedded", "Embedded"),
            tracks.map { resources.subtitleTrackDetails(it, null, inFile = true) },
        )
    }

    @Test
    fun unlabelledTracksOfOneFormatTakeTheirTrackNumbers() {
        val tracks = tracks(
            embedded("1", null, MimeTypes.APPLICATION_SUBRIP),
            embedded("2", null, MimeTypes.APPLICATION_SUBRIP),
            embedded("3", "tr", MimeTypes.APPLICATION_SUBRIP),
        )

        assertEquals(
            listOf("SRT (track 1)", "SRT (track 2)", "Turkish"),
            resources.subtitleTrackLabels(tracks, emptyList(), inFile = true),
        )
    }

    @Test
    fun anUnlabelledManifestTrackNamesItsFormatOnce() {
        val track = tracks(embedded("1", null, MimeTypes.TEXT_VTT)).single()

        assertEquals(listOf("VTT"), resources.subtitleTrackLabels(listOf(track), emptyList(), inFile = false))
        assertNull(resources.subtitleTrackDetails(track, null, inFile = false))
    }

    @Test
    fun anOriginalFileKeepsItsOwnTracksInsteadOfPutiosCopies() {
        val subtitles = PlaybackSubtitles.Sidecar(
            listOf(
                subtitle("mkv-en", "mkv"),
                subtitle("mkv-en-2", "mkv"),
                subtitle("os-en", "opensubtitles"),
                subtitle("folder-en", "folder"),
            ),
        )

        val original = source(PlaybackSourceKind.ORIGINAL, subtitles)
        assertEquals(listOf("os-en", "folder-en"), original.playerSubtitles().sidecarTracks().map { it.key })
        val configurations = requireNotNull(original.toMediaItem("episode.mkv").localConfiguration)
            .subtitleConfigurations
        assertEquals(listOf("os-en", "folder-en"), configurations.map { it.id })
        assertEquals(listOf(0, 0), configurations.map { it.selectionFlags })
        assertEquals("eng", original.toMediaItem("episode.mkv").inFileSubtitleLanguage())
        assertNull(source(PlaybackSourceKind.MP4, subtitles).toMediaItem("episode.mp4").inFileSubtitleLanguage())
        val rankedExternal = PlaybackSubtitles.Sidecar(subtitles.tracks.drop(2))
        val externalItem = source(PlaybackSourceKind.ORIGINAL, rankedExternal).toMediaItem("episode.mkv")
        assertNull(externalItem.inFileSubtitleLanguage())

        listOf(PlaybackSourceKind.MP4, PlaybackSourceKind.HLS).forEach { kind ->
            assertEquals(subtitles, source(kind, subtitles).playerSubtitles())
        }
        val copiesOnly = PlaybackSubtitles.Sidecar(listOf(subtitle("mkv-en", "mkv")))
        assertEquals(PlaybackSubtitles.None, source(PlaybackSourceKind.ORIGINAL, copiesOnly).playerSubtitles())
    }

    @Test
    fun manifestTracksTakePutiosLabelsWhereTheirLanguageCountsAgree() {
        val tracks = tracks(
            manifest("subs0:English 1", "en"),
            manifest("subs0:English 2", "en"),
            manifest("subs0:Turkish", "tr"),
        )
        val listed = listOf(
            subtitle("en-in-file", "mkv", name = "Sample.Show.en.srt"),
            subtitle("en-styled", "folder", name = "Sample.Show.en.ass").copy(format = "ass"),
            subtitle("en-found", "opensubtitles_rest", name = "sample_show_s01e01.SRT"),
            subtitle("tr-found", "opensubtitles_rest", name = "Sample.Show.tr.vtt")
                .copy(language = "Turkish", languageCode = "tur", format = "webvtt"),
        )

        val keyed = listed.keyedToManifest(tracks)

        assertEquals(
            listOf("English - Sample.Show.en", "English - sample_show_s01e01", "Turkish - Sample.Show.tr"),
            resources.subtitleTrackLabels(tracks, keyed, inFile = true),
        )
        assertEquals(
            listOf(SubtitleOrigin.File, SubtitleOrigin.OpenSubtitles, SubtitleOrigin.OpenSubtitles),
            tracks.map { it.putioSubtitle(keyed).origin() },
        )
        assertEquals(
            listOf("SRT", "OpenSubtitles", "OpenSubtitles"),
            tracks.map { resources.subtitleTrackDetails(it, it.putioSubtitle(keyed), inFile = true) },
        )
    }

    @Test
    fun aLanguageWhoseCountsDisagreeKeepsTheManifestNamesAndTheOthersStillKey() {
        val tracks = tracks(
            manifest("subs0:English 1", "en"),
            manifest("subs0:English 2", "en"),
            manifest("subs0:Turkish", "tr"),
        )
        val listed = listOf(
            subtitle("en-found", "opensubtitles_rest", name = "Sample.Show.en.srt"),
            subtitle("tr-found", "opensubtitles_rest", name = "Sample.Show.tr.srt")
                .copy(language = "Turkish", languageCode = "tur"),
        )

        val sources = subtitleSources(PlaybackSubtitles.Embedded, listed, tracks)

        assertEquals(listOf("Sample.Show.tr.srt"), sources.sidecar.map { it.name })
        assertEquals(false, sources.originsKnown)
        assertEquals(
            listOf("English 1", "English 2", "Turkish - Sample.Show.tr"),
            resources.subtitleTrackLabels(tracks, sources.sidecar, sources.originsKnown),
        )
    }

    @Test
    fun manifestTracksWithoutIdsAreKeyedByTheirNames() {
        val tracks = tracks(unnamed("English 1", "en"), unnamed("English 2", "en"))
        val listed = listOf(subtitle("en-in-file", "mkv"), subtitle("en-found", "opensubtitles_rest"))

        val keyed = listed.keyedToManifest(tracks)

        assertEquals(
            listOf(SubtitleOrigin.File, SubtitleOrigin.OpenSubtitles),
            tracks.map { it.putioSubtitle(keyed).origin() },
        )
        assertEquals(
            emptyList<PlaybackSubtitle>(),
            listed.keyedToManifest(tracks(unnamed("English", "en"), unnamed("English", "en"))),
        )
    }

    @Test
    fun anUndeterminedSubtitleKeysToTheManifestsUnknownTrack() {
        val tracks = tracks(manifest("subs0:Unknown", "und"))
        val listed = listOf(subtitle("und", "folder", name = "notes.srt").copy(language = "", languageCode = "und"))

        assertEquals(
            listOf("Unknown - notes"),
            resources.subtitleTrackLabels(tracks, listed.keyedToManifest(tracks), inFile = true),
        )
    }

    @Test
    fun directPlaySidecarsTakePutiosLabels() {
        val sidecar = subtitle("os-en", "opensubtitles", name = "Sample.Show.en.srt")
        val subtitles = PlaybackSubtitles.Sidecar(listOf(sidecar))
        val track = tracks(embedded("0:os-en", "en", MimeTypes.APPLICATION_SUBRIP))

        val sources = subtitleSources(subtitles, null, track)

        assertEquals(listOf("English - Sample.Show.en"), resources.subtitleTrackLabels(track, sources.sidecar, true))
    }

    @Test
    fun labelsDropOnlyASubtitleExtension() {
        val track = tracks(embedded("0:a", "en", MimeTypes.APPLICATION_SUBRIP), embedded("0:b", "en", null))
        val sidecar = listOf(
            subtitle("a", "folder", name = "Sample.Show.720p.webvtt"),
            subtitle("b", "folder", name = "Sample.Show.720p"),
        )

        assertEquals(
            listOf("English - Sample.Show.720p", "English - Sample.Show.720p"),
            resources.subtitleTrackLabels(track, sidecar, inFile = true).map { it.substringBefore(" (") },
        )
    }

    @Test
    fun aFailedSubtitleListIsAskedForAgainABoundedNumberOfTimes() = runTest {
        val listed = listOf(subtitle("en", "folder"))
        val target = PlaybackTarget(FilesItemId(42L), "Sample.Show.mkv")
        val offline = PlaybackRepositoryResult.Failure(
            PlaybackFailure.Putio(PutioFailure.Unexpected(IllegalStateException("offline"))),
        )
        var calls = 0
        val recovers = findManifestSubtitles(target, {
            calls++
            if (calls < 3) offline else PlaybackRepositoryResult.Success(listed)
        }) {}
        assertEquals(listed, recovers)
        assertEquals(3, calls)

        calls = 0
        assertNull(findManifestSubtitles(target, { calls++; offline }) {})
        assertEquals(3, calls)

        calls = 0
        var signedOut = false
        val rejected = PlaybackRepositoryResult.Failure(
            PlaybackFailure.Putio(
                PutioFailure.AuthenticationRequired(io.putdotio.sdk.errors.PutioConfigurationException("401")),
            ),
        )
        assertNull(findManifestSubtitles(target, { calls++; rejected }) { signedOut = true })
        assertEquals(1, calls)
        assertEquals(true, signedOut)
    }

    private fun tracks(vararg formats: Format): List<PlaybackSubtitleTrack> {
        val group = TrackGroup("text", *formats)
        return formats.indices.map { PlaybackSubtitleTrack(group, it, label = null, selected = false) }
    }

    private fun embedded(id: String, language: String?, codecs: String?): Format =
        Format.Builder()
            .setId(id)
            .setLanguage(language ?: C.LANGUAGE_UNDETERMINED)
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(codecs)
            .build()

    private fun unnamed(label: String, language: String): Format =
        Format.Builder()
            .setLanguage(language)
            .setLabel(label)
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .build()

    private fun manifest(id: String, language: String): Format =
        Format.Builder()
            .setId(id)
            .setLanguage(language)
            .setLabel(id.substringAfter(':'))
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.TEXT_VTT)
            .build()

    private fun source(kind: PlaybackSourceKind, subtitles: PlaybackSubtitles) =
        PlaybackSource(
            fileId = 42L,
            kind = kind,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/stream"),
            startFromSeconds = 0.0,
            subtitles = subtitles,
        )

    private fun subtitle(key: String, source: String, name: String = "episode.srt") =
        PlaybackSubtitle(
            format = "srt",
            key = key,
            language = "English",
            languageCode = "eng",
            name = name,
            source = source,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/subtitles/$key"),
        )
}
