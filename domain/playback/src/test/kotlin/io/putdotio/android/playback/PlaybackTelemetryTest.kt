package io.putdotio.android.playback

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.telemetry.TelemetrySeverity
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioMediaFormat
import io.putdotio.sdk.files.PutioMediaInfo
import io.putdotio.sdk.files.PutioMediaStream
import java.io.IOException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Player failures as put.io's playback failure reports (schema 2) want them: the contract's tags
 * only, in codes and media facts, never the error's text or the media URL, and no user.
 */
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

        val state = playing(PlaybackSourceKind.HLS)
        val failure = error.toPlaybackFailure()
        val telemetry = state.playerFailureTelemetry(failure, PlaybackPlatform.Mobile, state.readySource)

        assertEquals("playback failed: source_gone", telemetry.shared?.message)
        assertEquals(listOf("android", "failed", "source_gone", "io_bad_http_status"), telemetry.shared?.fingerprint)
        assertEquals(TelemetrySeverity.Error, telemetry.severity)
        assertEquals(
            mapOf(
                "telemetry_event" to "playback_failure",
                "schema_version" to "2",
                "platform" to "android",
                "player" to "media3",
                "playback_outcome" to "failed",
                "playback_terminal" to "true",
                "playback_started" to "true",
                "failure_mode" to "source_gone",
                "streaming_method" to "hls",
                "source_kind" to "hls",
                "device_check" to "skipped",
                "rescue_reason" to "none",
                "rescue_to" to "none",
                "engine_error_domain" to "none",
                "engine_error_category" to "io",
                "engine_error_code" to "io_bad_http_status",
                "source_request_status" to "404",
                "file_id" to "42",
            ) + MEDIA_FACTS.associateWith { "unknown" },
            telemetry.tags,
        )
    }

    @Test
    fun anOriginalTheDeviceCheckPassedButCouldNotDecodeCarriesItsMediaFacts() {
        val error = PlaybackException(TITLE, IOException(SIGNED_URL), PlaybackException.ERROR_CODE_DECODING_FAILED)

        val telemetry = playing(PlaybackSourceKind.ORIGINAL, HEVC_MAIN_10)
            .playerFailureTelemetry(error.toPlaybackFailure(), PlaybackPlatform.Tv, firstFrameSource = null)

        assertEquals("playback failed: decode", telemetry.shared?.message)
        assertEquals(listOf("android_tv", "failed", "decode", "decoding_failed"), telemetry.shared?.fingerprint)
        assertEquals(
            mapOf(
                "telemetry_event" to "playback_failure",
                "schema_version" to "2",
                "platform" to "android_tv",
                "player" to "media3",
                "playback_outcome" to "failed",
                "playback_terminal" to "true",
                "playback_started" to "false",
                "failure_mode" to "decode",
                "streaming_method" to "file",
                "source_kind" to "original",
                "device_check" to "pass",
                "rescue_reason" to "none",
                "rescue_to" to "none",
                "engine_error_domain" to "none",
                "engine_error_category" to "decoding",
                "engine_error_code" to "decoding_failed",
                "source_request_status" to "none",
                "file_id" to "42",
                "container_family" to "matroska",
                "video_codec" to "hevc",
                "video_profile" to "main 10",
                "video_level" to "150",
                "video_bit_depth" to "10",
                "video_resolution" to "3840x2160",
                "video_hdr" to "true",
                "audio_codec" to "aac",
                "audio_channels" to "2",
            ),
            telemetry.tags,
        )
    }

    @Test
    fun playbackReportsDistinguishSdrFromLegacyAndUseTheFirstAudioWithoutAFlag() {
        val failure = PlaybackException(TITLE, IOException(SIGNED_URL), PlaybackException.ERROR_CODE_DECODING_FAILED)
            .toPlaybackFailure()
        fun tags(videoFields: String): Map<String, String> {
            val json = """
                {"format":{"name":"matroska"},"streams":[
                    {"codec_type":"video","codec_name":"h264"$videoFields},
                    {"codec_type":"audio","codec_name":"eac3","channels":6},
                    {"codec_type":"audio","codec_name":"aac","channels":2}
                ]}
            """.trimIndent()
            val media = Json.decodeFromString(PutioMediaInfo.serializer(), json)
            return playing(PlaybackSourceKind.ORIGINAL, media)
                .playerFailureTelemetry(failure, PlaybackPlatform.Mobile, firstFrameSource = null).tags
        }

        val sdr = tags(""","bit_depth":8,"hdr":false""")
        assertEquals("8", sdr["video_bit_depth"])
        assertEquals("false", sdr["video_hdr"])
        val legacy = tags("")
        assertEquals("unknown", legacy["video_bit_depth"])
        assertEquals("unknown", legacy["video_hdr"])
        assertEquals("eac3", legacy["audio_codec"])
        assertEquals("6", legacy["audio_channels"])
    }

    @Test
    fun theConvertedFileSkipsTheDeviceCheckAndFailuresGroupByModeAndCode() {
        val converted = playing(PlaybackSourceKind.MP4)
        fun report(code: Int) =
            converted.playerFailureTelemetry(
                PlaybackException(TITLE, IOException(SIGNED_URL), code).toPlaybackFailure(),
                PlaybackPlatform.Mobile,
                firstFrameSource = null,
            )
        fun mode(code: Int) = report(code).tags["failure_mode"]

        assertEquals("network", mode(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals("unsupported_source", mode(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals("decode", mode(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals("unknown", mode(PlaybackException.ERROR_CODE_UNSPECIFIED))
        val tags = report(PlaybackException.ERROR_CODE_UNSPECIFIED).tags
        assertEquals("converted", tags["source_kind"])
        assertEquals("file", tags["streaming_method"])
        assertEquals("skipped", tags["device_check"])
        assertEquals("decode, decoder_init_failed", report(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED).code)
    }

    @Test
    fun aDownloadIsALocalFileWhateverItWasStreamedAs() {
        val error = PlaybackException(TITLE, IOException(SIGNED_URL), PlaybackException.ERROR_CODE_DECODING_FAILED)

        val state = playing(PlaybackSourceKind.ORIGINAL, offline = true)
        val failure = error.toPlaybackFailure()
        val tags = state.playerFailureTelemetry(failure, PlaybackPlatform.Mobile, state.readySource).tags

        assertEquals("local_file", tags["source_kind"])
        assertEquals("skipped", tags["device_check"])
    }

    private fun playing(kind: PlaybackSourceKind, mediaInfo: PutioMediaInfo? = null, offline: Boolean = false) =
        playbackState(
            target = PlaybackTarget(FilesItemId(42L), TITLE),
            content = PlaybackContent.Ready(
                PlaybackSource(
                    42L,
                    kind,
                    PutioCredentialUrl.of(SIGNED_URL),
                    0.0,
                    PlaybackSubtitles.Embedded,
                    mediaInfo = mediaInfo,
                ),
                offline = offline,
            ),
            nextRequestValue = 2L,
        )

    private companion object {
        const val TITLE = "Synthetic Feature Title"
        const val SIGNED_URL =
            "https://media.example.invalid/hls/Synthetic.Feature.mkv/master.m3u8?oauth_token=synthetic-token"
        val MEDIA_FACTS = listOf(
            "container_family",
            "video_codec",
            "video_profile",
            "video_level",
            "video_bit_depth",
            "video_resolution",
            "video_hdr",
            "audio_codec",
            "audio_channels",
        )
        val HEVC_MAIN_10 = PutioMediaInfo(
            mimeType = "video/x-matroska",
            format = PutioMediaFormat(name = "matroska,webm"),
            streams = listOf(
                PutioMediaStream(codecType = "attachment", codecName = "ttf"),
                PutioMediaStream(
                    codecType = "video",
                    codecName = "hevc",
                    width = 3840,
                    height = 2160,
                    profile = "Main 10",
                    level = 150,
                    bitDepth = 10,
                    isHdr = true,
                ),
                PutioMediaStream(codecType = "audio", codecName = "eac3", channels = 6, isDefault = false),
                PutioMediaStream(codecType = "audio", codecName = "aac", channels = 2, isDefault = true),
            ),
        )
    }
}
