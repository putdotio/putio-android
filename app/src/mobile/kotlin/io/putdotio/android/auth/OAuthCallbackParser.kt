package io.putdotio.android.auth

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal class AccessToken private constructor(
    private val value: String,
) {
    fun reveal(): String = value

    override fun toString(): String = "AccessToken([REDACTED])"

    companion object {
        fun parse(value: String?): AccessToken? {
            if (value.isNullOrEmpty() || value.length > MAX_ACCESS_TOKEN_LENGTH) {
                return null
            }
            if (value.any { it.code !in ACCESS_TOKEN_CHARACTER_RANGE }) {
                return null
            }

            return AccessToken(value)
        }
    }
}

internal sealed interface OAuthCallbackParseResult {
    val validatesExpectedState: Boolean

    data class Success(
        val accessToken: AccessToken,
    ) : OAuthCallbackParseResult {
        override val validatesExpectedState: Boolean = true
    }

    data class Failure(
        val reason: OAuthCallbackFailure,
        override val validatesExpectedState: Boolean = false,
    ) : OAuthCallbackParseResult
}

internal sealed interface OAuthCallbackFailure {
    data object MalformedUri : OAuthCallbackFailure

    data object UnexpectedEndpoint : OAuthCallbackFailure

    data object MalformedFragment : OAuthCallbackFailure

    data object ProviderRejected : OAuthCallbackFailure

    data object MissingAccessToken : OAuthCallbackFailure

    data object MissingState : OAuthCallbackFailure

    data object StateMismatch : OAuthCallbackFailure
}

internal object OAuthCallbackParser {
    fun parse(
        rawCallbackUri: String?,
        expectedState: String,
    ): OAuthCallbackParseResult {
        val callbackUri = rawCallbackUri?.toUriOrNull() ?: return failure(OAuthCallbackFailure.MalformedUri)
        if (!callbackUri.isExpectedEndpoint()) {
            return failure(OAuthCallbackFailure.UnexpectedEndpoint)
        }

        val parameters = callbackUri.rawFragment?.parseFragment()
            ?: return failure(OAuthCallbackFailure.MalformedFragment)
        val returnedStates = parameters.values(OAUTH_STATE_PARAMETER)
        val validatesExpectedState = returnedStates.fold(false) { matches, returnedState ->
            returnedState.constantTimeEquals(expectedState) or matches
        }
        if (returnedStates.size != 1) {
            return failure(
                if (returnedStates.isEmpty()) {
                    OAuthCallbackFailure.MissingState
                } else {
                    OAuthCallbackFailure.MalformedFragment
                },
                validatesExpectedState = validatesExpectedState,
            )
        }
        val returnedState = returnedStates.single()
        if (!validatesExpectedState) {
            return failure(OAuthCallbackFailure.StateMismatch)
        }
        if (parameters.malformed) {
            return failure(OAuthCallbackFailure.MalformedFragment, validatesExpectedState = true)
        }
        if (parameters.contains(OAUTH_ERROR_PARAMETER)) {
            return failure(OAuthCallbackFailure.ProviderRejected, validatesExpectedState = true)
        }

        val accessToken = AccessToken.parse(parameters.singleValue(OAUTH_ACCESS_TOKEN_PARAMETER))
            ?: return failure(OAuthCallbackFailure.MissingAccessToken, validatesExpectedState = true)
        return OAuthCallbackParseResult.Success(accessToken)
    }
}

private fun String.toUriOrNull(): URI? =
    try {
        URI(this)
    } catch (_: URISyntaxException) {
        null
    }

private fun URI.isExpectedEndpoint(): Boolean =
    !isOpaque &&
        scheme.equals(MOBILE_OAUTH_SCHEME, ignoreCase = true) &&
        host.equals(MOBILE_OAUTH_HOST, ignoreCase = true) &&
        rawPath == MOBILE_OAUTH_PATH &&
        rawUserInfo == null &&
        port == NO_PORT &&
        rawQuery == null

private data class OAuthFragmentParameters(
    private val parameters: Map<String, List<String>>,
    val malformed: Boolean,
) {
    fun values(name: String): List<String> = parameters[name].orEmpty()

    fun singleValue(name: String): String? = values(name).singleOrNull()

    fun contains(name: String): Boolean = parameters.containsKey(name)
}

private fun String.parseFragment(): OAuthFragmentParameters {
    if (isEmpty()) {
        return OAuthFragmentParameters(emptyMap(), malformed = true)
    }

    val parameters = mutableMapOf<String, MutableList<String>>()
    var malformed = false
    for (part in split(FRAGMENT_PARAMETER_SEPARATOR)) {
        val separatorIndex = part.indexOf(FRAGMENT_VALUE_SEPARATOR)
        if (separatorIndex <= 0) {
            malformed = true
            continue
        }

        val name = part.substring(0, separatorIndex).decodeFormComponent()
        val value = part.substring(separatorIndex + 1).decodeFormComponent()
        if (name.isNullOrEmpty() || value == null) {
            malformed = true
            continue
        }

        val values = parameters.getOrPut(name) { mutableListOf() }
        values += value
        malformed = malformed || values.size > 1
    }

    return OAuthFragmentParameters(parameters, malformed)
}

private fun String.decodeFormComponent(): String? =
    try {
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

private fun String.constantTimeEquals(other: String): Boolean =
    MessageDigest.isEqual(
        toByteArray(StandardCharsets.UTF_8),
        other.toByteArray(StandardCharsets.UTF_8),
    )

private fun failure(
    reason: OAuthCallbackFailure,
    validatesExpectedState: Boolean = false,
): OAuthCallbackParseResult =
    OAuthCallbackParseResult.Failure(reason, validatesExpectedState)

private const val MOBILE_OAUTH_SCHEME = "putio"
private const val MOBILE_OAUTH_HOST = "auth"
private const val MOBILE_OAUTH_PATH = "/callback"
private const val NO_PORT = -1
private const val FRAGMENT_PARAMETER_SEPARATOR = '&'
private const val FRAGMENT_VALUE_SEPARATOR = '='
private const val OAUTH_ACCESS_TOKEN_PARAMETER = "access_token"
private const val OAUTH_STATE_PARAMETER = "state"
private const val OAUTH_ERROR_PARAMETER = "error"
private const val MAX_ACCESS_TOKEN_LENGTH = 8_192
private val ACCESS_TOKEN_CHARACTER_RANGE = 0x21..0x7e
