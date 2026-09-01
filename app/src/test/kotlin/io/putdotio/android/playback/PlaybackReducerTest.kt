package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReducerTest {
    @Test
    fun startAndRetryIssueDistinctRequests() {
        val start = PlaybackReducer.start(Target)
        val firstRequest = (start.state.content as PlaybackContent.Loading).requestId

        val conversion = PlaybackReducer.reduce(
            start.state,
            PlaybackEvent.ResolveSucceeded(
                firstRequest,
                PlaybackResolution.Conversion(PlaybackConversionState.Converting(20.0)),
            ),
        )
        val retry = PlaybackReducer.reduce(conversion.state, PlaybackEvent.Retry)

        assertTrue(conversion.state.content is PlaybackContent.Conversion)
        assertEquals(PlaybackRequestId(2L), (retry.state.content as PlaybackContent.Loading).requestId)
        assertEquals(PlaybackRequestId(2L), retry.effect?.requestId)
    }

    @Test
    fun staleResultsCannotReplaceTheActiveRequest() {
        val start = PlaybackReducer.start(Target)
        val failed = PlaybackReducer.reduce(
            start.state,
            PlaybackEvent.ResolveFailed(
                PlaybackRequestId(1L),
                PlaybackFailure.Unexpected(IllegalStateException("offline")),
            ),
        )
        val retry = PlaybackReducer.reduce(failed.state, PlaybackEvent.Retry)
        val stale = PlaybackReducer.reduce(
            retry.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(1L),
                PlaybackResolution.Unsupported(PutioFileType.TEXT),
            ),
        )

        assertFalse(stale.consumed)
        assertEquals(retry.state, stale.state)
        assertNull(stale.effect)
    }

    @Test
    fun readyAndUnsupportedStatesDoNotRetry() {
        val start = PlaybackReducer.start(Target)
        val ready = PlaybackReducer.reduce(
            start.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(1L),
                PlaybackResolution.Unsupported(PutioFileType.IMAGE),
            ),
        )

        val retry = PlaybackReducer.reduce(ready.state, PlaybackEvent.Retry)

        assertTrue(retry.state.content is PlaybackContent.Unsupported)
        assertFalse(retry.consumed)
        assertNull(retry.effect)
    }

    @Test
    fun readyResolutionPreservesTheResolvedSource() {
        val start = PlaybackReducer.start(Target)
        val source = playbackSource()

        val ready = PlaybackReducer.reduce(
            start.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(1L),
                PlaybackResolution.Ready(source),
            ),
        )

        assertTrue(ready.state.content is PlaybackContent.Ready)
        assertTrue((ready.state.content as PlaybackContent.Ready).source === source)
    }

    private fun playbackSource(): PlaybackSource =
        PlaybackSource(
            fileId = Target.fileId.value,
            kind = PlaybackSourceKind.HLS,
            url =
                PutioCredentialUrl::class.java
                    .getDeclaredConstructor(String::class.java)
                    .newInstance("https://api.put.io/v2/files/42/hls/media.m3u8?token=secret"),
            startFromSeconds = 12.0,
            subtitles = PlaybackSubtitles.Embedded,
        )

    private companion object {
        val Target = PlaybackTarget(FilesItemId(42L), "episode.mkv")
    }
}
