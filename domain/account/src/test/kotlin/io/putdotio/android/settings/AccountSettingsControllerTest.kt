package io.putdotio.android.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSettingsControllerTest {

    @Test
    fun loadsSavesAndRetriesThroughTheRepositoryBoundary() =
        runBlocking {
            val repository = RecordingRepository()
            val controller = AccountSettingsController(repository, this)

            try {
                controller.awaitState { it.content is AccountSettingsContent.Ready }
                val change = AccountSettingsChange(AccountSettingsKey.History, enabled = false)
                assertTrue(controller.dispatch(AccountSettingsEvent.ChangeRequested(change)))
                controller.awaitState { it.mutation is AccountSettingsMutation.Failed }

                val failedReady = controller.state.value.content as AccountSettingsContent.Ready
                assertTrue(failedReady.preferences.historyEnabled)
                assertTrue(controller.dispatch(AccountSettingsEvent.RetryChange))
                controller.awaitState { it.mutation == AccountSettingsMutation.Idle }

                assertEquals(listOf(change, change), repository.savedChanges)
            } finally {
                controller.close()
            }
            assertTrue(coroutineContext[Job]?.isActive == true)
        }

    @Test
    fun immediateRetryFromTheFailureCollectorStartsANewEffect() =
        runBlocking {
            val repository = RecordingRepository()
            val controller = AccountSettingsController(repository, this)
            val change = AccountSettingsChange(AccountSettingsKey.History, enabled = false)
            val collector =
                launch(Dispatchers.Unconfined) {
                    controller.state.collect { state ->
                        if (state.mutation is AccountSettingsMutation.Failed) {
                            controller.dispatch(AccountSettingsEvent.RetryChange)
                        }
                    }
                }

            try {
                controller.awaitState { it.content is AccountSettingsContent.Ready }
                assertTrue(controller.dispatch(AccountSettingsEvent.ChangeRequested(change)))
                controller.awaitState {
                    it.mutation == AccountSettingsMutation.Idle && repository.savedChanges.size == 2
                }

                assertEquals(listOf(change, change), repository.savedChanges)
            } finally {
                collector.cancelAndJoin()
                controller.close()
            }
        }

    @Test
    fun refreshRetryAfterAcceptedSaveDoesNotRepeatTheMutation() =
        runBlocking {
            val repository = RefreshFailureRepository()
            val controller = AccountSettingsController(repository, this)
            val change = AccountSettingsChange(AccountSettingsKey.History, enabled = false)

            try {
                controller.awaitState { it.content is AccountSettingsContent.Ready }
                assertTrue(controller.dispatch(AccountSettingsEvent.ChangeRequested(change)))
                controller.awaitState {
                    (it.mutation as? AccountSettingsMutation.Failed)?.operation ==
                        AccountSettingsMutation.Operation.Refresh
                }

                assertEquals(1, repository.saveCount)
                assertTrue(controller.dispatch(AccountSettingsEvent.RetryChange))
                controller.awaitState { it.mutation == AccountSettingsMutation.Idle }

                assertEquals(1, repository.saveCount)
                assertEquals(3, repository.loadCount)
            } finally {
                controller.close()
            }
        }

    @Test
    fun closeCancelsAnInFlightLoad() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val repository =
                object : AccountSettingsRepository {
        override suspend fun loadTunnelRoutes(): AccountSettingsRepositoryResult<List<TunnelRouteOption>> =
            error("Tunnel routes are not expected")

                    override suspend fun load(): AccountSettingsRepositoryResult<AccountSettingsPreferences> {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }

                    override suspend fun save(
                        change: AccountSettingsChange,
                    ): AccountSettingsRepositoryResult<Unit> =
                        error("Save is not expected")
                }
            val controller = AccountSettingsController(repository, this)

            started.await()
            controller.close()

            withTimeout(TEST_TIMEOUT_MILLIS) { cancelled.await() }
        }

    private suspend fun AccountSettingsController.awaitState(
        predicate: (AccountSettingsState) -> Boolean,
    ): AccountSettingsState = withTimeout(TEST_TIMEOUT_MILLIS) { state.first(predicate) }

    private class RecordingRepository : AccountSettingsRepository {
        override suspend fun loadTunnelRoutes(): AccountSettingsRepositoryResult<List<TunnelRouteOption>> =
            error("Tunnel routes are not expected")

        val savedChanges = mutableListOf<AccountSettingsChange>()

        override suspend fun load(): AccountSettingsRepositoryResult<AccountSettingsPreferences> =
            AccountSettingsRepositoryResult.Success(Preferences)

        override suspend fun save(
            change: AccountSettingsChange,
        ): AccountSettingsRepositoryResult<Unit> {
            savedChanges += change
            return if (savedChanges.size == 1) {
                AccountSettingsRepositoryResult.Failure(
                    AccountSettingsFailure.Unexpected(IllegalStateException("offline")),
                )
            } else {
                AccountSettingsRepositoryResult.Success(Unit)
            }
        }
    }

    private class RefreshFailureRepository : AccountSettingsRepository {
        override suspend fun loadTunnelRoutes(): AccountSettingsRepositoryResult<List<TunnelRouteOption>> =
            error("Tunnel routes are not expected")

        var loadCount = 0
        var saveCount = 0

        override suspend fun load(): AccountSettingsRepositoryResult<AccountSettingsPreferences> {
            loadCount += 1
            return when (loadCount) {
                1 -> AccountSettingsRepositoryResult.Success(Preferences)
                2 ->
                    AccountSettingsRepositoryResult.Failure(
                        AccountSettingsFailure.Unexpected(IllegalStateException("offline")),
                    )
                else -> AccountSettingsRepositoryResult.Success(Preferences.copy(historyEnabled = false))
            }
        }

        override suspend fun save(change: AccountSettingsChange): AccountSettingsRepositoryResult<Unit> {
            saveCount += 1
            return AccountSettingsRepositoryResult.Success(Unit)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 2_000L
        val Preferences =
            AccountSettingsPreferences(
                historyEnabled = true,
                trashEnabled = true,
                showSubtitles = true,
                autoSelectSubtitles = true,
            )
    }
}
