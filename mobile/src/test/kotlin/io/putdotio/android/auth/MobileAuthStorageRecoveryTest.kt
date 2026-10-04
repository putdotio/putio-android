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
            "missing key" to ScriptedKeystoreTokenStorage.missingKey(),
            "bad GCM tag" to AEADBadTagException("tag mismatch"),
        )
        for ((case, decryptFailure) in cases) {
            val fixture = Fixture()
            fixture.storeToken()
            if (decryptFailure == null) fixture.storage.corruptRecord()
            fixture.storage.decryptFailure = decryptFailure

            fixture.controller.restoreSession()

            assertEquals(case, MobileAuthState.SignedOut(), fixture.controller.state.value)
            assertNull(case, fixture.storage.record)
            assertEquals(case, 1, fixture.storage.destroyKeyCalls)
            assertEquals(case, OAuthLaunchResult.Ready(AUTHORIZATION_URL), fixture.controller.beginSignIn())
        }
    }

    @Test
    fun `transient read failure stays retryable and reset signs in again`() = runBlocking {
        val fixture = Fixture()
        fixture.storeToken()
        fixture.storage.decryptFailure = ProviderException("keystore busy")

        fixture.controller.restoreSession()

        assertEquals(
            MobileAuthState.SignedOut(MobileSignedOutReason.SecureStorageUnavailable),
            fixture.controller.state.value,
        )
        assertNotNull(fixture.storage.record)

        assertEquals(OAuthLaunchResult.Ready(AUTHORIZATION_URL), fixture.controller.beginSignIn())
        assertNull(fixture.storage.record)
        assertEquals(1, fixture.storage.destroyKeyCalls)

        fixture.storage.decryptFailure = null
        assertEquals(OAuthCallbackHandlingResult.ACCEPTED, fixture.controller.handleOAuthCallback(VALID_CALLBACK))
        assertEquals(SIGNED_IN, fixture.controller.state.value)
        assertEquals(TOKEN, fixture.store.read()?.reveal())
    }

    @Test
    fun `logout clear failure does not block the next sign in`() = runBlocking {
        val fixture = Fixture()
        fixture.storeToken()
        fixture.controller.restoreSession()
        fixture.storage.destroyFailure = ProviderException("keystore busy")

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
        val storage = ScriptedKeystoreTokenStorage(preferences)
        val store = storage.store
        val controller = MobileAuthController(
            oauthConfiguration = MobileOAuthConfiguration.Configured("9001"),
            tokenStore = store,
            oauthAttempts = OAuthAttempts(
                store = SharedPreferencesPendingOAuthAttemptStore(preferences, Dispatchers.Unconfined),
                stateGenerator = OAuthStateGenerator { OAUTH_STATE },
                clock = OAuthAttemptClock { NOW_EPOCH_MILLIS },
            ),
            sessionGateway = ValidSessionGateway(),
            tokenRevocations = NoTokenRevocations,
        )

        suspend fun storeToken() {
            store.write(checkNotNull(AccessToken.parse(TOKEN)))
        }
    }

    private class ValidSessionGateway : AuthSessionGateway {
        override fun buildLoginUrl(redirectUri: String, state: String): String =
            "https://app.put.io/authenticate?state=$state"

        override fun setAccessToken(accessToken: AccessToken) = Unit

        override fun clearAccessToken() = Unit

        override suspend fun validateSession(): SessionValidationResult = SessionValidationResult.Valid(ACCOUNT)
    }

    private companion object {
        const val TOKEN = "token-value"
        const val OAUTH_STATE = "fixed-oauth-state"
        const val AUTHORIZATION_URL = "https://app.put.io/authenticate?state=fixed-oauth-state"
        const val VALID_CALLBACK = "putio://auth?state=$OAUTH_STATE#access_token=$TOKEN&state=$OAUTH_STATE"
        const val NOW_EPOCH_MILLIS = 1_788_000_000_000L
        val ACCOUNT = MobileAccount(userId = 42, username = "user", email = "user@example.com")
        val SIGNED_IN = MobileAuthState.SignedIn(account = ACCOUNT, sessionId = MobileAuthSessionId(1L))
    }
}
