package io.putdotio.android

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileVideoPlayerScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun conversionStateOffersRefreshAndBack() {
        var retries = 0
        var backs = 0
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = state(PlaybackContent.Conversion(PlaybackConversionState.Converting(42.0))),
                    onRetry = { retries += 1 },
                    onPlayerFailure = { _, _ -> },
                    onBack = { backs += 1 },
                )
            }
        }

        compose.onNodeWithText("Conversion is in progress. 42%").assertIsDisplayed()
        compose.onNodeWithText("Check again").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, retries)
        assertEquals(1, backs)
    }

    @Test
    fun unsupportedStateIsExplicitAndDoesNotOfferRetry() {
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = state(PlaybackContent.Unsupported(PutioFileType.TEXT)),
                    onRetry = { error("Retry must not be offered") },
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Can’t play this file").assertIsDisplayed()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
    }

    @Test
    fun expiredPlaybackAccessOffersCredentialRefresh() {
        var retries = 0
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state =
                        state(
                            PlaybackContent.Failed(
                                PlaybackFailure.MediaCredentialUnavailable(IllegalStateException("expired")),
                            ),
                        ),
                    onRetry = { retries += 1 },
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Playback access expired. Retry to refresh it.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    @UnstableApi
    fun mediaItemPreservesHlsMetadataAndKnownSidecarSubtitles() {
        val source = PlaybackSource(
            fileId = Target.fileId.value,
            kind = PlaybackSourceKind.HLS,
            url = credentialUrl("https://api.put.io/v2/files/42/hls/media.m3u8?token=credential"),
            startFromSeconds = 12.5,
            subtitles =
                PlaybackSubtitles.Sidecar(
                    listOf(
                        subtitle("English", "en", "srt"),
                        subtitle("Unknown", "und", "future-format"),
                    ),
                ),
        )

        val preparedPlayback = source.preparePlayback(Target.name)
        val item = preparedPlayback.mediaItem
        val local = requireNotNull(item.localConfiguration)

        assertEquals(12_500L, preparedPlayback.startPositionMillis)
        assertEquals(MimeTypes.APPLICATION_M3U8, local.mimeType)
        assertEquals("episode.mkv", item.mediaMetadata.title)
        assertEquals(1, local.subtitleConfigurations.size)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, local.subtitleConfigurations.single().mimeType)
        assertEquals("en", local.subtitleConfigurations.single().language)
        assertEquals(C.SELECTION_FLAG_DEFAULT, local.subtitleConfigurations.single().selectionFlags)
        assertEquals(54_321L, source.preparePlayback(Target.name, 54_321L).startPositionMillis)
    }

    @Test
    fun mp4MediaItemLetsMedia3InferTheContainer() {
        val source = PlaybackSource(
            fileId = Target.fileId.value,
            kind = PlaybackSourceKind.MP4,
            url = credentialUrl("https://api.put.io/v2/files/42/mp4/download?token=credential"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )

        assertNull(source.toMediaItem(Target.name).localConfiguration?.mimeType)
    }

    private fun state(content: PlaybackContent): PlaybackState =
        PlaybackState(
            target = Target,
            content = content,
            nextRequestValue = 2L,
        )

    private fun subtitle(
        name: String,
        languageCode: String,
        format: String,
    ): PlaybackSubtitle =
        PlaybackSubtitle(
            format = format,
            key = languageCode,
            language = name,
            languageCode = languageCode,
            name = name,
            source = "put.io",
            url = credentialUrl("https://api.put.io/v2/subtitles/$languageCode?token=credential"),
        )

    // Credential URLs can only be minted by the SDK resolver in production.
    private fun credentialUrl(value: String): PutioCredentialUrl =
        PutioCredentialUrl::class.java
            .getDeclaredConstructor(String::class.java)
            .newInstance(value)

    private companion object {
        val Target = PlaybackTarget(FilesItemId(42L), "episode.mkv")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
class MobileVideoPlayerCodecTest {
    @Test
    fun emulatorCodecsDemoteGoldfishDecoders() {
        assertTrue(requiresEmulatorCodecWorkaround(37, "ranchu"))
        assertTrue(requiresEmulatorCodecWorkaround(37, "goldfish"))
        assertFalse(requiresEmulatorCodecWorkaround(36, "ranchu"))
        assertFalse(requiresEmulatorCodecWorkaround(37, "tensor"))
        assertEquals(1, emulatorCodecPriority("c2.goldfish.h264.decoder"))
        assertEquals(0, emulatorCodecPriority("c2.android.avc.decoder"))
        assertEquals(SURFACE_TYPE_TEXTURE_VIEW, playbackSurfaceType(37, "ranchu"))
        assertEquals(SURFACE_TYPE_SURFACE_VIEW, playbackSurfaceType(36, "ranchu"))
        assertEquals(SURFACE_TYPE_SURFACE_VIEW, playbackSurfaceType(37, "tensor"))
    }

    @Test
    fun mediaRequestUnauthorizedRefreshesThePlaybackCredential() {
        val dataSpec = DataSpec(Uri.parse("https://example.com/video.mp4"))
        val response =
            HttpDataSource.InvalidResponseCodeException(
                401,
                "Unauthorized",
                IOException("rejected"),
                emptyMap(),
                dataSpec,
                ByteArray(0),
            )

        assertTrue(
            IllegalStateException("player failed", response).toMediaRequestFailureOrNull() is
                PlaybackFailure.MediaCredentialUnavailable,
        )
    }

    @Test
    fun mediaRequestTransportFailureReportsNetworkUnavailable() {
        val dataSpec = DataSpec(Uri.parse("https://example.com/video.mp4"))
        val transport =
            HttpDataSource.HttpDataSourceException(
                IOException("offline"),
                dataSpec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )

        assertTrue(
            IllegalStateException("player failed", transport).toMediaRequestFailureOrNull() is
                PlaybackFailure.NetworkUnavailable,
        )
    }

    @Test
    fun nonNetworkDataSourceFailureUsesGenericRecovery() {
        val dataSpec = DataSpec(Uri.parse("https://example.com/video.mp4"))
        val dataSourceFailure =
            HttpDataSource.HttpDataSourceException(
                IOException("cleartext rejected"),
                dataSpec,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        val error =
            PlaybackException(
                "player failed",
                dataSourceFailure,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            )

        assertTrue(error.toPlaybackFailure() is PlaybackFailure.Unexpected)
    }

    @Test
    fun nonTransportPlayerFailureUsesTheAppRecoveryState() {
        val error =
            PlaybackException(
                "decoder failed",
                IllegalStateException("codec"),
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            )

        assertTrue(error.toPlaybackFailure() is PlaybackFailure.Unexpected)
    }
}
