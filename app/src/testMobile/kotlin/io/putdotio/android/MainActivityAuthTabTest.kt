package io.putdotio.android

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.browser.auth.AuthTabIntent
import io.putdotio.android.auth.PendingOAuthAttempt
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.SharedPreferencesPendingOAuthAttemptStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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
        assertResultConsumedAfterRecreation(AuthTabResult(resultCode = AuthTabIntent.RESULT_CANCELED))
    }

    @Test
    fun `registered Auth Tab launcher consumes failure after Activity recreation`() {
        assertResultConsumedAfterRecreation(AuthTabResult(resultCode = AuthTabIntent.RESULT_VERIFICATION_FAILED))
    }

    private fun assertResultConsumedAfterRecreation(result: AuthTabResult) {
        val activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val pendingAttemptStore = SharedPreferencesPendingOAuthAttemptStore(activityController.get())
            runBlocking {
                pendingAttemptStore.write(
                    PendingOAuthAttempt(
                        state = "expected-state",
                        createdAtEpochMillis = 1L,
                    ),
                )
            }

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

            waitForPendingAttemptToClear(pendingAttemptStore)
        } finally {
            activityController.close()
        }
    }

    private fun waitForPendingAttemptToClear(store: SharedPreferencesPendingOAuthAttemptStore) {
        repeat(RESULT_WAIT_ATTEMPTS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (runBlocking { store.read() } == null) {
                return
            }
            Thread.sleep(RESULT_WAIT_MILLIS)
        }
        assertNull(runBlocking { store.read() })
    }

    private data class AuthTabResult(
        val resultCode: Int,
        val resultUri: String? = null,
    )

    private companion object {
        const val RESULT_WAIT_ATTEMPTS = 100
        const val RESULT_WAIT_MILLIS = 10L
    }
}
