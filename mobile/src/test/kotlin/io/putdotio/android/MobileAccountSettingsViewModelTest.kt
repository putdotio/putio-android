package io.putdotio.android

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.MobileAccountSettingsViewModel
import io.putdotio.android.account.mobileAccountSettingsViewModelFactory
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsRepository
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.TunnelRouteOption
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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

    private fun SettingsHostActivity.settingsViewModel(
        authState: MutableStateFlow<MobileAuthState>,
    ): MobileAccountSettingsViewModel =
        ViewModelProvider(
            this,
            mobileAccountSettingsViewModelFactory(authState),
        )[MobileAccountSettingsViewModel::class.java]

    class SettingsHostActivity : ComponentActivity()

    private class RecordingRepository : AccountSettingsRepository {
        override suspend fun loadTunnelRoutes(): AccountSettingsRepositoryResult<List<TunnelRouteOption>> =
            error("Tunnel routes are not expected")

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
