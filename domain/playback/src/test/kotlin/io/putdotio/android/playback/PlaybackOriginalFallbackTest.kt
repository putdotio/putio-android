package io.putdotio.android.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlaybackOriginalFallbackTest {
    @Test
    fun aRejectedOriginalResolvesConvertedFromWhereItStood() {
        val rejected = PlaybackReducer.reduce(
            ready(source(PlaybackSourceKind.ORIGINAL)),
            PlaybackEvent.OriginalRejected(PlaybackFallbackReason.VideoUnsupported, 61_000L),
        )

        assertEquals(PlaybackContent.Loading(PlaybackRequestId(2L)), rejected.state.content)
        assertEquals(PlaybackEffect.Resolve(Video, PlaybackRequestId(2L), convertedOnly = true), rejected.effect)
        assertEquals(61_000L, rejected.state.resumePositionMillis)
        val converted = PlaybackReducer.reduce(
            rejected.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(2L),
                PlaybackResolution.Ready(source(PlaybackSourceKind.HLS), useStartFrom = true),
            ),
        )
        assertEquals(
            PlaybackContent.Ready(source(PlaybackSourceKind.HLS), useStartFrom = true),
            converted.state.content,
        )
        assertEquals(61_000L, converted.state.resumePositionMillis)
    }

    @Test
    fun convertedStaysStickyThroughConversionAndRetryUntilAnotherFile() {
        val rejected = PlaybackReducer.reduce(
            ready(source(PlaybackSourceKind.ORIGINAL)),
            PlaybackEvent.OriginalRejected(PlaybackFallbackReason.DecoderError, 5_000L),
        ).state
        val converting = PlaybackReducer.reduce(
            rejected,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(2L),
                PlaybackResolution.Conversion(PlaybackConversionState.NotAvailable),
            ),
        )
        assertEquals(
            PlaybackEffect.StartConversion(Video, PlaybackRequestId(3L), convertedOnly = true),
            converting.effect,
        )
        val failed = PlaybackReducer.reduce(
            converting.state,
            PlaybackEvent.ResolveFailed(PlaybackRequestId(3L), Unavailable),
        )
        val retried = PlaybackReducer.reduce(failed.state, PlaybackEvent.Retry)
        assertEquals(PlaybackEffect.Resolve(Video, PlaybackRequestId(4L), convertedOnly = true), retried.effect)
        val playing = PlaybackReducer.reduce(
            retried.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(4L),
                PlaybackResolution.Ready(source(PlaybackSourceKind.HLS)),
            ),
        )

        val next = PlaybackTarget(FilesItemId(43L), "next.mkv")
        val chosen = PlaybackReducer.reduce(playing.state, PlaybackEvent.NextChosen(next))
        assertEquals(PlaybackEffect.Resolve(next, PlaybackRequestId(5L)), chosen.effect)
    }

    @Test
    fun downloadsConvertedStreamsAndAudioIgnoreTheRejection() {
        val rejection = PlaybackEvent.OriginalRejected(PlaybackFallbackReason.VideoUnsupported, 1_000L)
        val audio = PlaybackTarget(FilesItemId(42L), "track.flac", PlaybackMediaType.AUDIO)
        listOf(
            ready(source(PlaybackSourceKind.ORIGINAL), offline = true),
            ready(source(PlaybackSourceKind.HLS)),
            ready(source(PlaybackSourceKind.MP4)),
            ready(source(PlaybackSourceKind.ORIGINAL), target = audio),
        ).forEach { state ->
            val transition = PlaybackReducer.reduce(state, rejection)
            assertFalse(transition.consumed)
            assertEquals(state, transition.state)
            assertNull(transition.effect)
        }
    }

    @Test
    fun aFailureAfterTheFirstFrameStillFails() {
        val failed = PlaybackReducer.reduce(
            ready(source(PlaybackSourceKind.ORIGINAL)),
            PlaybackEvent.PlayerFailed(Unavailable, 90_000L),
        )

        assertEquals(PlaybackContent.Failed(Unavailable), failed.state.content)
        assertFalse(
            PlaybackReducer.reduce(
                failed.state,
                PlaybackEvent.OriginalRejected(PlaybackFallbackReason.DecoderError, 90_000L),
            ).consumed,
        )
        assertEquals(
            PlaybackEffect.Resolve(Video, PlaybackRequestId(2L)),
            PlaybackReducer.reduce(failed.state, PlaybackEvent.Retry).effect,
        )
    }

    @Test
    fun theControllerResolvesConvertedOnlyAfterARejection() = runBlocking {
        val asked = mutableListOf<Boolean>()
        val repository = object : PlaybackRepository {

            override suspend fun resolve(
                target: PlaybackTarget,
                convertedOnly: Boolean,
            ): PlaybackRepositoryResult<PlaybackResolution> {
                asked += convertedOnly
                val kind = if (convertedOnly) PlaybackSourceKind.HLS else PlaybackSourceKind.ORIGINAL
                return PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source(kind)))
            }

            override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = error("Not expected")
        }
        val controller = PlaybackController(Video, repository, this)
        try {
            withTimeout(TIMEOUT_MILLIS) { controller.state.first { it.content.kind() == PlaybackSourceKind.ORIGINAL } }
            assertTrue(
                controller.dispatch(PlaybackEvent.OriginalRejected(PlaybackFallbackReason.AudioUnsupported, 7_000L)),
            )
            withTimeout(TIMEOUT_MILLIS) { controller.state.first { it.content.kind() == PlaybackSourceKind.HLS } }
            assertEquals(listOf(false, true), asked)
        } finally {
            controller.close()
        }
    }

    @Test
    fun theVerdictFallsBackWhenNoVideoOrNoAudioTrackDecodes() {
        val video = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build())
        val audio = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build())
        val otherAudio = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build())

        assertEquals(
            PlaybackFallbackReason.VideoUnsupported,
            directPlayVerdict(tracks(video to C.FORMAT_UNSUPPORTED_SUBTYPE, audio to C.FORMAT_HANDLED)),
        )
        assertEquals(
            PlaybackFallbackReason.AudioUnsupported,
            directPlayVerdict(tracks(video to C.FORMAT_HANDLED, audio to C.FORMAT_UNSUPPORTED_TYPE)),
        )
        assertEquals(
            PlaybackFallbackReason.VideoUnsupported,
            directPlayVerdict(tracks(video to C.FORMAT_EXCEEDS_CAPABILITIES)),
        )
        assertNull(
            directPlayVerdict(
                tracks(video to C.FORMAT_HANDLED, audio to C.FORMAT_UNSUPPORTED_TYPE, otherAudio to C.FORMAT_HANDLED),
            ),
        )
        assertNull(directPlayVerdict(tracks(video to C.FORMAT_HANDLED)))
        assertNull(directPlayVerdict(tracks(audio to C.FORMAT_UNSUPPORTED_TYPE)))
        assertNull(directPlayVerdict(Tracks.EMPTY))
    }

    @Test
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun onlyParsingDecodingAndRefusedRequestFailuresRejectTheOriginal() {
        fun reason(code: Int) = PlaybackException("synthetic", null, code).originalRejection()
        fun rendererReason(mimeType: String) =
            ExoPlaybackException.createForRenderer(
                IllegalStateException("synthetic"),
                "renderer",
                0,
                Format.Builder().setSampleMimeType(mimeType).build(),
                C.FORMAT_UNSUPPORTED_SUBTYPE,
                null,
                false,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            ).originalRejection()

        assertEquals(
            PlaybackFallbackReason.ContainerUnsupported,
            reason(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),
        )
        assertEquals(
            PlaybackFallbackReason.ContainerUnsupported,
            reason(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
        assertEquals(PlaybackFallbackReason.DecoderError, reason(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(
            PlaybackFallbackReason.DecoderError,
            reason(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
        )
        assertEquals(PlaybackFallbackReason.VideoUnsupported, rendererReason(MimeTypes.VIDEO_H265))
        assertEquals(PlaybackFallbackReason.AudioUnsupported, rendererReason(MimeTypes.AUDIO_TRUEHD))
        assertNull(reason(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertNull(reason(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals(PlaybackFallbackReason.ContainerUnsupported, statusReason(HttpURLConnection.HTTP_BAD_REQUEST))
        assertEquals(
            PlaybackFallbackReason.ContainerUnsupported,
            PlaybackException(
                "synthetic",
                IOException("Response code: 400"),
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            ).originalRejection(),
        )
        listOf(
            HttpURLConnection.HTTP_UNAUTHORIZED,
            HttpURLConnection.HTTP_FORBIDDEN,
            HttpURLConnection.HTTP_NOT_FOUND,
            HttpURLConnection.HTTP_INTERNAL_ERROR,
        ).forEach { assertNull("$it", statusReason(it)) }
        assertNull(reason(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED))
    }

    @Test
    fun aReceiverAlwaysGetsStandardWhateverThePlayAskedFor() {
        listOf(null, StreamingMethod.File, StreamingMethod.Hls).forEach { method ->
            val directPlay = PlaybackState(
                target = Video.copy(method = method),
                content = PlaybackContent.Ready(source(PlaybackSourceKind.ORIGINAL)),
                nextRequestValue = 2L,
            )

            val rejected = PlaybackReducer.reduce(
                directPlay,
                PlaybackEvent.OriginalRejected(PlaybackFallbackReason.CastReceiver, 5_000L),
            )

            val standard = Video.copy(method = StreamingMethod.Hls)
            assertEquals(PlaybackEffect.Resolve(standard, PlaybackRequestId(2L), convertedOnly = true), rejected.effect)
            assertEquals(standard, rejected.state.target)
        }
    }

    @Test
    fun aFallbackReportsItsReasonAsANonTerminalOutcome() {
        val telemetry = ready(source(PlaybackSourceKind.ORIGINAL))
            .originalFallbackTelemetry(PlaybackFallbackReason.AudioUnsupported)

        assertEquals("fallback, audio_unsupported", telemetry.code)
        assertEquals(
            mapOf(
                "telemetry_event" to "playback_failure",
                "schema_version" to "1",
                "player" to "media3",
                "playback_outcome" to "fallback",
                "playback_terminal" to "false",
                "playback_fallback_reason" to "audio_unsupported",
                "source_kind" to "original",
                "media_type" to "video",
                "file_id" to "42",
            ),
            telemetry.tags,
        )
        val failure = PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("synthetic")))
        assertEquals(
            "original",
            ready(source(PlaybackSourceKind.ORIGINAL)).playerFailureTelemetry(failure).tags["source_kind"],
        )
        assertEquals(
            "direct",
            ready(source(PlaybackSourceKind.MP4)).playerFailureTelemetry(failure).tags["source_kind"],
        )
    }

    @Test
    fun aReceiverNeverGetsAnOriginalVideo() {
        val original = source(PlaybackSourceKind.ORIGINAL)
        val audio = PlaybackTarget(FilesItemId(42L), "track.flac", PlaybackMediaType.AUDIO)

        assertNull(original.toCastMedia(Video, "download-secret"))
        assertEquals("audio/flac", original.toCastMedia(audio, "download-secret")?.contentType)
        assertEquals("video/mp4", source(PlaybackSourceKind.MP4).toCastMedia(Video, "download-secret")?.contentType)
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun statusReason(status: Int): PlaybackFallbackReason? =
        ExoPlaybackException.createForSource(
            HttpDataSource.InvalidResponseCodeException(
                status,
                null,
                null,
                emptyMap(),
                DataSpec(Uri.parse("https://api.put.io/v2/files/42/hls/media.m3u8?original=1")),
                ByteArray(0),
            ),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        ).originalRejection()

    private fun ready(
        source: PlaybackSource,
        offline: Boolean = false,
        target: PlaybackTarget = Video,
    ): PlaybackState =
        playbackState(
            target = target,
            content = PlaybackContent.Ready(source, offline = offline),
            nextRequestValue = 2L,
        )

    private fun source(kind: PlaybackSourceKind): PlaybackSource =
        PlaybackSource(
            fileId = Video.fileId.value,
            kind = kind,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/stream?oauth_token=secret"),
            startFromSeconds = 12.0,
            subtitles = PlaybackSubtitles.None,
        )

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun tracks(vararg groups: Pair<TrackGroup, Int>): Tracks =
        Tracks(
            groups.map { (group, support) ->
                Tracks.Group(group, false, intArrayOf(support), booleanArrayOf(false))
            },
        )

    private fun PlaybackContent.kind(): PlaybackSourceKind? = (this as? PlaybackContent.Ready)?.source?.kind

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        val Video = PlaybackTarget(FilesItemId(42L), "episode.mkv")
        val Unavailable = PlaybackFailure.Putio(PutioFailure.Unexpected(IllegalStateException("gone")))
    }
}
