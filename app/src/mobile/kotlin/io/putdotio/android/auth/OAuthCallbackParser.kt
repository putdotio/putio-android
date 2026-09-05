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
        fun parse(value: String?): AccessToken? =
            when {
                value.isNullOrEmpty() || value.length > MAX_ACCESS_TOKEN_LENGTH -> null
                value.any { it.code !in ACCESS_TOKEN_CHARACTER_RANGE } -> null
                else -> AccessToken(value)
            }
    }
}

internal sealed interface OAuthCallbackParseResult {
    data class Success(
        val accessToken: AccessToken,
    ) : OAuthCallbackParseResult

    data class Failure(
        val reason: OAuthCallbackFailure,
    ) : OAuthCallbackParseResult
}

internal sealed interface OAuthCallbackFailure {
    data object MalformedUri : OAuthCallbackFailure

    data object UnexpectedEndpoint : OAuthCallbackFailure

    data object MalformedFragment : OAuthCallbackFailure

    data object MalformedQuery : OAuthCallbackFailure

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
        return when {
            !callbackUri.isExpectedEndpoint() -> failure(OAuthCallbackFailure.UnexpectedEndpoint)
            callbackUri.rawFragment == null -> failure(OAuthCallbackFailure.MalformedFragment)
            else -> parseFragment(
                callbackUri.rawFragment.parseParameters(),
                callbackUri.rawQuery?.parseParameters(),
                expectedState,
            )
        }
    }

    private fun parseFragment(
        fragment: OAuthEncodedParameters,
        query: OAuthEncodedParameters?,
        expectedState: String,
    ): OAuthCallbackParseResult {
        val stateFailure = fragment.validateState(query, expectedState)
        return when {
            stateFailure != null -> failure(stateFailure)
            fragment.malformed -> failure(OAuthCallbackFailure.MalformedFragment)
            query != null && !query.isMatchingStateQuery(expectedState) -> failure(OAuthCallbackFailure.MalformedQuery)
            fragment.contains(OAUTH_ERROR_PARAMETER) -> failure(OAuthCallbackFailure.ProviderRejected)
            else -> AccessToken.parse(fragment.singleValue(OAUTH_ACCESS_TOKEN_PARAMETER))
                ?.let(OAuthCallbackParseResult::Success)
                ?: failure(OAuthCallbackFailure.MissingAccessToken)
        }
    }
}

private fun OAuthEncodedParameters.validateState(
    query: OAuthEncodedParameters?,
    expectedState: String,
): OAuthCallbackFailure? {
    val states = values(OAUTH_STATE_PARAMETER)
    val matches = matchesState(expectedState)
    return when {
        states.isEmpty() -> OAuthCallbackFailure.MissingState
        states.size != 1 -> OAuthCallbackFailure.MalformedFragment
        matches -> null
        query?.matchesState(expectedState) == true -> OAuthCallbackFailure.MalformedQuery
        else -> OAuthCallbackFailure.StateMismatch
    }
}

private fun OAuthEncodedParameters.matchesState(expectedState: String): Boolean =
    values(OAUTH_STATE_PARAMETER).fold(false) { matches, returnedState ->
        returnedState.constantTimeEquals(expectedState) or matches
    }

private fun OAuthEncodedParameters.isMatchingStateQuery(expectedState: String): Boolean =
    !malformed && names == setOf(OAUTH_STATE_PARAMETER) &&
        values(OAUTH_STATE_PARAMETER).size == 1 && matchesState(expectedState)

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
        rawPath.isNullOrEmpty() &&
        rawUserInfo == null &&
        port == NO_PORT

private data class OAuthEncodedParameters(
    private val parameters: Map<String, List<String>>,
    val malformed: Boolean,
) {
    val names: Set<String>
        get() = parameters.keys

    fun values(name: String): List<String> = parameters[name].orEmpty()

    fun singleValue(name: String): String? = values(name).singleOrNull()

    fun contains(name: String): Boolean = parameters.containsKey(name)
}

private fun String.parseParameters(): OAuthEncodedParameters {
    if (isEmpty()) {
        return OAuthEncodedParameters(emptyMap(), malformed = true)
    }

    val parameters = mutableMapOf<String, MutableList<String>>()
    var malformed = false
    for (part in split(PARAMETER_SEPARATOR)) {
        val parameter = part.decodeParameter()
        if (parameter == null) {
            malformed = true
        } else {
            val (name, value) = parameter
            val values = parameters.getOrPut(name) { mutableListOf() }
            values += value
            malformed = malformed || values.size > 1
        }
    }

    return OAuthEncodedParameters(parameters, malformed)
}

private fun String.decodeParameter(): Pair<String, String>? {
    val separatorIndex = indexOf(VALUE_SEPARATOR)
    if (separatorIndex <= 0) return null
    val name = substring(0, separatorIndex).decodeUriComponent()
    val value = substring(separatorIndex + 1).decodeUriComponent()
    return if (name.isNullOrEmpty() || value == null) null else name to value
}

private fun String.decodeUriComponent(): String? =
    try {
        URLDecoder.decode(replace("+", "%2B"), StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

private fun String.constantTimeEquals(other: String): Boolean =
    MessageDigest.isEqual(
        toByteArray(StandardCharsets.UTF_8),
        other.toByteArray(StandardCharsets.UTF_8),
    )

private fun failure(reason: OAuthCallbackFailure): OAuthCallbackParseResult =
    OAuthCallbackParseResult.Failure(reason)

private const val NO_PORT = -1
private const val PARAMETER_SEPARATOR = '&'
private const val VALUE_SEPARATOR = '='
private const val OAUTH_ACCESS_TOKEN_PARAMETER = "access_token"
private const val OAUTH_STATE_PARAMETER = "state"
private const val OAUTH_ERROR_PARAMETER = "error"
private const val MAX_ACCESS_TOKEN_LENGTH = 8_192
private val ACCESS_TOKEN_CHARACTER_RANGE = '!'.code..'~'.code
