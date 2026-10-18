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
        val preferences = freshPreferences()
        val cipher = FakeTokenCipher()
        val store = KeystoreAuthTokenStore(preferences, cipher, Dispatchers.Unconfined)

        store.write(TOKEN)

        assertFalse(checkNotNull(preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null)).contains(ACCESS_TOKEN))
        assertEquals(ACCESS_TOKEN, store.read()?.reveal())

        store.clear()
        assertNull(store.read())
        assertEquals(1, cipher.destroyKeyCalls)
    }

    @Test
    fun `clear removes ciphertext when key destruction fails`() {
        val preferences = freshPreferences(record = "ciphertext")
        val failure = ProviderException("keystore unavailable")
        val cipher = FakeTokenCipher(destroyFailure = failure)
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
        val preferences = freshPreferences(record = "ciphertext")
        val cipher = FakeTokenCipher()
        val store = KeystoreAuthTokenStore(CommitFailingSharedPreferences(preferences), cipher, Dispatchers.Unconfined)

        assertThrows(AuthTokenStorageException::class.java) {
            runBlocking { store.clear() }
        }

        assertEquals(1, cipher.destroyKeyCalls)
        assertEquals("ciphertext", preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null))
    }

    @Test
    fun `provider failures stay inside the typed storage boundary`() {
        val failure = ProviderException("keystore unavailable")
        val store = KeystoreAuthTokenStore(
            freshPreferences(),
            FakeTokenCipher(encryptFailure = failure),
            Dispatchers.Unconfined,
        )

        val error = assertThrows(AuthTokenStorageException::class.java) {
            runBlocking { store.write(TOKEN) }
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
            val cipher = FakeTokenCipher()
            val store = KeystoreAuthTokenStore(preferences, cipher, Dispatchers.Unconfined)
            store.write(TOKEN)
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
        val cipher = FakeTokenCipher()
        val store = KeystoreAuthTokenStore(preferences, cipher, Dispatchers.Unconfined)
        runBlocking { store.write(TOKEN) }
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
        val preferences = freshPreferences(record = "v1:not-a-record")
        val store = KeystoreAuthTokenStore(
            preferences = CommitFailingSharedPreferences(preferences),
            tokenCipher = FakeTokenCipher(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertThrows(AuthTokenStorageException::class.java) {
            runBlocking { store.read() }
        }
    }

    @Test
    fun `clearing the session keeps a token awaiting revocation`() = runBlocking {
        val preferences = freshPreferences()
        val session = KeystoreAuthTokenStore(preferences, FakeTokenCipher(), Dispatchers.Unconfined)
        val pendingRevocation = KeystoreAuthTokenStore(
            preferences = preferences,
            tokenCipher = FakeTokenCipher(),
            ioDispatcher = Dispatchers.Unconfined,
            recordKey = PENDING_REVOCATION_TOKEN_KEY,
        )
        session.write(TOKEN)
        pendingRevocation.write(TOKEN)

        session.clear()

        assertNull(session.read())
        assertEquals(ACCESS_TOKEN, pendingRevocation.read()?.reveal())
        assertFalse(preferences.getString(PENDING_REVOCATION_TOKEN_KEY, null).orEmpty().contains(ACCESS_TOKEN))
    }

    private fun freshPreferences(record: String? = null): SharedPreferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .also { it.edit().clear().putString(ENCRYPTED_ACCESS_TOKEN_KEY, record).commit() }

    private class FakeTokenCipher(
        private val encryptFailure: Exception? = null,
        private val destroyFailure: Exception? = null,
    ) : AuthTokenCipher {
        var destroyKeyCalls = 0
            private set
        var decryptFailure: Exception? = null

        override fun encrypt(plaintext: ByteArray): EncryptedAuthTokenValue {
            encryptFailure?.let { throw it }
            return EncryptedAuthTokenValue(ByteArray(INITIALIZATION_VECTOR_SIZE), plaintext.masked())
        }

        override fun decrypt(value: EncryptedAuthTokenValue): ByteArray {
            decryptFailure?.let { throw it }
            return value.ciphertext.masked()
        }

        override fun destroyKey() {
            destroyKeyCalls += 1
            destroyFailure?.let { throw it }
        }

        private fun ByteArray.masked() = map { (it.toInt() xor CIPHER_MASK).toByte() }.toByteArray()
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
        val TOKEN = checkNotNull(AccessToken.parse(ACCESS_TOKEN))
        const val INITIALIZATION_VECTOR_SIZE = 12
        const val CIPHER_MASK = 0x5a
    }
}
