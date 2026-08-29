package io.putdotio.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthCallbackParserTest {
    @Test
    fun `valid callback parses fragment token after matching state`() {
        val result = OAuthCallbackParser.parse(
            rawCallbackUri = "putio://auth/callback#access_token=token%2Bvalue&state=expected-state",
            expectedState = "expected-state",
        )

        assertTrue(result is OAuthCallbackParseResult.Success)
        assertEquals("token+value", (result as OAuthCallbackParseResult.Success).accessToken.reveal())
    }

    @Test
    fun `callback rejects every endpoint variation`() {
        val callbacks = listOf(
            "https://auth/callback#access_token=token&state=expected-state",
            "putio://other/callback#access_token=token&state=expected-state",
            "putio://auth/other#access_token=token&state=expected-state",
            "putio://auth/callback?source=browser#access_token=token&state=expected-state",
            "putio://user@auth/callback#access_token=token&state=expected-state",
        )

        callbacks.forEach { callback ->
            assertFailure<OAuthCallbackFailure.UnexpectedEndpoint>(callback)
        }
    }

    @Test
    fun `callback rejects missing duplicate and malformed parameters`() {
        assertFailure<OAuthCallbackFailure.MalformedUri>(null)
        assertFailure<OAuthCallbackFailure.MalformedFragment>("putio://auth/callback")
        assertFailure<OAuthCallbackFailure.MalformedFragment>(
            "putio://auth/callback#access_token=one&access_token=two&state=expected-state",
            validatesExpectedState = true,
        )
        assertFailure<OAuthCallbackFailure.MalformedFragment>(
            "putio://auth/callback#access_token=token&state=expected-state&state=expected-state",
            validatesExpectedState = true,
        )
        assertFailure<OAuthCallbackFailure.MissingAccessToken>(
            "putio://auth/callback#state=expected-state",
            validatesExpectedState = true,
        )
        assertFailure<OAuthCallbackFailure.MissingState>(
            "putio://auth/callback#access_token=token",
        )
    }

    @Test
    fun `callback rejects provider errors and state mismatch`() {
        assertFailure<OAuthCallbackFailure.ProviderRejected>(
            "putio://auth/callback#error=access_denied&state=expected-state",
            validatesExpectedState = true,
        )
        assertFailure<OAuthCallbackFailure.ProviderRejected>(
            "putio://auth/callback#error=&access_token=token&state=expected-state",
            validatesExpectedState = true,
        )
        assertFailure<OAuthCallbackFailure.StateMismatch>(
            "putio://auth/callback#error=access_denied&state=other-state",
        )
        assertFailure<OAuthCallbackFailure.StateMismatch>(
            "putio://auth/callback#access_token=token&state=other-state",
        )
    }

    private inline fun <reified T : OAuthCallbackFailure> assertFailure(
        callback: String?,
        validatesExpectedState: Boolean = false,
    ) {
        val result = OAuthCallbackParser.parse(callback, EXPECTED_STATE)
        assertTrue(result is OAuthCallbackParseResult.Failure)
        assertTrue((result as OAuthCallbackParseResult.Failure).reason is T)
        assertEquals(validatesExpectedState, result.validatesExpectedState)
    }

    private companion object {
        const val EXPECTED_STATE = "expected-state"
    }
}
