package io.putdotio.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthCallbackParserTest {
    @Test
    fun `valid callback parses fragment token after matching state`() {
        assertToken("token+value", "putio://auth?state=expected-state#access_token=token%2Bvalue&state=expected-state")
    }

    @Test
    fun `callback preserves literal and percent encoded plus characters`() {
        listOf("token+value", "token%2Bvalue").forEach { encodedToken ->
            assertToken("token+value", "putio://auth#access_token=$encodedToken&state=expected-state")
        }
    }

    @Test
    fun `callback accepts token character boundaries and rejects values outside them`() {
        assertToken("!token~", "putio://auth#access_token=!token~&state=expected-state")

        listOf("token%20value", "token%7Fvalue", "token%C3%A9value").forEach { encodedToken ->
            assertFailure<OAuthCallbackFailure.MissingAccessToken>(
                "putio://auth#access_token=$encodedToken&state=expected-state",
            )
        }
    }

    @Test
    fun `callback rejects every endpoint variation`() {
        val callbacks = listOf(
            "https://auth/callback#access_token=token&state=expected-state",
            "putio://other#access_token=token&state=expected-state",
            "putio://auth/#access_token=token&state=expected-state",
            "putio://auth/callback#access_token=token&state=expected-state",
            "putio://user@auth#access_token=token&state=expected-state",
            "putio://auth:443#access_token=token&state=expected-state",
            "putio:auth#access_token=token&state=expected-state",
        )

        callbacks.forEach { callback ->
            assertFailure<OAuthCallbackFailure.UnexpectedEndpoint>(callback)
        }
    }

    @Test
    fun `callback permits only an optional matching state query`() {
        assertToken("token", "putio://auth#access_token=token&state=expected-state")

        listOf(
            "putio://auth?source=browser#access_token=token&state=expected-state",
            "putio://auth?state=expected-state&state=expected-state#access_token=token&state=expected-state",
        ).forEach { callback ->
            assertFailure<OAuthCallbackFailure.MalformedQuery>(callback)
        }
        assertFailure<OAuthCallbackFailure.MalformedUri>(
            "putio://auth?state=%ZZ#access_token=token&state=expected-state",
        )
        assertFailure<OAuthCallbackFailure.MalformedQuery>(
            "putio://auth?state=other-state#access_token=token&state=expected-state",
        )
        assertFailure<OAuthCallbackFailure.MalformedQuery>(
            "putio://auth?state=expected-state#access_token=token&state=other-state",
        )
    }

    @Test
    fun `callback rejects missing duplicate and malformed parameters`() {
        assertFailure<OAuthCallbackFailure.MalformedUri>(null)
        assertFailure<OAuthCallbackFailure.MalformedFragment>("putio://auth")
        assertFailure<OAuthCallbackFailure.MalformedFragment>(
            "putio://auth#access_token=one&access_token=two&state=expected-state",
        )
        assertFailure<OAuthCallbackFailure.MalformedFragment>(
            "putio://auth#access_token=token&state=expected-state&state=expected-state",
        )
        assertFailure<OAuthCallbackFailure.MissingAccessToken>(
            "putio://auth#state=expected-state",
        )
        assertFailure<OAuthCallbackFailure.MissingState>(
            "putio://auth#access_token=token",
        )
    }

    @Test
    fun `callback rejects provider errors and state mismatch`() {
        assertFailure<OAuthCallbackFailure.ProviderRejected>(
            "putio://auth#error=access_denied&state=expected-state",
        )
        assertFailure<OAuthCallbackFailure.ProviderRejected>(
            "putio://auth#error=&access_token=token&state=expected-state",
        )
        assertFailure<OAuthCallbackFailure.StateMismatch>(
            "putio://auth#error=access_denied&state=other-state",
        )
        assertFailure<OAuthCallbackFailure.StateMismatch>(
            "putio://auth#access_token=token&state=other-state",
        )
    }

    private fun assertToken(expected: String, callback: String) {
        val result = OAuthCallbackParser.parse(callback, EXPECTED_STATE)
        assertEquals(expected, (result as? OAuthCallbackParseResult.Success)?.accessToken?.reveal())
    }

    private inline fun <reified T : OAuthCallbackFailure> assertFailure(
        callback: String?,
    ) {
        val result = OAuthCallbackParser.parse(callback, EXPECTED_STATE)
        assertTrue(result is OAuthCallbackParseResult.Failure)
        assertTrue(
            "Expected ${T::class.simpleName}, got ${(result as OAuthCallbackParseResult.Failure).reason}",
            result.reason is T,
        )
    }

    private companion object {
        const val EXPECTED_STATE = "expected-state"
    }
}
