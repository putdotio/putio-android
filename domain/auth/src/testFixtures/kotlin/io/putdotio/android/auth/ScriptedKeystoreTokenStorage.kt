package io.putdotio.android.auth

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers

/**
 * The production [KeystoreAuthTokenStore] over [preferences], with a cipher the test scripts in
 * place of Android Keystore, so other modules can drive sign-in through real token storage.
 */
public class ScriptedKeystoreTokenStorage(private val preferences: SharedPreferences) {
    private val cipher = ScriptedTokenCipher()

    public val store: AuthTokenStore = KeystoreAuthTokenStore(preferences, cipher, Dispatchers.Unconfined)

    /** Thrown by every decrypt while set, as Android Keystore would. */
    public var decryptFailure: Exception? by cipher::decryptFailure

    /** Thrown by every key deletion while set. */
    public var destroyFailure: Exception? by cipher::destroyFailure

    public val destroyKeyCalls: Int get() = cipher.destroyKeyCalls

    /** The stored access-token record, or null when storage holds none. */
    public val record: String? get() = preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null)

    /** Replaces the stored record with one that can never decrypt. */
    public fun corruptRecord() {
        check(preferences.edit().putString(ENCRYPTED_ACCESS_TOKEN_KEY, "v1:not-a-record").commit())
    }

    public companion object {
        /** The failure Android Keystore reports once the token key is gone. */
        public fun missingKey(): Exception = MissingAuthTokenKeyException()
    }
}

private class ScriptedTokenCipher : AuthTokenCipher {
    var decryptFailure: Exception? = null
    var destroyFailure: Exception? = null
    var destroyKeyCalls = 0
        private set

    override fun encrypt(plaintext: ByteArray): EncryptedAuthTokenValue =
        EncryptedAuthTokenValue(ByteArray(INITIALIZATION_VECTOR_SIZE), plaintext.reversedArray())

    override fun decrypt(value: EncryptedAuthTokenValue): ByteArray {
        decryptFailure?.let { throw it }
        return value.ciphertext.reversedArray()
    }

    override fun destroyKey() {
        destroyKeyCalls += 1
        destroyFailure?.let { throw it }
    }
}

private const val INITIALIZATION_VECTOR_SIZE = 12
