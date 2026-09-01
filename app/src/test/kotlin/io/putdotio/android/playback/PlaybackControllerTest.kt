package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackConversionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerTest {
    @Test
    fun resolvesAndRetriesAConversion() =
        runBlocking {
            var calls = 0
            val repository = object : PlaybackRepository {
                override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                    calls += 1
                    return PlaybackRepositoryResult.Success(
                        if (calls == 1) {
                            PlaybackResolution.Conversion(PlaybackConversionState.Converting(40.0))
                        } else {
                            PlaybackResolution.Conversion(PlaybackConversionState.Completed)
                        },
                    )
                }
            }
            val controller = PlaybackController(Target, repository, this)

            try {
                controller.awaitContent<PlaybackContent.Conversion>()
                assertTrue(controller.dispatch(PlaybackEvent.Retry))
                val resolved = controller.awaitContent<PlaybackContent.Conversion> {
                    it.state == PlaybackConversionState.Completed
                }

                assertEquals(PlaybackConversionState.Completed, resolved.state)
                assertEquals(2, calls)
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

    private suspend inline fun <reified T : PlaybackContent> PlaybackController.awaitContent(
        crossinline predicate: (T) -> Boolean = { true },
    ): T =
        withTimeout(TEST_TIMEOUT_MILLIS) {
            state.first { (it.content as? T)?.let(predicate) == true }.content as T
        }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 2_000L
        val Target = PlaybackTarget(FilesItemId(42L), "episode.mkv")
    }
}
