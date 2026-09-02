package io.putdotio.android

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigRepository
import io.putdotio.android.settings.AndroidAppConfigRepositoryResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test
    fun sameUserReauthenticationClosesThenReplacesTheController() {
        val repository = RecordingRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(ConfigHostActivity::class.java).setup()

        try {
            val viewModel = activityController.get().configViewModel(authState)
            val first = checkNotNull(viewModel.controllerFor(USER_ID, SessionOne, repository))
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking(Dispatchers.Default) {
                authState.value = MobileAuthState.SignedOut()
                authState.value = signedIn(SessionTwo)
            }
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(first.dispatch(AndroidAppConfigEvent.RetryLoad))
            val second = checkNotNull(viewModel.controllerFor(USER_ID, SessionTwo, repository))
            shadowOf(Looper.getMainLooper()).idle()

            assertNotSame(first, second)
            assertEquals(2, repository.loadCount)
        } finally {
            activityController.close()
        }
    }

    @Test
    fun sessionReplacementCancelsThePreviousInFlightLoad() {
        val repository = SuspendingRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(ConfigHostActivity::class.java).setup()

        try {
            val viewModel = activityController.get().configViewModel(authState)
            val first = checkNotNull(viewModel.controllerFor(USER_ID, SessionOne, repository))
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) { repository.started.await() }
            }

            authState.value = signedIn(SessionTwo)
            shadowOf(Looper.getMainLooper()).idle()

            runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) { repository.cancelled.await() }
            }
            assertFalse(first.dispatch(AndroidAppConfigEvent.RetryLoad))
            assertNotSame(
                first,
                viewModel.controllerFor(USER_ID, SessionTwo, RecordingRepository()),
            )
        } finally {
            activityController.close()
        }
    }

    @Test
    fun staleSessionCannotRecreateAControllerAfterSignOut() {
        val repository = RecordingRepository()
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val activityController = Robolectric.buildActivity(ConfigHostActivity::class.java).setup()

        try {
            val viewModel = activityController.get().configViewModel(authState)
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

        override suspend fun load(): AndroidAppConfigRepositoryResult<AndroidAppConfigPreferences> {
            loadCount += 1
            return AndroidAppConfigRepositoryResult.Success(Preferences)
        }

        override suspend fun save(
            change: io.putdotio.android.settings.AndroidAppConfigChange,
        ): AndroidAppConfigRepositoryResult<Unit> = AndroidAppConfigRepositoryResult.Success(Unit)
    }

    private class SuspendingRepository : AndroidAppConfigRepository {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()

        override suspend fun load(): AndroidAppConfigRepositoryResult<AndroidAppConfigPreferences> {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        override suspend fun save(
            change: io.putdotio.android.settings.AndroidAppConfigChange,
        ): AndroidAppConfigRepositoryResult<Unit> = error("Save is not expected")
    }

    private companion object {
        const val USER_ID = 42L
        const val TEST_TIMEOUT_MILLIS = 2_000L
        val Account = MobileAccount(USER_ID, "user", "user@example.com")
        val SessionOne = MobileAuthSessionId(1L)
        val SessionTwo = MobileAuthSessionId(2L)
        val Preferences = AndroidAppConfigPreferences()

        fun signedIn(sessionId: MobileAuthSessionId): MobileAuthState.SignedIn =
            MobileAuthState.SignedIn(Account, sessionId)
    }
}
