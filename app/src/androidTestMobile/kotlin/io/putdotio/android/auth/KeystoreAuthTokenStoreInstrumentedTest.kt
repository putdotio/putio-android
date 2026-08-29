package io.putdotio.android.auth

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreAuthTokenStoreInstrumentedTest {
    @Test
    fun productionKeystoreRoundTripRejectsTampering() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE)
        val keyAlias = authTokenKeyAlias(context.packageName)
        deleteKey(keyAlias)
        preferences.edit().clear().commit()
        val store = KeystoreAuthTokenStore(context)
        val token = checkNotNull(AccessToken.parse("instrumented-access-token"))

        try {
            store.write(token)
            assertTrue(loadKeyStore().containsAlias(keyAlias))
            val serialized = checkNotNull(preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null))
            assertFalse(serialized.contains(token.reveal()))
            assertEquals(token.reveal(), store.read()?.reveal())

            val encrypted = EncryptedAuthTokenValue.deserialize(serialized)
            val tamperedCiphertext = encrypted.ciphertext.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
            preferences.edit().putString(
                ENCRYPTED_ACCESS_TOKEN_KEY,
                encrypted.copy(ciphertext = tamperedCiphertext).serialize(),
            ).commit()

            val tamperedRead = runCatching { store.read() }
            assertTrue(tamperedRead.exceptionOrNull() is AuthTokenStorageException)
        } finally {
            try {
                store.clear()
                assertFalse(loadKeyStore().containsAlias(keyAlias))
            } finally {
                deleteKey(keyAlias)
            }
        }

        assertNull(store.read())
    }

    private fun deleteKey(alias: String) {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
        assertFalse(keyStore.containsAlias(alias))
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
