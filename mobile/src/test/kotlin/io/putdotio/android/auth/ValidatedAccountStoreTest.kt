package io.putdotio.android.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.AccountStorage
import io.putdotio.android.account.InactiveAccountNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ValidatedAccountStoreTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("validated-account-test", Context.MODE_PRIVATE)
    private val store = SharedPreferencesValidatedAccountStore(preferences, Dispatchers.Unconfined)

    @Test
    fun `an account put io confirmed reads back whole`() = runBlocking {
        val account = MobileAccount(
            userId = 42,
            username = "user",
            email = "user@example.com",
            historyEnabled = true,
            avatarUrl = "https://example.com/a.png",
            storage = AccountStorage(availableBytes = 3, sizeBytes = 10, usedBytes = 7, showOptimisticUsage = true),
            inactiveNotice = InactiveAccountNotice.Deactivated(Instant.ofEpochMilli(1_788_000_000_000L)),
            diagnosticsEnabled = false,
        )

        store.write(account)

        assertEquals(account, store.read())
        val family = account.copy(avatarUrl = null, inactiveNotice = InactiveAccountNotice.FamilyPlanExpired)
        store.write(family)
        assertEquals(family, store.read())
    }

    @Test
    fun `a cleared or partial record reads as none`() = runBlocking {
        store.write(MobileAccount(userId = 42, username = "user", email = "user@example.com"))

        assertTrue(store.clear())
        assertNull(store.read())
        preferences.edit().putInt("version", 1).putLong("user_id", 42).commit()
        assertNull(store.read())
    }
}
