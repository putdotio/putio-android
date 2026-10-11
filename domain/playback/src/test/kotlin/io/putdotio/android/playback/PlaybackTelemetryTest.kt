package io.putdotio.android.playback

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Player failures as diagnostics see them: modes and codes, never the error's text or the media URL. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlaybackTelemetryTest {
    @Test
    fun aGoneSourceReportsItsStatusButNotItsUrlOrMessage() {
        val error = PlaybackException(
            "Cannot open $SIGNED_URL",
            HttpDataSource.InvalidResponseCodeException(
                404, "Not Found $TITLE", null, emptyMap(), DataSpec(Uri.parse(SIGNED_URL)), ByteArray(0),
            ),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )

        val telemetry = playing(PlaybackSourceKind.HLS).playerFailureTelemetry(error.toPlaybackFailure())

        assertEquals("source_gone, io_bad_http_status", telemetry.code)
        assertEquals(
            mapOf(
                "telemetry_event" to "playback_failure",
                "schema_version" to "1",
                "player" to "media3",
                "playback_outcome" to "failed",
                "playback_terminal" to "true",
                "playback_failure_mode" to "source_gone",
                "engine_error_category" to "io",
                "engine_error_code" to "io_bad_http_status",
                "source_kind" to "hls",
                "source_request_status" to "404",
                "media_type" to "video",
                "file_id" to "42",
            ),
            telemetry.tags,
        )
    }

    @Test
    fun failuresGroupByModeAndCode() {
        val direct = playing(PlaybackSourceKind.MP4)
        fun report(code: Int) =
            direct.playerFailureTelemetry(PlaybackException(TITLE, IOException(SIGNED_URL), code).toPlaybackFailure())
        fun mode(code: Int) = report(code).tags["playback_failure_mode"]

        assertEquals("network", mode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals("unsupported_source", mode(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals("decode", mode(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals("unknown", mode(PlaybackException.ERROR_CODE_UNSPECIFIED))
        assertEquals("direct", report(PlaybackException.ERROR_CODE_UNSPECIFIED).tags["source_kind"])
        assertEquals("decode, decoder_init_failed", report(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED).code)
    }

    @Test
    fun aPlayerWithoutAKnownFileOrSourceReportsThemUnknown() {
        val error = PlaybackException(TITLE, IOException(SIGNED_URL), PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)

        val telemetry = playbackFailureTelemetry(error.toPlaybackFailure(), null, PlaybackMediaType.AUDIO, null)

        assertEquals("unknown", telemetry.tags["source_kind"])
        assertEquals("audio", telemetry.tags["media_type"])
        assertEquals(null, telemetry.tags["file_id"])
    }

    private fun playing(kind: PlaybackSourceKind): PlaybackState =
        playbackState(
            target = PlaybackTarget(FilesItemId(42L), TITLE),
            content = PlaybackContent.Ready(
                PlaybackSource(42L, kind, PutioCredentialUrl.of(SIGNED_URL), 0.0, PlaybackSubtitles.Embedded),
            ),
            nextRequestValue = 2L,
        )

    private companion object {
        const val TITLE = "Synthetic Feature Title"
        const val SIGNED_URL =
            "https://media.example.invalid/hls/Synthetic.Feature.mkv/master.m3u8?oauth_token=synthetic-token"
    }
}
