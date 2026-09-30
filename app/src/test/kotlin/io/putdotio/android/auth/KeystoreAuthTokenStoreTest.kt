package io.putdotio.android.auth

import android.content.Context
import android.content.SharedPreferences
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
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeystoreAuthTokenStoreTest {
    @Test
    fun `store persists only encrypted payload and can clear it`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val cipher = DeterministicTokenCipher()
        val store = KeystoreAuthTokenStore(
            preferences = preferences,
            tokenCipher = cipher,
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
        assertEquals(1, cipher.destroyKeyCalls)
    }

    @Test
    fun `clear removes ciphertext when key destruction fails`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().putString(ENCRYPTED_ACCESS_TOKEN_KEY, "ciphertext").commit()
        val failure = ProviderException("keystore unavailable")
        val cipher = TrackingTokenCipher(destroyFailure = failure)
        val store = KeystoreAuthTokenStore(preferences, cipher, Dispatchers.Unconfined)

        val error = assertThrows(AuthTokenStorageException::class.java) {
            runBlocking { store.clear() }
        }

        assertSame(failure, error.cause)
        assertEquals(1, cipher.destroyKeyCalls)
        assertNull(preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null))
    }

    @Test
    fun `clear destroys key when ciphertext removal fails`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().putString(ENCRYPTED_ACCESS_TOKEN_KEY, "ciphertext").commit()
        val cipher = TrackingTokenCipher()
        val store = KeystoreAuthTokenStore(
            preferences = CommitFailingSharedPreferences(preferences),
            tokenCipher = cipher,
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertThrows(AuthTokenStorageException::class.java) {
            runBlocking { store.clear() }
        }

        assertEquals(1, cipher.destroyKeyCalls)
        assertEquals("ciphertext", preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null))
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

    @Test
    fun `undecryptable records are wiped with their key and read as absent`() = runBlocking {
        val cases = listOf(
            "malformed record" to null,
            "missing key" to MissingAuthTokenKeyException(),
            "bad GCM tag" to AEADBadTagException("tag mismatch"),
        )
        for ((case, decryptFailure) in cases) {
            val preferences = freshPreferences()
            val cipher = DeterministicTokenCipher()
            val store = KeystoreAuthTokenStore(preferences, cipher, Dispatchers.Unconfined)
            store.write(checkNotNull(AccessToken.parse(ACCESS_TOKEN)))
            if (decryptFailure == null) {
                preferences.edit().putString(ENCRYPTED_ACCESS_TOKEN_KEY, "v1:not-a-record").commit()
            }
            cipher.decryptFailure = decryptFailure

            assertNull(case, store.read())
            assertNull(case, preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null))
            assertEquals(case, 1, cipher.destroyKeyCalls)
        }
    }

    @Test
    fun `transient read failures keep the record and stay storage errors`() {
        val preferences = freshPreferences()
        val cipher = DeterministicTokenCipher()
        val store = KeystoreAuthTokenStore(preferences, cipher, Dispatchers.Unconfined)
        runBlocking { store.write(checkNotNull(AccessToken.parse(ACCESS_TOKEN))) }
        val failure = ProviderException("keystore busy")
        cipher.decryptFailure = failure

        val error = assertThrows(AuthTokenStorageException::class.java) {
            runBlocking { store.read() }
        }

        assertSame(failure, error.cause)
        assertEquals(0, cipher.destroyKeyCalls)
        cipher.decryptFailure = null
        assertEquals(ACCESS_TOKEN, runBlocking { store.read() }?.reveal())
    }

    @Test
    fun `failed wipe of an undecryptable record stays a storage error`() {
        val preferences = freshPreferences()
        preferences.edit().putString(ENCRYPTED_ACCESS_TOKEN_KEY, "v1:not-a-record").commit()
        val store = KeystoreAuthTokenStore(
            preferences = CommitFailingSharedPreferences(preferences),
            tokenCipher = DeterministicTokenCipher(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertThrows(AuthTokenStorageException::class.java) {
            runBlocking { store.read() }
        }
    }

    @Test
    fun `clearing the session keeps a token awaiting revocation`() = runBlocking {
        val preferences = freshPreferences()
        val session = KeystoreAuthTokenStore(preferences, DeterministicTokenCipher(), Dispatchers.Unconfined)
        val pendingRevocation = KeystoreAuthTokenStore(
            preferences = preferences,
            tokenCipher = DeterministicTokenCipher(),
            ioDispatcher = Dispatchers.Unconfined,
            recordKey = PENDING_REVOCATION_TOKEN_KEY,
        )
        val accessToken = checkNotNull(AccessToken.parse(ACCESS_TOKEN))
        session.write(accessToken)
        pendingRevocation.write(accessToken)

        session.clear()

        assertNull(session.read())
        assertEquals(ACCESS_TOKEN, pendingRevocation.read()?.reveal())
        assertFalse(preferences.getString(PENDING_REVOCATION_TOKEN_KEY, null).orEmpty().contains(ACCESS_TOKEN))
    }

    private fun freshPreferences(): SharedPreferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }

    private class DeterministicTokenCipher : AuthTokenCipher {
        var destroyKeyCalls = 0
        var decryptFailure: Exception? = null

        override fun encrypt(plaintext: ByteArray): EncryptedAuthTokenValue =
            EncryptedAuthTokenValue(
                initializationVector = ByteArray(INITIALIZATION_VECTOR_SIZE) { it.toByte() },
                ciphertext = plaintext.map { (it.toInt() xor CIPHER_MASK).toByte() }.toByteArray(),
            )

        override fun decrypt(value: EncryptedAuthTokenValue): ByteArray {
            decryptFailure?.let { throw it }
            return value.ciphertext.map { (it.toInt() xor CIPHER_MASK).toByte() }.toByteArray()
        }

        override fun destroyKey() {
            destroyKeyCalls += 1
        }
    }

    private class FailingTokenCipher(
        private val failure: ProviderException,
    ) : AuthTokenCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedAuthTokenValue = throw failure

        override fun decrypt(value: EncryptedAuthTokenValue): ByteArray = throw failure

        override fun destroyKey() = throw failure
    }

    private class TrackingTokenCipher(
        private val destroyFailure: ProviderException? = null,
    ) : AuthTokenCipher {
        var destroyKeyCalls = 0
            private set

        override fun encrypt(plaintext: ByteArray): EncryptedAuthTokenValue = error("not used")

        override fun decrypt(value: EncryptedAuthTokenValue): ByteArray = error("not used")

        override fun destroyKey() {
            destroyKeyCalls += 1
            destroyFailure?.let { throw it }
        }
    }

    private class CommitFailingSharedPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun remove(key: String): SharedPreferences.Editor {
                    editor.remove(key)
                    return this
                }

                override fun commit(): Boolean = false
            }
        }
    }

    private companion object {
        const val ACCESS_TOKEN = "secret-token-value"
        const val INITIALIZATION_VECTOR_SIZE = 12
        const val CIPHER_MASK = 0x5a
    }
}
