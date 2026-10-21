package io.putdotio.android

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioResult
import io.putdotio.android.account.MobileAndroidAppConfigViewModel
import io.putdotio.android.account.mobileAndroidAppConfigViewModelFactory
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigRepository
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
class MobileAndroidAppConfigViewModelTest {

    @Test
    fun loadedConfigSurvivesActivityRecreation() {
        val repository = RecordingRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(ConfigHostActivity::class.java).setup()

        try {
            val beforeActivity = activityController.get()
            val beforeViewModel = beforeActivity.configViewModel(authState)
            val beforeController = checkNotNull(beforeViewModel.controllerFor(USER_ID, SessionOne, repository))
            shadowOf(Looper.getMainLooper()).idle()

            activityController.recreate()

            val afterActivity = activityController.get()
            val afterViewModel = afterActivity.configViewModel(authState)
            val afterController = checkNotNull(afterViewModel.controllerFor(USER_ID, SessionOne, repository))
            assertNotSame(beforeActivity, afterActivity)
            assertSame(beforeViewModel, afterViewModel)
            assertSame(beforeController, afterController)
            assertEquals(1, repository.loadCount)
            assertEquals(
                Preferences,
                (afterController.state.value.content as AndroidAppConfigContent.Ready).preferences,
            )
        } finally {
            activityController.close()
        }
    }

    private fun ConfigHostActivity.configViewModel(
        authState: MutableStateFlow<MobileAuthState>,
    ): MobileAndroidAppConfigViewModel =
        ViewModelProvider(
            this,
            mobileAndroidAppConfigViewModelFactory(authState),
        )[MobileAndroidAppConfigViewModel::class.java]

    class ConfigHostActivity : ComponentActivity()

    private class RecordingRepository : AndroidAppConfigRepository {
        var loadCount = 0

        override suspend fun load(): PutioResult<AndroidAppConfigPreferences> {
            loadCount += 1
            return PutioResult.Success(Preferences)
        }

        override suspend fun save(change: AndroidAppConfigChange): PutioResult<Unit> = PutioResult.Success(Unit)
    }

    private companion object {
        const val USER_ID = 42L
        val Account = MobileAccount(USER_ID, "user", "user@example.com")
        val SessionOne = MobileAuthSessionId(1L)
        val Preferences = AndroidAppConfigPreferences()

        fun signedIn(sessionId: MobileAuthSessionId): MobileAuthState.SignedIn =
            MobileAuthState.SignedIn(Account, sessionId)
    }
}
