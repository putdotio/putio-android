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
    fun audioSessionStartupWaitsForTheScreenWithoutResolvingOrAdvancing() {
        val audio = Target.copy(mediaType = PlaybackMediaType.AUDIO)
        val attached = PlaybackReducer.start(audio, PlaybackStartup.AttachAudioSession)

        assertEquals(PlaybackContent.Session, attached.state.content)
        assertNull(attached.effect)
        assertFalse(PlaybackReducer.reduce(attached.state, PlaybackEvent.Retry).consumed)
        assertFalse(PlaybackReducer.reduce(attached.state, PlaybackEvent.PlayerEnded).consumed)
        val stale = PlaybackReducer.reduce(
            attached.state,
            PlaybackEvent.ResolveFailed(
                PlaybackRequestId(1L),
                PlaybackFailure.Unexpected(IllegalStateException("unavailable")),
            ),
        )
        assertFalse(stale.consumed)
        assertEquals(attached.state, stale.state)
        assertTrue(PlaybackReducer.start(audio).effect is PlaybackEffect.Resolve)
        assertTrue(PlaybackReducer.start(Target, PlaybackStartup.AttachAudioSession).effect is PlaybackEffect.Resolve)
    }

    @Test
    fun unavailableSessionRequestsOneSourceAndPreservesItsPosition() {
        val audio = Target.copy(mediaType = PlaybackMediaType.AUDIO)
        val attached = PlaybackReducer.start(audio, PlaybackStartup.AttachAudioSession)
        val requested = PlaybackReducer.reduce(attached.state, PlaybackEvent.SourceRequired(12_345L))

        assertEquals(PlaybackContent.Loading(PlaybackRequestId(1L)), requested.state.content)
        assertEquals(PlaybackEffect.Resolve(audio, PlaybackRequestId(1L)), requested.effect)
        assertEquals(12_345L, requested.state.resumePositionMillis)
        val duplicate = PlaybackReducer.reduce(requested.state, PlaybackEvent.SourceRequired(99_999L))
        assertFalse(duplicate.consumed)
        assertEquals(requested.state, duplicate.state)
        assertNull(duplicate.effect)
        val failed = PlaybackReducer.reduce(
            requested.state,
            PlaybackEvent.ResolveFailed(
                PlaybackRequestId(1L),
                PlaybackFailure.Unexpected(IllegalStateException("unavailable")),
            ),
        )
        assertFalse(PlaybackReducer.reduce(failed.state, PlaybackEvent.SourceRequired()).consumed)
        val retry = PlaybackReducer.reduce(failed.state, PlaybackEvent.Retry)
        assertEquals(PlaybackContent.Loading(PlaybackRequestId(2L)), retry.state.content)
        assertEquals(12_345L, retry.state.resumePositionMillis)
    }

    @Test
    fun liveSessionPlayerFailureUsesExistingRecoveryAndNextRequestId() {
        val audio = Target.copy(mediaType = PlaybackMediaType.AUDIO)
        val attached = PlaybackReducer.start(audio, PlaybackStartup.AttachAudioSession)
        val failure = PlaybackFailure.MediaCredentialUnavailable(IllegalStateException("expired"))
        val failed = PlaybackReducer.reduce(attached.state, PlaybackEvent.PlayerFailed(failure, 54_321L))
        val retry = PlaybackReducer.reduce(failed.state, PlaybackEvent.Retry)

        assertEquals(PlaybackContent.Failed(failure), failed.state.content)
        assertEquals(54_321L, failed.state.resumePositionMillis)
        assertNull(failed.effect)
        assertEquals(PlaybackEffect.Resolve(audio, PlaybackRequestId(1L)), retry.effect)
        assertEquals(54_321L, retry.state.resumePositionMillis)
    }

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

    @Test
    fun mediaRequestFailureLeavesReadyStateAndCanRetryResolution() {
        val start = PlaybackReducer.start(Target)
        val ready =
            PlaybackReducer.reduce(
                start.state,
                PlaybackEvent.ResolveSucceeded(
                    PlaybackRequestId(1L),
                    PlaybackResolution.Ready(playbackSource()),
                ),
            )
        val failure = PlaybackFailure.MediaCredentialUnavailable(IllegalStateException("expired"))
        val failed = PlaybackReducer.reduce(ready.state, PlaybackEvent.PlayerFailed(failure, 54_321L))
        val retry = PlaybackReducer.reduce(failed.state, PlaybackEvent.Retry)

        assertEquals(failure, (failed.state.content as PlaybackContent.Failed).failure)
        assertEquals(54_321L, failed.state.resumePositionMillis)
        assertEquals(PlaybackRequestId(2L), (retry.state.content as PlaybackContent.Loading).requestId)
        assertEquals(PlaybackRequestId(2L), retry.effect?.requestId)
    }

    @Test
    fun endedAudioPlaybackDoesNotLookForANextFile() {
        val audio = PlaybackTarget(FilesItemId(7L), "song.mp3", PlaybackMediaType.AUDIO)
        val start = PlaybackReducer.start(audio)
        val ready = PlaybackReducer.reduce(
            start.state,
            PlaybackEvent.ResolveSucceeded(PlaybackRequestId(1L), PlaybackResolution.Ready(playbackSource())),
        ).state

        val ended = PlaybackReducer.reduce(ready, PlaybackEvent.PlayerEnded)

        assertFalse(ended.consumed)
        assertNull(ended.effect)
        assertEquals(ready, ended.state)
    }

    @Test
    fun endedPlaybackFindsAndResolvesAnUnvisitedNextVideo() {
        val ready = readyState()

        val finding = PlaybackReducer.reduce(ready, PlaybackEvent.PlayerEnded)
        val next = PlaybackTarget(FilesItemId(43L), "next.mkv")
        val resolving = PlaybackReducer.reduce(
            finding.state,
            PlaybackEvent.NextFound(PlaybackRequestId(2L), next),
        )

        assertEquals(PlaybackContent.FindingNext(PlaybackRequestId(2L)), finding.state.content)
        assertEquals(PlaybackEffect.FindNext(Target, PlaybackRequestId(2L)), finding.effect)
        assertEquals(next, resolving.state.target)
        assertEquals(setOf(Target.fileId, next.fileId), resolving.state.visitedFileIds)
        assertEquals(PlaybackContent.Loading(PlaybackRequestId(3L)), resolving.state.content)
        assertEquals(PlaybackEffect.Resolve(next, PlaybackRequestId(3L)), resolving.effect)
        assertNull(resolving.state.resumePositionMillis)
    }

    @Test
    fun failedResolutionAfterAutoplayRetriesTheAdvancedTarget() {
        val finding = PlaybackReducer.reduce(readyState(), PlaybackEvent.PlayerEnded)
        val next = PlaybackTarget(FilesItemId(43L), "next.mkv")
        val resolving =
            PlaybackReducer.reduce(
                finding.state,
                PlaybackEvent.NextFound(PlaybackRequestId(2L), next),
            )
        val failure = PlaybackFailure.NetworkUnavailable(IllegalStateException("offline"))
        val failed =
            PlaybackReducer.reduce(
                resolving.state,
                PlaybackEvent.ResolveFailed(PlaybackRequestId(3L), failure),
            )
        val retry = PlaybackReducer.reduce(failed.state, PlaybackEvent.Retry)

        assertEquals(failure, (failed.state.content as PlaybackContent.Failed).failure)
        assertEquals(next, retry.state.target)
        assertEquals(setOf(Target.fileId, next.fileId), retry.state.visitedFileIds)
        assertEquals(PlaybackContent.Loading(PlaybackRequestId(4L)), retry.state.content)
        assertEquals(PlaybackEffect.Resolve(next, PlaybackRequestId(4L)), retry.effect)
    }

    @Test
    fun duplicateEndsAndStaleNextResultsCannotStartAnotherResolution() {
        val finding = PlaybackReducer.reduce(readyState(), PlaybackEvent.PlayerEnded)
        val duplicate = PlaybackReducer.reduce(finding.state, PlaybackEvent.PlayerEnded)
        val stale = PlaybackReducer.reduce(
            finding.state,
            PlaybackEvent.NextFound(
                PlaybackRequestId(99L),
                PlaybackTarget(FilesItemId(43L), "next.mkv"),
            ),
        )

        assertFalse(duplicate.consumed)
        assertNull(duplicate.effect)
        assertFalse(stale.consumed)
        assertNull(stale.effect)
        assertEquals(finding.state, stale.state)
    }

    @Test
    fun wrappingToAVisitedVideoEndsThePlaybackSession() {
        val firstNext = PlaybackTarget(FilesItemId(43L), "first-next.mkv")
        val secondNext = PlaybackTarget(FilesItemId(44L), "second-next.mkv")
        val findingFirst = PlaybackReducer.reduce(readyState(), PlaybackEvent.PlayerEnded)
        val resolvingFirstNext = PlaybackReducer.reduce(
            findingFirst.state,
            PlaybackEvent.NextFound(PlaybackRequestId(2L), firstNext),
        )
        val firstNextReady = PlaybackReducer.reduce(
            resolvingFirstNext.state,
            PlaybackEvent.ResolveSucceeded(PlaybackRequestId(3L), PlaybackResolution.Ready(playbackSource())),
        )
        val findingSecond = PlaybackReducer.reduce(firstNextReady.state, PlaybackEvent.PlayerEnded)
        val resolvingSecondNext =
            PlaybackReducer.reduce(
                findingSecond.state,
                PlaybackEvent.NextFound(PlaybackRequestId(4L), secondNext),
            )
        val secondNextReady =
            PlaybackReducer.reduce(
                resolvingSecondNext.state,
                PlaybackEvent.ResolveSucceeded(PlaybackRequestId(5L), PlaybackResolution.Ready(playbackSource())),
            )
        val findingWrapped = PlaybackReducer.reduce(secondNextReady.state, PlaybackEvent.PlayerEnded)

        val wrapped = PlaybackReducer.reduce(
            findingWrapped.state,
            PlaybackEvent.NextFound(PlaybackRequestId(6L), firstNext),
        )

        assertEquals(PlaybackContent.Ended, wrapped.state.content)
        assertEquals(secondNext, wrapped.state.target)
        assertEquals(setOf(Target.fileId, firstNext.fileId, secondNext.fileId), wrapped.state.visitedFileIds)
        assertNull(wrapped.effect)
    }

    @Test
    fun missingNextEndsWhileLookupFailureRetriesLookup() {
        val finding = PlaybackReducer.reduce(readyState(), PlaybackEvent.PlayerEnded)
        val failure = PlaybackFailure.NetworkUnavailable(IllegalStateException("offline"))
        val failed = PlaybackReducer.reduce(
            finding.state,
            PlaybackEvent.NextFailed(PlaybackRequestId(2L), failure),
        )
        val retry = PlaybackReducer.reduce(failed.state, PlaybackEvent.Retry)
        val ended = PlaybackReducer.reduce(
            retry.state,
            PlaybackEvent.NextEnded(PlaybackRequestId(3L)),
        )

        assertEquals(PlaybackContent.NextFailed(failure), failed.state.content)
        assertEquals(PlaybackEffect.FindNext(Target, PlaybackRequestId(3L)), retry.effect)
        assertEquals(PlaybackContent.Ended, ended.state.content)
    }

    @Test
    fun savedPositionRequiresAChoiceForBothMediaTypes() {
        for (mediaType in PlaybackMediaType.entries) {
            val start = PlaybackReducer.start(Target.copy(mediaType = mediaType))
            val pending = PlaybackReducer.reduce(
                start.state,
                PlaybackEvent.ResolveSucceeded(
                    PlaybackRequestId(1L),
                    PlaybackResolution.Ready(playbackSource(), useStartFrom = true),
                ),
            )
            assertTrue(pending.state.content is PlaybackContent.AwaitingResume)
            assertNull(pending.effect)
            assertNull(pending.state.resumePositionMillis)
            val resumed = PlaybackReducer.reduce(pending.state, PlaybackEvent.Resume)
            assertEquals(12_000L, resumed.state.resumePositionMillis)
            assertTrue((resumed.state.content as PlaybackContent.Ready).useStartFrom)
            assertNull(resumed.effect)
            val restarted = PlaybackReducer.reduce(pending.state, PlaybackEvent.Restart)
            assertEquals(0L, restarted.state.resumePositionMillis)
            assertNull(restarted.effect)
            assertFalse(PlaybackReducer.reduce(restarted.state, PlaybackEvent.Resume).consumed)
            assertFalse(PlaybackReducer.reduce(resumed.state, PlaybackEvent.Restart).consumed)
        }
    }

    @Test
    fun disabledResumeOrZeroSavedPositionStartsWithoutAPrompt() {
        for ((enabled, seconds) in listOf(false to 12.0, true to 0.0)) {
            val resolved = PlaybackReducer.reduce(
                PlaybackReducer.start(Target).state,
                PlaybackEvent.ResolveSucceeded(
                    PlaybackRequestId(1L),
                    PlaybackResolution.Ready(playbackSource().copy(startFromSeconds = seconds), enabled),
                ),
            )
            assertTrue(resolved.state.content is PlaybackContent.Ready)
            assertEquals(enabled, (resolved.state.content as PlaybackContent.Ready).useStartFrom)
        }
    }

    @Test
    fun pendingChoiceIgnoresStaleResponsesAndPlayerEvents() {
        val pending = PlaybackReducer.reduce(
            PlaybackReducer.start(Target).state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(1L), PlaybackResolution.Ready(playbackSource(), useStartFrom = true),
            ),
        ).state
        val failure = PlaybackFailure.NetworkUnavailable(IllegalStateException("offline"))
        for (event in listOf(
            PlaybackEvent.Retry,
            PlaybackEvent.PlayerEnded,
            PlaybackEvent.PlayerFailed(failure, 900L),
            PlaybackEvent.SourceRequired(900L),
            PlaybackEvent.ResolveFailed(PlaybackRequestId(1L), failure),
            PlaybackEvent.ResolveSucceeded(PlaybackRequestId(1L), PlaybackResolution.Ready(playbackSource())),
        )) {
            val ignored = PlaybackReducer.reduce(pending, event)
            assertFalse(ignored.consumed)
            assertEquals(pending, ignored.state)
            assertNull(ignored.effect)
        }
    }

    @Test
    fun recoveryRetryRetainsLocalPositionWithoutAnotherResumePrompt() {
        val failure = PlaybackFailure.NetworkUnavailable(IllegalStateException("offline"))
        val failed = PlaybackReducer.reduce(readyState(), PlaybackEvent.PlayerFailed(failure, 4_321L))
        val retry = PlaybackReducer.reduce(failed.state, PlaybackEvent.Retry)
        val recovered = PlaybackReducer.reduce(
            retry.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(2L), PlaybackResolution.Ready(playbackSource(), useStartFrom = true),
            ),
        )
        assertTrue(recovered.state.content is PlaybackContent.Ready)
        assertEquals(4_321L, recovered.state.resumePositionMillis)
    }

    @Test
    fun autoplayPromptsForTheNextTargetsSavedPosition() {
        val finding = PlaybackReducer.reduce(readyState().copy(resumePositionMillis = 0L), PlaybackEvent.PlayerEnded)
        val next = Target.copy(fileId = FilesItemId(43L), name = "next.mkv")
        val loading = PlaybackReducer.reduce(finding.state, PlaybackEvent.NextFound(PlaybackRequestId(2L), next))
        val resolved = PlaybackReducer.reduce(
            loading.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(3L),
                PlaybackResolution.Ready(playbackSource().copy(fileId = 43L), useStartFrom = true),
            ),
        )
        assertTrue(resolved.state.content is PlaybackContent.AwaitingResume)
        assertNull(resolved.state.resumePositionMillis)
    }

    private fun readyState(): PlaybackState {
        val start = PlaybackReducer.start(Target)
        return PlaybackReducer.reduce(
            start.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(1L),
                PlaybackResolution.Ready(playbackSource()),
            ),
        ).state
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
