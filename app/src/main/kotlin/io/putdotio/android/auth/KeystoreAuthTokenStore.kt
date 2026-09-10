package io.putdotio.android.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface AuthTokenStore {
    suspend fun read(): AccessToken?

    suspend fun write(accessToken: AccessToken)

    suspend fun clear()
}

internal class AuthTokenStorageException(
    operation: String,
    cause: Throwable? = null,
) : Exception("Secure token storage failed during $operation", cause)

/**
 * Only ciphertext is stored in SharedPreferences. The non-exportable AES key
 * is generated and retained by Android Keystore for this application id.
 */
// Platform commit is intentionally used so validation waits for durability and
// can fail when the Boolean result says the write did not reach storage.
@SuppressLint("ApplySharedPref", "UseKtx")
internal class KeystoreAuthTokenStore internal constructor(
    private val preferences: SharedPreferences,
    private val tokenCipher: AuthTokenCipher,
    private val ioDispatcher: CoroutineDispatcher,
) : AuthTokenStore {
    constructor(context: Context) : this(
        preferences = context.getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE),
        tokenCipher = AndroidKeystoreAuthTokenCipher(authTokenKeyAlias(context.packageName)),
        ioDispatcher = Dispatchers.IO,
    )

    override suspend fun read(): AccessToken? = withContext(ioDispatcher) {
        storageOperation(AUTH_STORAGE_READ_OPERATION) {
            val serialized = preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null) ?: return@storageOperation null
            val encryptedValue = EncryptedAuthTokenValue.deserialize(serialized)
            val plaintext = tokenCipher.decrypt(encryptedValue)
            AccessToken.parse(String(plaintext, StandardCharsets.UTF_8))
                ?: throw IllegalArgumentException("Decrypted access token has an invalid shape")
        }
    }

    override suspend fun write(accessToken: AccessToken) = withContext(ioDispatcher) {
        storageOperation(AUTH_STORAGE_WRITE_OPERATION) {
            val plaintext = accessToken.reveal().toByteArray(StandardCharsets.UTF_8)
            val serialized = tokenCipher.encrypt(plaintext).serialize()
            if (!preferences.edit().putString(ENCRYPTED_ACCESS_TOKEN_KEY, serialized).commit()) {
                throw AuthTokenStorageException(AUTH_STORAGE_WRITE_OPERATION)
            }
        }
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        var clearFailure: AuthTokenStorageException? = null

        try {
            storageOperation(AUTH_STORAGE_CLEAR_OPERATION) {
                tokenCipher.destroyKey()
            }
        } catch (error: AuthTokenStorageException) {
            clearFailure = error
        }

        try {
            storageOperation(AUTH_STORAGE_CLEAR_OPERATION) {
                if (!preferences.edit().remove(ENCRYPTED_ACCESS_TOKEN_KEY).commit()) {
                    throw AuthTokenStorageException(AUTH_STORAGE_CLEAR_OPERATION)
                }
            }
        } catch (error: AuthTokenStorageException) {
            clearFailure?.addSuppressed(error) ?: run { clearFailure = error }
        }

        clearFailure?.let { throw it }
        Unit
    }
}

internal interface AuthTokenCipher {
    fun encrypt(plaintext: ByteArray): EncryptedAuthTokenValue

    fun decrypt(value: EncryptedAuthTokenValue): ByteArray

    fun destroyKey()
}

internal data class EncryptedAuthTokenValue(
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
) {
    fun serialize(): String = listOf(
        ENCRYPTED_VALUE_VERSION,
        initializationVector.encodeBase64Url(),
        ciphertext.encodeBase64Url(),
    ).joinToString(ENCRYPTED_VALUE_SEPARATOR)

    companion object {
        fun deserialize(serialized: String): EncryptedAuthTokenValue {
            val parts = serialized.split(ENCRYPTED_VALUE_SEPARATOR)
            require(parts.size == ENCRYPTED_VALUE_PART_COUNT && parts.first() == ENCRYPTED_VALUE_VERSION) {
                "Encrypted access token has an unsupported shape"
            }

            val initializationVector = parts[1].decodeBase64Url()
            val ciphertext = parts[2].decodeBase64Url()
            require(initializationVector.size == GCM_INITIALIZATION_VECTOR_BYTE_COUNT && ciphertext.isNotEmpty()) {
                "Encrypted access token has invalid data"
            }
            return EncryptedAuthTokenValue(initializationVector, ciphertext)
        }
    }
}

private class AndroidKeystoreAuthTokenCipher(
    private val keyAlias: String,
) : AuthTokenCipher {
    private val keyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
    }

    override fun encrypt(plaintext: ByteArray): EncryptedAuthTokenValue {
        val cipher = Cipher.getInstance(AUTH_CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return EncryptedAuthTokenValue(
            initializationVector = cipher.iv,
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    override fun decrypt(value: EncryptedAuthTokenValue): ByteArray {
        val key = keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw GeneralSecurityException("Android Keystore access-token key is missing")
        val cipher = Cipher.getInstance(AUTH_CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_AUTHENTICATION_TAG_BIT_COUNT, value.initializationVector),
        )
        return cipher.doFinal(value.ciphertext)
    }

    override fun destroyKey() {
        keyStore.deleteEntry(keyAlias)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val existingKey = keyStore.getKey(keyAlias, null)
        if (existingKey != null) {
            return existingKey as? SecretKey
                ?: throw GeneralSecurityException("Android Keystore access-token key has an unexpected type")
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }
}

private inline fun <T> storageOperation(
    operation: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (error: AuthTokenStorageException) {
        throw error
    } catch (error: GeneralSecurityException) {
        throw AuthTokenStorageException(operation, error)
    } catch (error: ProviderException) {
        throw AuthTokenStorageException(operation, error)
    } catch (error: IOException) {
        throw AuthTokenStorageException(operation, error)
    } catch (error: IllegalArgumentException) {
        throw AuthTokenStorageException(operation, error)
    } catch (error: ClassCastException) {
        throw AuthTokenStorageException(operation, error)
    }

private fun ByteArray.encodeBase64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.decodeBase64Url(): ByteArray =
    Base64.getUrlDecoder().decode(this)

internal const val AUTH_PREFERENCES_NAME = "putio_auth"
internal const val ENCRYPTED_ACCESS_TOKEN_KEY = "access_token_v1"
internal fun authTokenKeyAlias(packageName: String): String = "$packageName.$AUTH_KEY_ALIAS_SUFFIX"

private const val AUTH_KEY_ALIAS_SUFFIX = "oauth.access-token.v1"
private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val AUTH_CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_AUTHENTICATION_TAG_BIT_COUNT = 128
private const val GCM_INITIALIZATION_VECTOR_BYTE_COUNT = 12
private const val ENCRYPTED_VALUE_VERSION = "v1"
private const val ENCRYPTED_VALUE_SEPARATOR = ":"
private const val ENCRYPTED_VALUE_PART_COUNT = 3
private const val AUTH_STORAGE_READ_OPERATION = "read"
private const val AUTH_STORAGE_WRITE_OPERATION = "write"
private const val AUTH_STORAGE_CLEAR_OPERATION = "clear"
