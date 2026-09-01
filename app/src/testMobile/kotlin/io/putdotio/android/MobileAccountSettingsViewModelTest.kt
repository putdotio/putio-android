package io.putdotio.android

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsRepository
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileAccountSettingsViewModelTest {

    @Test
    fun loadedSettingsSurviveActivityRecreation() {
        val repository = RecordingRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(SettingsHostActivity::class.java).setup()

        try {
            val beforeActivity = activityController.get()
            val beforeViewModel = beforeActivity.settingsViewModel(authState)
            val beforeController = checkNotNull(beforeViewModel.controllerFor(USER_ID, SessionOne, repository))
            shadowOf(Looper.getMainLooper()).idle()

            activityController.recreate()

            val afterActivity = activityController.get()
            val afterViewModel = afterActivity.settingsViewModel(authState)
            val afterController = checkNotNull(afterViewModel.controllerFor(USER_ID, SessionOne, repository))
            assertNotSame(beforeActivity, afterActivity)
            assertSame(beforeViewModel, afterViewModel)
            assertSame(beforeController, afterController)
            assertEquals(1, repository.loadCount)
            assertEquals(Preferences, (afterController.state.value.content as AccountSettingsContent.Ready).preferences)
        } finally {
            activityController.close()
        }
    }

    @Test
    fun sameUserReauthenticationClosesThenReplacesTheController() {
        val repository = RecordingRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(SettingsHostActivity::class.java).setup()

        try {
            val viewModel = activityController.get().settingsViewModel(authState)
            val first = checkNotNull(viewModel.controllerFor(USER_ID, SessionOne, repository))
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking(Dispatchers.Default) {
                authState.value = MobileAuthState.SignedOut()
                authState.value = signedIn(SessionTwo)
            }
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(first.dispatch(AccountSettingsEvent.RetryLoad))
            val second = checkNotNull(viewModel.controllerFor(USER_ID, SessionTwo, repository))
            shadowOf(Looper.getMainLooper()).idle()

            assertNotSame(first, second)
            assertEquals(2, repository.loadCount)
        } finally {
            activityController.close()
        }
    }

    @Test
    fun staleSessionCannotRecreateAControllerAfterSignOut() {
        val repository = RecordingRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(SettingsHostActivity::class.java).setup()

        try {
            val viewModel = activityController.get().settingsViewModel(authState)
            checkNotNull(viewModel.controllerFor(USER_ID, SessionOne, repository))
            shadowOf(Looper.getMainLooper()).idle()

            authState.value = MobileAuthState.SignedOut()
            shadowOf(Looper.getMainLooper()).idle()

            assertNull(viewModel.controllerFor(USER_ID, SessionOne, repository))
            assertEquals(1, repository.loadCount)
        } finally {
            activityController.close()
        }
    }

    private fun SettingsHostActivity.settingsViewModel(
        authState: MutableStateFlow<MobileAuthState>,
    ): MobileAccountSettingsViewModel =
        ViewModelProvider(
            this,
            mobileAccountSettingsViewModelFactory(authState),
        )[MobileAccountSettingsViewModel::class.java]

    class SettingsHostActivity : ComponentActivity()

    private class RecordingRepository : AccountSettingsRepository {
        var loadCount = 0

        override suspend fun load(): AccountSettingsRepositoryResult<AccountSettingsPreferences> {
            loadCount += 1
            return AccountSettingsRepositoryResult.Success(Preferences)
        }

        override suspend fun save(
            change: AccountSettingsChange,
        ): AccountSettingsRepositoryResult<Unit> = AccountSettingsRepositoryResult.Success(Unit)
    }

    private companion object {
        const val USER_ID = 42L
        val Account = MobileAccount(USER_ID, "user", "user@example.com")
        val SessionOne = MobileAuthSessionId(1L)
        val SessionTwo = MobileAuthSessionId(2L)
        val Preferences =
            AccountSettingsPreferences(
                historyEnabled = true,
                trashEnabled = true,
                showSubtitles = true,
                autoSelectSubtitles = true,
            )

        fun signedIn(sessionId: MobileAuthSessionId): MobileAuthState.SignedIn =
            MobileAuthState.SignedIn(Account, sessionId)
    }
}
