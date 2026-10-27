package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.account.AccountDisk
import io.putdotio.sdk.account.AccountDownloadToken
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCastMediaTest {
    @Test
    fun hlsGoesWithTheDownloadTokenInPlaceOfTheSessionToken() {
        val media = source(
            PlaybackSourceKind.HLS,
            "https://api.put.io/v2/files/42/hls/media.m3u8?subtitle_languages=eng&oauth_token=$ACCESS",
            PlaybackSubtitles.Embedded,
        ).castable(Video)

        assertEquals(
            "https://api.put.io/v2/files/42/hls/media.m3u8?subtitle_languages=eng&oauth_token=$DOWNLOAD",
            media.url,
        )
        assertEquals("application/x-mpegURL", media.contentType)
        // put.io's playlists carry no stable duration; the receiver reads the stream.
        assertFalse(media.buffered)
        // The playlist carries its own subtitle renditions.
        assertTrue(media.subtitles.isEmpty())
        assertEquals("episode.mkv", media.title)
        assertFalse(media.toString().contains(DOWNLOAD))
    }

    @Test
    fun sidecarSubtitlesGoAsWebVttWithTheDownloadTokenOnly() {
        // put.io lists sidecar subtitle URLs with the caller's own access token in the query.
        val media = source(
            PlaybackSourceKind.MP4,
            "https://api.put.io/v2/files/42/mp4/download?oauth_token=$ACCESS",
            PlaybackSubtitles.Sidecar(
                listOf(
                    subtitle("first", "https://api.put.io/v2/files/42/subtitles/first?oauth_token=$ACCESS"),
                    subtitle("elsewhere", "https://subtitles.example.com/42.srt"),
                    subtitle("second", "https://api.put.io/v2/files/42/subtitles/second?format=srt"),
                ),
            ),
        ).castable(Video.copy(durationSeconds = 90.5), activeSubtitleKey = "second")

        assertEquals("video/mp4", media.contentType)
        assertTrue(media.buffered)
        assertEquals(90_500L, media.durationMillis)
        assertEquals(
            listOf(
                "https://api.put.io/v2/files/42/subtitles/first?format=webvtt&oauth_token=$DOWNLOAD",
                "https://api.put.io/v2/files/42/subtitles/second?format=webvtt&oauth_token=$DOWNLOAD",
            ),
            media.subtitles.map { it.url },
        )
        assertEquals(listOf(1L, 2L), media.subtitles.map { it.id })
        assertEquals(2L, media.activeSubtitleId)
        assertFalse((media.subtitles.map { it.url } + media.url).any { it.contains(ACCESS) })
    }

    @Test
    fun anEscapedCredentialNameIsReplacedToo() {
        val media = source(
            PlaybackSourceKind.MP4,
            "https://api.put.io/v2/files/42/mp4/download?oauth%5Ftoken=$ACCESS&%6Fauth_token=$ACCESS",
            PlaybackSubtitles.None,
        ).castable(Video.copy(posterUrl = "https://api.put.io/v2/files/42/screenshot?oauth_token=$ACCESS"))

        assertEquals("https://api.put.io/v2/files/42/mp4/download?oauth_token=$DOWNLOAD", media.url)
        // A poster that carries a credential is left out rather than sent.
        assertNull(media.posterUrl)
    }

    @Test
    fun aDownloadsTokenFreeApiUrlTakesTheDownloadToken() {
        val media = source(
            PlaybackSourceKind.HLS,
            "https://api.put.io/v2/files/42/hls/media.m3u8",
            PlaybackSubtitles.Embedded,
        ).castable(Video)

        assertEquals("https://api.put.io/v2/files/42/hls/media.m3u8?oauth_token=$DOWNLOAD", media.url)
    }

    @Test
    fun onlyPutioApiUrlsReachAReceiver() {
        listOf(
            "http://api.put.io/v2/files/42/stream",
            "https://cdn.example.com/v2/files/42/stream",
        ).forEach { url ->
            val source = source(PlaybackSourceKind.ORIGINAL, url, PlaybackSubtitles.None)
            assertNull(url, source.toCastMedia(Video, DOWNLOAD))
        }
    }

    @Test
    fun audioGoesAsTheOriginalWithItsOwnContentType() {
        val stream = "https://api.put.io/v2/files/42/stream"
        val original = source(PlaybackSourceKind.ORIGINAL, stream, PlaybackSubtitles.None)
        val flac = original.castable(PlaybackTarget(FilesItemId(42L), "Tree Rings.FLAC", PlaybackMediaType.AUDIO))
        val unknown = original.castable(PlaybackTarget(FilesItemId(42L), "episode", PlaybackMediaType.AUDIO))

        assertEquals("audio/flac", flac.contentType)
        assertEquals(PlaybackMediaType.AUDIO, flac.mediaType)
        assertEquals("audio/mpeg", unknown.contentType)
    }

    @Test
    fun theRepositoryCastsWithTheAccountsDownloadToken() = runBlocking {
        val hls = source(
            PlaybackSourceKind.HLS,
            "https://api.put.io/v2/files/42/hls/media.m3u8?oauth_token=$ACCESS",
            PlaybackSubtitles.Embedded,
        )
        fun repository(token: AccountDownloadToken?) =
            SdkPlaybackRepository(
                playbackPreference = { io.putdotio.sdk.files.PlaybackPreference.HLS },
                loadAccount = { account(token) },
                resolvePlayback = { error("Casting reads no playback") },
            )

        val ready = repository(AccountDownloadToken(DOWNLOAD)).castMedia(Video, hls)
        val missing = repository(null).castMedia(Video, hls)
        val uncastable = repository(AccountDownloadToken(DOWNLOAD))
            .castMedia(Video, source(PlaybackSourceKind.HLS, "https://cdn.example.com/a.m3u8", PlaybackSubtitles.None))

        assertTrue(((ready as PlaybackRepositoryResult.Success).value).url.endsWith("oauth_token=$DOWNLOAD"))
        // Never the session's access token in its place.
        assertTrue((missing as PlaybackRepositoryResult.Failure).failure is PlaybackFailure.MediaCredentialUnavailable)
        assertTrue((uncastable as PlaybackRepositoryResult.Failure).failure is PlaybackFailure.MediaUnsupported)
    }

    private fun PlaybackSource.castable(target: PlaybackTarget, activeSubtitleKey: String? = null) =
        requireNotNull(toCastMedia(target, DOWNLOAD, activeSubtitleKey))

    private fun source(kind: PlaybackSourceKind, url: String, subtitles: PlaybackSubtitles) =
        PlaybackSource(
            fileId = 42L,
            kind = kind,
            url = PutioCredentialUrl.of(url),
            startFromSeconds = 0.0,
            subtitles = subtitles,
        )

    private fun subtitle(key: String, url: String) =
        PlaybackSubtitle(
            format = "srt",
            key = key,
            language = "English",
            languageCode = "eng",
            name = "$key.srt",
            source = "opensubtitles",
            url = PutioCredentialUrl.of(url),
        )

    private fun account(token: AccountDownloadToken?) =
        AccountInfo(
            userId = 1L,
            username = "test",
            mail = "test@example.com",
            avatarUrl = "https://example.com/avatar.png",
            disk = AccountDisk(available = 1L, size = 2L, used = 1L),
            settings = AccountSettings(sortBy = "NAME_ASC", useStartFrom = true),
            accountStatus = "active",
            downloadToken = token,
        )

    private companion object {
        const val ACCESS = "session-access-token"
        const val DOWNLOAD = "download-token"
        val Video = PlaybackTarget(FilesItemId(42L), "episode.mkv")
    }
}
