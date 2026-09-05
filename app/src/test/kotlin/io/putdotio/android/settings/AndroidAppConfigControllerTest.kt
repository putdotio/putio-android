package io.putdotio.android.settings

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    fun controllerLoadsAndRefreshesSavedPreference() = runBlocking {
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
    fun simultaneousChangesAcceptOnlyOneSaveWhileRepositoryIsSuspended() = runBlocking {
        val repository = SuspendedSaveRepository()
        val controller = AndroidAppConfigController(repository, this)
        val start = CyclicBarrier(2)
        val changes = listOf(
            AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4),
            AndroidAppConfigChange.AutoplayNextVideo(enabled = true),
        )
        try {
            controller.awaitState { it.content is AndroidAppConfigContent.Ready }
            val results = withTimeout(2_000L) {
                changes.map { change ->
                    async(Dispatchers.Default) {
                        start.await(2, TimeUnit.SECONDS)
                        change to controller.dispatch(AndroidAppConfigEvent.ChangeRequested(change))
                    }
                }.awaitAll()
            }
            withTimeout(2_000L) { repository.started.await() }
            val accepted = results.filter { it.second }.map { it.first }
            assertEquals(1, accepted.size)
            assertEquals(accepted, repository.saved.toList())
            assertTrue(controller.state.value.mutation is AndroidAppConfigMutation.Saving)
            repository.release.complete(Unit)
            controller.awaitState { it.mutation == AndroidAppConfigMutation.Idle }
            assertEquals(
                AndroidAppConfigPreferences().applying(accepted.single()),
                controller.state.value.readyPreferences(),
            )
            assertEquals(accepted, repository.saved.toList())
        } finally {
            repository.release.complete(Unit)
            controller.close()
        }
    }

    @Test
    fun cancelledParentRejectsChangesWithoutPublishingSavingState() = runBlocking {
        val parent = Job()
        val controller = AndroidAppConfigController(SaveFailureRepository(), CoroutineScope(coroutineContext + parent))
        try {
            controller.awaitState { it.content is AndroidAppConfigContent.Ready }
            val beforeCancellation = controller.state.value
            parent.cancel()
            val change = AndroidAppConfigChange.AutoplayNextVideo(enabled = true)
            assertFalse(controller.dispatch(AndroidAppConfigEvent.ChangeRequested(change)))
            assertEquals(beforeCancellation, controller.state.value)
        } finally {
            controller.close()
            withTimeout(2_000L) { parent.cancelAndJoin() }
        }
    }

    @Test
    fun cancelledParentRejectsLateNonCooperativeLoadResult() = runBlocking {
        assertLateLoadIgnored(closeController = false)
    }

    @Test
    fun closedControllerRejectsLateNonCooperativeLoadResult() = runBlocking {
        assertLateLoadIgnored(closeController = true)
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

    private suspend fun assertLateLoadIgnored(closeController: Boolean) {
        val parent = Job()
        val repository = NonCooperativeLoadRepository()
        val controller = AndroidAppConfigController(repository, CoroutineScope(Dispatchers.Default + parent))
        val controllerJob = parent.children.single()
        try {
            withTimeout(2_000L) { repository.started.await() }
            val beforeTermination = controller.state.value
            if (closeController) controller.close() else parent.cancel()
            repository.release()
            withTimeout(2_000L) { controllerJob.join() }
            assertEquals(beforeTermination, controller.state.value)
        } finally {
            controller.close()
            repository.release()
            withTimeout(2_000L) { parent.cancelAndJoin() }
        }
    }

    private class SuspendedSaveRepository : AndroidAppConfigRepository {
        val saved = ConcurrentLinkedQueue<AndroidAppConfigChange>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private var preferences = AndroidAppConfigPreferences()

        override suspend fun load() = AndroidAppConfigRepositoryResult.Success(preferences)

        override suspend fun save(change: AndroidAppConfigChange): AndroidAppConfigRepositoryResult<Unit> {
            saved.add(change)
            started.complete(Unit)
            release.await()
            preferences = preferences.applying(change)
            return AndroidAppConfigRepositoryResult.Success(Unit)
        }
    }

    private class NonCooperativeLoadRepository : AndroidAppConfigRepository {
        val started = CompletableDeferred<Unit>()
        private val lock = Any()
        private var released = false
        private var pending: Continuation<AndroidAppConfigRepositoryResult<AndroidAppConfigPreferences>>? = null

        // suspendCoroutine deliberately models a callback API that completes after cancellation.
        override suspend fun load(): AndroidAppConfigRepositoryResult<AndroidAppConfigPreferences> =
            suspendCoroutine { continuation ->
                synchronized(lock) {
                    if (released) {
                        continuation.resume(AndroidAppConfigRepositoryResult.Success(AndroidAppConfigPreferences()))
                    } else {
                        pending = continuation
                    }
                    started.complete(Unit)
                }
            }

        override suspend fun save(change: AndroidAppConfigChange) = AndroidAppConfigRepositoryResult.Success(Unit)

        fun release() {
            val continuation = synchronized(lock) {
                released = true
                pending.also { pending = null }
            }
            continuation?.resume(AndroidAppConfigRepositoryResult.Success(AndroidAppConfigPreferences()))
        }
    }

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
