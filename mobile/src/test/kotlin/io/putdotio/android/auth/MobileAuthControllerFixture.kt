package io.putdotio.android.auth

import io.putdotio.android.auth.AuthControllerTestValues.ACCOUNT
import io.putdotio.android.auth.AuthControllerTestValues.NOW_EPOCH_MILLIS
import io.putdotio.android.auth.AuthControllerTestValues.OAUTH_STATE
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import java.util.ArrayDeque

internal class AuthControllerFixture(
    storedToken: String? = null,
    validationResults: List<SessionValidationResult> = listOf(SessionValidationResult.Valid(ACCOUNT)),
    configuration: MobileOAuthConfiguration = MobileOAuthConfiguration.Configured("9001"),
    stateGenerator: OAuthStateGenerator = OAuthStateGenerator { OAUTH_STATE },
    val pendingAttemptStore: FakePendingOAuthAttemptStore = FakePendingOAuthAttemptStore(),
    revocations: RevocationScript = RevocationScript(),
) {
    val clock = FakeOAuthAttemptClock(NOW_EPOCH_MILLIS)
    val tokenStore = FakeAuthTokenStore(storedToken?.let { checkNotNull(AccessToken.parse(it)) })
    val gateway = FakeAuthSessionGateway(validationResults)
    val revocationStore = InMemoryAuthTokenStore(revocations.pending?.let { checkNotNull(AccessToken.parse(it)) })
    val revoker = ScriptedTokenRevoker(*revocations.results.toTypedArray())
    val revocationScope = TestScope(UnconfinedTestDispatcher())
    var accountLocalStateClears = 0
    val controller = MobileAuthController(
        oauthConfiguration = configuration,
        tokenStore = tokenStore,
        oauthAttempts = OAuthAttempts(pendingAttemptStore, stateGenerator, clock),
        sessionGateway = gateway,
        tokenRevocations = PendingTokenRevocations(revocationStore, tokenStore, revoker, revocationScope),
        clearAccountLocalState = { accountLocalStateClears += 1 },
    )
}

/** A revocation recorded by an earlier process, and put.io's answers to each revocation attempt. */
internal class RevocationScript(
    val pending: String? = null,
    val results: List<TokenRevocationResult> = emptyList(),
)

internal class FakePendingOAuthAttemptStore(
    var attempt: PendingOAuthAttempt? = null,
) : PendingOAuthAttemptStore {
    var failRead = false
    var failClear = false
    var beforeRead: (suspend () -> Unit)? = null

    override suspend fun read(): PendingOAuthAttempt? {
        beforeRead?.invoke()
        if (failRead) {
            throw PendingOAuthAttemptStorageException("read")
        }
        return attempt
    }

    override suspend fun write(attempt: PendingOAuthAttempt) {
        this.attempt = attempt
    }

    override suspend fun clear() {
        if (failClear) {
            throw PendingOAuthAttemptStorageException("clear")
        }
        attempt = null
    }
}

internal class FakeOAuthAttemptClock(
    var nowEpochMillis: Long,
) : OAuthAttemptClock {
    override fun nowEpochMillis(): Long = nowEpochMillis
}

internal class FakeAuthTokenStore(
    var token: AccessToken?,
) : AuthTokenStore {
    var beforeClear: (suspend () -> Unit)? = null
    var beforeRead: (suspend () -> Unit)? = null
    var failRead = false

    override suspend fun read(): AccessToken? {
        beforeRead?.invoke()
        if (failRead) {
            throw AuthTokenStorageException("read")
        }
        return token
    }

    override suspend fun write(accessToken: AccessToken) {
        token = accessToken
    }

    override suspend fun clear() {
        beforeClear?.invoke()
        token = null
    }
}

internal class FakeAuthSessionGateway(
    validationResults: List<SessionValidationResult>,
) : AuthSessionGateway {
    val calls = mutableListOf<String>()
    val results = ArrayDeque(validationResults)
    var configuredToken: AccessToken? = null
    var clearCount = 0
    var validationFailure: Throwable? = null

    override fun buildLoginUrl(redirectUri: String, state: String): String {
        calls += "build-url"
        assertEquals(MOBILE_OAUTH_REDIRECT_URI, redirectUri)
        return "https://app.put.io/authenticate?state=$state"
    }

    override fun setAccessToken(accessToken: AccessToken) {
        calls += "set-token"
        configuredToken = accessToken
    }

    override fun clearAccessToken() {
        calls += "clear-token"
        clearCount += 1
        configuredToken = null
    }

    override suspend fun validateSession(): SessionValidationResult {
        calls += "validate"
        validationFailure?.let { throw it }
        return results.removeFirst()
    }
}

internal object AuthControllerTestValues {
    const val TOKEN = "token-value"
    const val OLD_TOKEN = "old-token-value"
    const val OAUTH_STATE = "fixed-oauth-state"
    const val AUTHORIZATION_URL = "https://app.put.io/authenticate?state=fixed-oauth-state"
    const val VALID_CALLBACK = "putio://auth?state=$OAUTH_STATE#access_token=$TOKEN&state=$OAUTH_STATE"
    const val NOW_EPOCH_MILLIS = 1_788_000_000_000L
    val ACCOUNT = MobileAccount(userId = 42, username = "user", email = "user@example.com")
    val SIGNED_IN = MobileAuthState.SignedIn(
        account = ACCOUNT,
        sessionId = MobileAuthSessionId(1L),
    )
}
