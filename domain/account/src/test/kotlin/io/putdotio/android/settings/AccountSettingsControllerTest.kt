package io.putdotio.android.settings

import io.putdotio.android.PutioFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSettingsControllerTest {
    @Test
    fun immediateRetryFromTheFailureCollectorStartsANewEffect() =
        runBlocking<Unit> {
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
    fun closeCancelsAnInFlightLoad() =
        runBlocking<Unit> {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val repository =
                object : AccountSettingsRepository {
                    override suspend fun load(): AccountSettingsRepositoryResult<AccountSettingsPreferences> {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }

                    override suspend fun save(change: AccountSettingsChange): AccountSettingsRepositoryResult<Unit> =
                        error("Save is not expected")

                    override suspend fun loadTunnelRoutes(): AccountSettingsRepositoryResult<List<TunnelRouteOption>> =
                        error("Tunnel routes are not expected")
                }
            val controller = AccountSettingsController(repository, this)

            started.await()
            controller.close()

            withTimeout(TEST_TIMEOUT_MILLIS) { cancelled.await() }
            assertTrue(coroutineContext[Job]?.isActive == true)
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

        override suspend fun save(change: AccountSettingsChange): AccountSettingsRepositoryResult<Unit> {
            savedChanges += change
            return if (savedChanges.size == 1) {
                AccountSettingsRepositoryResult.Failure(
                    AccountSettingsFailure.Putio(PutioFailure.Unexpected(IllegalStateException("offline"))),
                )
            } else {
                AccountSettingsRepositoryResult.Success(Unit)
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 2_000L
        val Preferences = DefaultAccountSettingsPreferences
    }
}
