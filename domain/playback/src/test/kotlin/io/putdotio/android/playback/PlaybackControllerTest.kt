package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerTest {
    @Test
    fun resumeChoiceIsRetainedWithoutAnotherResolution() = runBlocking {
        var resolutions = 0
        val repository = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                resolutions += 1
                return PlaybackRepositoryResult.Success(
                    PlaybackResolution.Ready(playbackSource(target.fileId.value).copy(startFromSeconds = 12.345), true),
                )
            }

            override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = error("Not expected")
        }
        val controller = PlaybackController(Target, repository, this)
        try {
            withTimeout(TEST_TIMEOUT_MILLIS) { controller.state.first { it.content is PlaybackContent.AwaitingResume } }
            assertTrue(controller.dispatch(PlaybackEvent.Resume))
            assertEquals(12_345L, controller.state.value.resumePositionMillis)
            assertFalse(controller.dispatch(PlaybackEvent.Restart))
            assertEquals(12_345L, controller.state.value.resumePositionMillis)
            assertEquals(1, resolutions)
        } finally {
            controller.close()
        }
    }

    @Test
    fun closingAnAttachedSessionPreventsDeferredSourceWork() = runBlocking {
        var calls = 0
        val repository = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                calls += 1
                return PlaybackRepositoryResult.Success(PlaybackResolution.Ready(playbackSource(target.fileId.value)))
            }

            override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
        }
        val controller = PlaybackController(
            Target.copy(mediaType = PlaybackMediaType.AUDIO),
            repository,
            this,
            PlaybackStartup.AttachAudioSession,
        )
        controller.close()
        assertFalse(controller.dispatch(PlaybackEvent.SourceRequired()))
        yield()
        assertEquals(0, calls)
        assertTrue(coroutineContext[Job]?.isActive == true)
    }

    @Test
    fun openingAVideoWithNoConversionRequestedStartsItOnceThroughTheRepository() =
        runBlocking {
            val calls = mutableListOf<String>()
            val repository = object : PlaybackRepository {
                override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                    val started = "start" in calls
                    val state = if (started) PlaybackConversionState.Queued else PlaybackConversionState.NotAvailable
                    calls += "resolve"
                    return PlaybackRepositoryResult.Success(PlaybackResolution.Conversion(state))
                }

                override suspend fun startConversion(
                    target: PlaybackTarget,
                ): PlaybackRepositoryResult<PlaybackResolution> {
                    calls += "start"
                    return PlaybackRepositoryResult.Success(
                        PlaybackResolution.Conversion(PlaybackConversionState.Queued),
                    )
                }

                override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
                    PlaybackNextResult.Ended
            }
            val controller = PlaybackController(Target, repository, this)

            try {
                controller.awaitContent<PlaybackContent.Conversion> {
                    it.state == PlaybackConversionState.Queued && it.refreshRequestId == null
                }
                assertTrue(controller.dispatch(PlaybackEvent.RefreshConversion))
                controller.awaitContent<PlaybackContent.Conversion> {
                    it.state == PlaybackConversionState.Queued && it.refreshRequestId == null
                }
                assertFalse(controller.dispatch(PlaybackEvent.StartConversion))

                assertEquals(listOf("resolve", "start", "resolve"), calls)
            } finally {
                controller.close()
            }
        }

    @Test
    fun closeCancelsResolutionWithoutCancellingTheParentScope() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val repository = object : PlaybackRepository {
                override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
                    PlaybackNextResult.Ended
            }
            val controller = PlaybackController(Target, repository, this)

            withTimeout(TEST_TIMEOUT_MILLIS) { started.await() }
            controller.close()
            withTimeout(TEST_TIMEOUT_MILLIS) { cancelled.await() }

            assertFalse(controller.dispatch(PlaybackEvent.Retry))
            assertTrue(coroutineContext[Job]?.isActive == true)
        }

    @Test
    fun parentCancellationCancelsResolution() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val parentJob = Job()
            val repository = object : PlaybackRepository {
                override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
                override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
                    PlaybackNextResult.Ended
            }
            val controller = PlaybackController(
                target = Target,
                repository = repository,
                parentScope = CoroutineScope(coroutineContext + parentJob),
            )

            withTimeout(TEST_TIMEOUT_MILLIS) { started.await() }
            parentJob.cancel()
            withTimeout(TEST_TIMEOUT_MILLIS) { cancelled.await() }

            assertFalse(parentJob.isActive)
            controller.close()
        }

    @Test
    fun findingNextVideoResolvesTheReturnedTargetInOrder() =
        runBlocking {
            val calls = mutableListOf<String>()
            val next = PlaybackTarget(FilesItemId(43L), "next.mkv")
            val repository =
                object : PlaybackRepository {
                    override suspend fun resolve(
                        target: PlaybackTarget,
                    ): PlaybackRepositoryResult<PlaybackResolution> {
                        calls += "resolve:${target.fileId.value}"
                        return PlaybackRepositoryResult.Success(
                            PlaybackResolution.Ready(playbackSource(target.fileId.value)),
                        )
                    }

                    override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult {
                        calls += "next:${target.fileId.value}"
                        return PlaybackNextResult.Found(next)
                    }
                }
            val controller = PlaybackController(Target, repository, this)

            try {
                controller.awaitContent<PlaybackContent.Ready>()
                assertTrue(controller.dispatch(PlaybackEvent.PlayerEnded))
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    controller.state.first {
                        it.target == next && it.content is PlaybackContent.Ready
                    }
                }

                assertEquals(listOf("resolve:42", "next:42", "resolve:43"), calls)
                assertEquals(setOf(Target.fileId, next.fileId), controller.state.value.visitedFileIds)
            } finally {
                controller.close()
            }
        }

    private suspend inline fun <reified T : PlaybackContent> PlaybackController.awaitContent(
        crossinline predicate: (T) -> Boolean = { true },
    ): T =
        withTimeout(TEST_TIMEOUT_MILLIS) {
            state.first { (it.content as? T)?.let(predicate) == true }.content as T
        }

    private fun playbackSource(fileId: Long): PlaybackSource =
        PlaybackSource(
            fileId = fileId,
            kind = PlaybackSourceKind.HLS,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/$fileId/hls/media.m3u8?token=secret"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.Embedded,
        )

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 2_000L
        val Target = PlaybackTarget(FilesItemId(42L), "episode.mkv")
    }
}
