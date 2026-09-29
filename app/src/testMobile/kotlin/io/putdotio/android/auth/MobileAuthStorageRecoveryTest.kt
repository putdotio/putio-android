package io.putdotio.android.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.ProviderException
import javax.crypto.AEADBadTagException

// Drives the controller through the production Keystore store with a scripted cipher.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobileAuthStorageRecoveryTest {
    @Test
    fun `undecryptable stored token wipes storage and offers a normal sign in`() = runBlocking {
        val cases = listOf(
            "malformed record" to null,
            "missing key" to MissingAuthTokenKeyException(),
            "bad GCM tag" to AEADBadTagException("tag mismatch"),
        )
        for ((case, decryptFailure) in cases) {
            val fixture = Fixture()
            fixture.storeToken()
            if (decryptFailure == null) {
                fixture.preferences.edit().putString(ENCRYPTED_ACCESS_TOKEN_KEY, "v1:not-a-record").commit()
            }
            fixture.cipher.decryptFailure = decryptFailure

            fixture.controller.restoreSession()

            assertEquals(case, MobileAuthState.SignedOut(), fixture.controller.state.value)
            assertNull(case, fixture.preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null))
            assertEquals(case, 1, fixture.cipher.destroyKeyCalls)
            assertEquals(case, OAuthLaunchResult.Ready(AUTHORIZATION_URL), fixture.controller.beginSignIn())
        }
    }

    @Test
    fun `transient read failure stays retryable and reset signs in again`() = runBlocking {
        val fixture = Fixture()
        fixture.storeToken()
        fixture.cipher.decryptFailure = ProviderException("keystore busy")

        fixture.controller.restoreSession()

        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable),
            fixture.controller.state.value,
        )
        assertNotNull(fixture.preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null))

        assertEquals(OAuthLaunchResult.Ready(AUTHORIZATION_URL), fixture.controller.beginSignIn())
        assertNull(fixture.preferences.getString(ENCRYPTED_ACCESS_TOKEN_KEY, null))
        assertEquals(1, fixture.cipher.destroyKeyCalls)

        fixture.cipher.decryptFailure = null
        assertEquals(OAuthCallbackHandlingResult.ACCEPTED, fixture.controller.handleOAuthCallback(VALID_CALLBACK))
        assertEquals(SIGNED_IN, fixture.controller.state.value)
        assertEquals(TOKEN, fixture.store.read()?.reveal())
    }

    @Test
    fun `logout clear failure does not block the next sign in`() = runBlocking {
        val fixture = Fixture()
        fixture.storeToken()
        fixture.controller.restoreSession()
        fixture.cipher.destroyFailure = ProviderException("keystore busy")

        fixture.controller.logout()

        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable),
            fixture.controller.state.value,
        )
        assertEquals(OAuthLaunchResult.Ready(AUTHORIZATION_URL), fixture.controller.beginSignIn())
        assertEquals(MobileAuthState.AwaitingOAuthCallback, fixture.controller.state.value)
    }

    private class Fixture {
        val preferences: SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }
        val cipher = ScriptedTokenCipher()
        val store = KeystoreAuthTokenStore(preferences, cipher, Dispatchers.Unconfined)
        val controller = MobileAuthController(
            oauthConfiguration = MobileOAuthConfiguration.Configured("9001"),
            tokenStore = store,
            pendingOAuthAttemptStore = SharedPreferencesPendingOAuthAttemptStore(preferences, Dispatchers.Unconfined),
            sessionGateway = ValidSessionGateway(),
            stateGenerator = OAuthStateGenerator { OAUTH_STATE },
            clock = OAuthAttemptClock { NOW_EPOCH_MILLIS },
        )

        suspend fun storeToken() {
            store.write(checkNotNull(AccessToken.parse(TOKEN)))
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

    private class ValidSessionGateway : AuthSessionGateway {
        override fun buildLoginUrl(redirectUri: String, state: String): String =
            "https://app.put.io/authenticate?state=$state"

        override fun setAccessToken(accessToken: AccessToken) = Unit

        override fun clearAccessToken() = Unit

        override suspend fun validateSession(): SessionValidationResult = SessionValidationResult.Valid(ACCOUNT)

        override suspend fun logout(): RemoteLogoutResult = RemoteLogoutResult.Completed
    }

    private companion object {
        const val TOKEN = "token-value"
        const val OAUTH_STATE = "fixed-oauth-state"
        const val AUTHORIZATION_URL = "https://app.put.io/authenticate?state=fixed-oauth-state"
        const val VALID_CALLBACK = "putio://auth?state=$OAUTH_STATE#access_token=$TOKEN&state=$OAUTH_STATE"
        const val NOW_EPOCH_MILLIS = 1_788_000_000_000L
        const val INITIALIZATION_VECTOR_SIZE = 12
        val ACCOUNT = MobileAccount(userId = 42, username = "user", email = "user@example.com")
        val SIGNED_IN = MobileAuthState.SignedIn(account = ACCOUNT, sessionId = MobileAuthSessionId(1L))
    }
}
