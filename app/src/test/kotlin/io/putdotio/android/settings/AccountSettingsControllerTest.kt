package io.putdotio.android.settings

import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
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

    private suspend fun AccountSettingsController.awaitState(
        predicate: (AccountSettingsState) -> Boolean,
    ): AccountSettingsState = withTimeout(TEST_TIMEOUT_MILLIS) { state.first(predicate) }

    private class RecordingRepository : AccountSettingsRepository {
        val savedChanges = mutableListOf<AccountSettingsChange>()

        override suspend fun load(): AccountSettingsRepositoryResult<AccountSettingsPreferences> =
            AccountSettingsRepositoryResult.Success(Preferences)

        override suspend fun save(
            change: AccountSettingsChange,
        ): AccountSettingsRepositoryResult<AccountSettingsPreferences> {
            savedChanges += change
            return if (savedChanges.size == 1) {
                AccountSettingsRepositoryResult.Failure(
                    AccountSettingsFailure.Unexpected(IllegalStateException("offline")),
                )
            } else {
                AccountSettingsRepositoryResult.Success(
                    Preferences.copy(historyEnabled = change.enabled),
                )
            }
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
