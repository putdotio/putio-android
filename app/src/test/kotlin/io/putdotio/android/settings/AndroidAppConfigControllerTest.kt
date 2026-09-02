package io.putdotio.android.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAppConfigControllerTest {
    @Test
    fun controllerLoadsAndSerializesPerKeySaves() = runBlocking {
        val saved = mutableListOf<AndroidAppConfigChange>()
        var preferences = AndroidAppConfigPreferences()
        val repository = object : AndroidAppConfigRepository {
            override suspend fun load() =
                AndroidAppConfigRepositoryResult.Success(preferences)

            override suspend fun save(change: AndroidAppConfigChange): AndroidAppConfigRepositoryResult<Unit> {
                saved += change
                preferences = preferences.applying(change)
                return AndroidAppConfigRepositoryResult.Success(Unit)
            }
        }
        val controller = AndroidAppConfigController(repository, this)
        try {
            controller.awaitState { it.content is AndroidAppConfigContent.Ready }
            val change = AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4)
            assertTrue(controller.dispatch(AndroidAppConfigEvent.ChangeRequested(change)))
            controller.awaitState { it.mutation == AndroidAppConfigMutation.Idle }
            assertEquals(listOf(change), saved)
            assertEquals(VideoPlaybackType.Mp4, controller.state.value.readyPreferences().videoPlaybackType)
        } finally {
            controller.close()
        }
    }

    @Test
    fun immediateRetryFromFailureCollectorStartsANewSave() = runBlocking {
        val repository = SaveFailureRepository()
        val controller = AndroidAppConfigController(repository, this)
        val change = AndroidAppConfigChange.AutoplayNextVideo(enabled = true)
        val collector =
            launch(Dispatchers.Unconfined) {
                controller.state.collect { state ->
                    if (state.mutation is AndroidAppConfigMutation.Failed) {
                        controller.dispatch(AndroidAppConfigEvent.RetryChange)
                    }
                }
            }
        try {
            controller.awaitState { it.content is AndroidAppConfigContent.Ready }
            assertTrue(controller.dispatch(AndroidAppConfigEvent.ChangeRequested(change)))
            controller.awaitState { it.mutation == AndroidAppConfigMutation.Idle && repository.saveCount == 2 }
            assertEquals(2, repository.saveCount)
        } finally {
            collector.cancelAndJoin()
            controller.close()
        }
    }

    @Test
    fun refreshRetryAfterAcceptedSaveDoesNotRepeatSave() = runBlocking {
        val repository = RefreshFailureRepository()
        val controller = AndroidAppConfigController(repository, this)
        val change = AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4)
        try {
            controller.awaitState { it.content is AndroidAppConfigContent.Ready }
            assertTrue(controller.dispatch(AndroidAppConfigEvent.ChangeRequested(change)))
            controller.awaitState {
                (it.mutation as? AndroidAppConfigMutation.Failed)?.operation ==
                    AndroidAppConfigMutation.Operation.Refresh
            }
            assertEquals(1, repository.saveCount)
            assertTrue(controller.dispatch(AndroidAppConfigEvent.RetryChange))
            controller.awaitState { it.mutation == AndroidAppConfigMutation.Idle }
            assertEquals(1, repository.saveCount)
            assertEquals(3, repository.loadCount)
            assertEquals(VideoPlaybackType.Mp4, controller.state.value.readyPreferences().videoPlaybackType)
        } finally {
            controller.close()
        }
    }

    @Test
    fun closedControllerRejectsEventsAndCancelsItsLoad() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val repository = object : AndroidAppConfigRepository {
            override suspend fun load(): AndroidAppConfigRepositoryResult<AndroidAppConfigPreferences> =
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }

            override suspend fun save(change: AndroidAppConfigChange) =
                AndroidAppConfigRepositoryResult.Success(Unit)
        }
        val controller = AndroidAppConfigController(repository, this)
        started.await()
        controller.close()
        assertFalse(controller.dispatch(AndroidAppConfigEvent.RetryLoad))
        withTimeout(2_000L) { cancelled.await() }
    }

    private suspend fun AndroidAppConfigController.awaitState(predicate: (AndroidAppConfigState) -> Boolean) =
        withTimeout(2_000L) { state.first(predicate) }

    private fun AndroidAppConfigState.readyPreferences() =
        (content as AndroidAppConfigContent.Ready).preferences

    private class SaveFailureRepository : AndroidAppConfigRepository {
        var saveCount = 0

        override suspend fun load() = AndroidAppConfigRepositoryResult.Success(AndroidAppConfigPreferences())

        override suspend fun save(change: AndroidAppConfigChange): AndroidAppConfigRepositoryResult<Unit> {
            saveCount += 1
            return if (saveCount == 1) {
                AndroidAppConfigRepositoryResult.Failure(
                    AndroidAppConfigFailure.Unexpected(IllegalStateException("offline")),
                )
            } else {
                AndroidAppConfigRepositoryResult.Success(Unit)
            }
        }
    }

    private class RefreshFailureRepository : AndroidAppConfigRepository {
        var loadCount = 0
        var saveCount = 0

        override suspend fun load(): AndroidAppConfigRepositoryResult<AndroidAppConfigPreferences> {
            loadCount += 1
            return when (loadCount) {
                1 -> AndroidAppConfigRepositoryResult.Success(AndroidAppConfigPreferences())
                2 -> AndroidAppConfigRepositoryResult.Failure(
                    AndroidAppConfigFailure.Unexpected(IllegalStateException("offline")),
                )
                else -> AndroidAppConfigRepositoryResult.Success(
                    AndroidAppConfigPreferences(videoPlaybackType = VideoPlaybackType.Mp4),
                )
            }
        }

        override suspend fun save(change: AndroidAppConfigChange): AndroidAppConfigRepositoryResult<Unit> {
            saveCount += 1
            return AndroidAppConfigRepositoryResult.Success(Unit)
        }
    }
}
