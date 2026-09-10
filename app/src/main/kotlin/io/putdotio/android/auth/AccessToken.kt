package io.putdotio.android.auth

/** An OAuth access token. Never logged or rendered; [reveal] only for the SDK and secure storage. */
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

private const val MAX_ACCESS_TOKEN_LENGTH = 8_192
private val ACCESS_TOKEN_CHARACTER_RANGE = '!'.code..'~'.code
