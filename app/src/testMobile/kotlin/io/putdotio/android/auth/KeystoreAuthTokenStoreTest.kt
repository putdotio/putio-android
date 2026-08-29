package io.putdotio.android.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.ProviderException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeystoreAuthTokenStoreTest {
    @Test
    fun `store persists only encrypted payload and can clear it`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = KeystoreAuthTokenStore(
            preferences = preferences,
            tokenCipher = DeterministicTokenCipher,
            ioDispatcher = Dispatchers.Unconfined,
        )
        val accessToken = checkNotNull(AccessToken.parse(ACCESS_TOKEN))

        store.write(accessToken)

        val persisted = preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null)
        assertFalse(persisted.isNullOrEmpty())
        assertFalse(persisted.orEmpty().contains(ACCESS_TOKEN))
        assertEquals(ACCESS_TOKEN, store.read()?.reveal())

        store.clear()
        assertNull(store.read())
    }

    @Test
    fun `provider failures stay inside the typed storage boundary`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val failure = ProviderException("keystore unavailable")
        val store = KeystoreAuthTokenStore(
            preferences = preferences,
            tokenCipher = FailingTokenCipher(failure),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val error = assertThrows(AuthTokenStorageException::class.java) {
            runBlocking {
                store.write(checkNotNull(AccessToken.parse(ACCESS_TOKEN)))
            }
        }

        assertSame(failure, error.cause)
    }

    private object DeterministicTokenCipher : AuthTokenCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedAuthTokenValue =
            EncryptedAuthTokenValue(
                initializationVector = ByteArray(INITIALIZATION_VECTOR_SIZE) { it.toByte() },
                ciphertext = plaintext.map { (it.toInt() xor CIPHER_MASK).toByte() }.toByteArray(),
            )

        override fun decrypt(value: EncryptedAuthTokenValue): ByteArray =
            value.ciphertext.map { (it.toInt() xor CIPHER_MASK).toByte() }.toByteArray()
    }

    private class FailingTokenCipher(
        private val failure: ProviderException,
    ) : AuthTokenCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedAuthTokenValue = throw failure

        override fun decrypt(value: EncryptedAuthTokenValue): ByteArray = throw failure
    }

    private companion object {
        const val ACCESS_TOKEN = "secret-token-value"
        const val INITIALIZATION_VECTOR_SIZE = 12
        const val CIPHER_MASK = 0x5a
    }
}
