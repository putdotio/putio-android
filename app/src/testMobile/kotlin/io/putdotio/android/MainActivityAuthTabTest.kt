package io.putdotio.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Looper
import androidx.browser.auth.AuthTabIntent
import io.putdotio.android.auth.AUTH_PREFERENCES_NAME
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.PENDING_OAUTH_CREATED_AT_KEY
import io.putdotio.android.auth.PENDING_OAUTH_STATE_KEY
import io.putdotio.android.auth.PendingOAuthAttempt
import io.putdotio.android.auth.SharedPreferencesPendingOAuthAttemptStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityAuthTabTest {
    @Before
    fun resetRuntimeBeforeTest() {
        MobileOAuthRuntime.resetForTests()
    }

    @After
    fun resetRuntimeAfterTest() {
        MobileOAuthRuntime.resetForTests()
    }

    @Test
    fun `registered Auth Tab launcher consumes callback after Activity recreation`() {
        assertResultConsumedAfterRecreation(
            AuthTabResult(
                resultCode = AuthTabIntent.RESULT_OK,
                resultUri = "putio://auth#state=wrong-state&access_token=token",
            ),
        )
    }

    @Test
    fun `registered Auth Tab launcher consumes cancellation after Activity recreation`() {
        assertResultConsumedAfterRecreation(
            AuthTabResult(
                resultCode = AuthTabIntent.RESULT_CANCELED,
            ),
        )
    }

    @Test
    fun `registered Auth Tab launcher consumes failure after Activity recreation`() {
        assertResultConsumedAfterRecreation(
            AuthTabResult(
                resultCode = AuthTabIntent.RESULT_VERIFICATION_FAILED,
            ),
        )
    }

    private fun assertResultConsumedAfterRecreation(result: AuthTabResult) {
        val activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        val preferences = activityController.get().getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val pendingAttemptCleared = CountDownLatch(1)
        val preferenceListener = pendingAttemptClearListener(preferences, pendingAttemptCleared)
        try {
            val pendingAttemptStore = SharedPreferencesPendingOAuthAttemptStore(activityController.get())
            runBlocking {
                pendingAttemptStore.write(
                    PendingOAuthAttempt(
                        state = "expected-state",
                        createdAtEpochMillis = System.currentTimeMillis().coerceAtLeast(1L),
                    ),
                )
            }
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)

            activityController.get().authTabLauncher.launch(Intent("io.putdotio.android.TEST_AUTH_TAB"))
            val requestCode = shadowOf(activityController.get()).nextStartedActivityForResult.requestCode

            activityController.recreate()
            val resultIntent = result.resultUri?.let { Intent().setData(Uri.parse(it)) }
            assertTrue(
                activityController.get().activityResultRegistry.dispatchResult(
                    requestCode,
                    result.resultCode,
                    resultIntent,
                ),
            )

            waitForPendingAttemptClear(pendingAttemptCleared)
            assertFalse(preferences.contains(PENDING_OAUTH_STATE_KEY))
            assertFalse(preferences.contains(PENDING_OAUTH_CREATED_AT_KEY))
        } finally {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            activityController.close()
        }
    }

    private fun pendingAttemptClearListener(
        preferences: SharedPreferences,
        cleared: CountDownLatch,
    ): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key in PENDING_OAUTH_KEYS &&
                !preferences.contains(PENDING_OAUTH_STATE_KEY) &&
                !preferences.contains(PENDING_OAUTH_CREATED_AT_KEY)
            ) {
                cleared.countDown()
            }
        }

    private fun waitForPendingAttemptClear(cleared: CountDownLatch) {
        repeat(RESULT_WAIT_ATTEMPTS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cleared.await(RESULT_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                return
            }
        }
        assertTrue("pending OAuth attempt was not consumed", cleared.count == 0L)
    }

    private data class AuthTabResult(
        val resultCode: Int,
        val resultUri: String? = null,
    )

    private companion object {
        const val RESULT_WAIT_ATTEMPTS = 500
        const val RESULT_WAIT_MILLIS = 10L
        val PENDING_OAUTH_KEYS = setOf(PENDING_OAUTH_STATE_KEY, PENDING_OAUTH_CREATED_AT_KEY)
    }
}
