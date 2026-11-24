package io.putdotio.android.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
class PlaybackSubtitleDefaultKeyTest {
    @Test
    fun nonFirstDefaultKeyMarksOnlyThatSidecarTrackDefault() {
        val subtitles = PlaybackSubtitles.Sidecar(
            tracks = listOf(
                subtitle("en", "opensubtitles", "eng"),
                subtitle("de", "opensubtitles", "deu"),
                subtitle("tr", "opensubtitles", "tur"),
            ),
            defaultKey = "de",
        )

        val item = source(PlaybackSourceKind.MP4, subtitles).toMediaItem("episode.mp4")
        val configs = requireNotNull(item.localConfiguration).subtitleConfigurations

        assertEquals(listOf("en", "de", "tr"), configs.map { it.id })
        assertEquals(listOf(0, C.SELECTION_FLAG_DEFAULT, 0), configs.map { it.selectionFlags })
    }

    @Test
    fun nullDefaultKeyMarksNoSidecarTrackDefault() {
        val mp4Subtitles = PlaybackSubtitles.Sidecar(
            tracks = listOf(
                subtitle("en", "opensubtitles", "eng"),
                subtitle("de", "opensubtitles", "deu"),
            ),
            defaultKey = null,
        )
        val mp4Configs = requireNotNull(
            source(PlaybackSourceKind.MP4, mp4Subtitles).toMediaItem("episode.mp4").localConfiguration,
        ).subtitleConfigurations
        assertEquals(listOf(0, 0), mp4Configs.map { it.selectionFlags })

        val originalSubtitles = PlaybackSubtitles.Sidecar(
            tracks = listOf(
                subtitle("mkv-en", "mkv", "eng"),
                subtitle("os-en", "opensubtitles", "eng"),
            ),
            defaultKey = null,
        )
        val originalItem = source(PlaybackSourceKind.ORIGINAL, originalSubtitles).toMediaItem("episode.mkv")
        val originalConfigs = requireNotNull(originalItem.localConfiguration).subtitleConfigurations
        assertEquals(listOf(0), originalConfigs.map { it.selectionFlags })
        assertNull(originalItem.inFileSubtitleLanguage())
    }

    @Test
    fun originalWithNonFirstMkvDefaultSetsInFileLanguageAndNoExternalDefault() {
        val osEn = subtitle("os-en", "opensubtitles", "eng")
        val mkvTr = subtitle("mkv-tr", "mkv", "tur")
        val folderDe = subtitle("folder-de", "folder", "deu")
        val source = source(
            PlaybackSourceKind.ORIGINAL,
            PlaybackSubtitles.Sidecar(listOf(osEn, mkvTr, folderDe), defaultKey = "mkv-tr"),
        )

        assertEquals(PlaybackSubtitles.Sidecar(listOf(osEn, folderDe), defaultKey = null), source.playerSubtitles())
        val item = source.toMediaItem("episode.mkv")
        val configs = requireNotNull(item.localConfiguration).subtitleConfigurations
        assertEquals("tur", item.inFileSubtitleLanguage())
        assertEquals(listOf("os-en", "folder-de"), configs.map { it.id })
        assertEquals(listOf(0, 0), configs.map { it.selectionFlags })
    }

    @Test
    fun originalWithExternalDefaultMarksThatExternalTrackAndLeavesInFileLanguageNull() {
        val mkvEn = subtitle("mkv-en", "mkv", "eng")
        val osEn = subtitle("os-en", "opensubtitles", "eng")
        val folderTr = subtitle("folder-tr", "folder", "tur")
        val source = source(
            PlaybackSourceKind.ORIGINAL,
            PlaybackSubtitles.Sidecar(listOf(mkvEn, osEn, folderTr), defaultKey = "folder-tr"),
        )

        assertEquals(
            PlaybackSubtitles.Sidecar(listOf(osEn, folderTr), defaultKey = "folder-tr"),
            source.playerSubtitles(),
        )
        val item = source.toMediaItem("episode.mkv")
        val configs = requireNotNull(item.localConfiguration).subtitleConfigurations
        assertNull(item.inFileSubtitleLanguage())
        assertEquals(listOf("os-en", "folder-tr"), configs.map { it.id })
        assertEquals(listOf(0, C.SELECTION_FLAG_DEFAULT), configs.map { it.selectionFlags })
    }

    private fun source(kind: PlaybackSourceKind, subtitles: PlaybackSubtitles) =
        PlaybackSource(
            fileId = 42L,
            kind = kind,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/stream"),
            startFromSeconds = 0.0,
            subtitles = subtitles,
        )

    private fun subtitle(key: String, source: String, languageCode: String) =
        PlaybackSubtitle(
            format = "srt",
            key = key,
            language = languageCode,
            languageCode = languageCode,
            name = "$key.srt",
            source = source,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/subtitles/$key"),
        )
}
