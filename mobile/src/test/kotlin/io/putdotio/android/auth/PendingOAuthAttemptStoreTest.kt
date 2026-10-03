package io.putdotio.android.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PendingOAuthAttemptStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences by lazy {
        context.getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val store by lazy {
        SharedPreferencesPendingOAuthAttemptStore(preferences, Dispatchers.Unconfined)
    }

    @Before
    fun clearBefore() {
        preferences.edit().clear().commit()
    }

    @After
    fun clearAfter() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `pending attempt round trips and clears atomically`() = runBlocking {
        val attempt = PendingOAuthAttempt("oauth-state", 1_788_000_000_000L)

        store.write(attempt)

        assertEquals(attempt, store.read())
        store.clear()
        assertNull(store.read())
    }

    @Test
    fun `incomplete pending attempt fails closed`() = runBlocking {
        preferences.edit().putString(PENDING_OAUTH_STATE_KEY, "oauth-state").commit()

        val result = runCatching { store.read() }

        assertTrue(result.exceptionOrNull() is PendingOAuthAttemptStorageException)
    }
}
