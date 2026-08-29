package io.putdotio.android.auth

import java.security.SecureRandom
import java.util.Base64

internal sealed interface MobileOAuthConfiguration {
    val redirectUri: String

    data class Configured(
        val clientId: String,
        override val redirectUri: String = MOBILE_OAUTH_REDIRECT_URI,
    ) : MobileOAuthConfiguration

    data class Unavailable(
        val problem: OAuthConfigurationProblem,
        override val redirectUri: String = MOBILE_OAUTH_REDIRECT_URI,
    ) : MobileOAuthConfiguration

    companion object {
        fun fromClientId(clientId: String?): MobileOAuthConfiguration {
            if (clientId.isNullOrEmpty()) {
                return Unavailable(OAuthConfigurationProblem.MissingClientId)
            }
            val numericClientId = clientId.toLongOrNull()
            if (clientId != clientId.trim() || numericClientId == null || numericClientId <= 0) {
                return Unavailable(OAuthConfigurationProblem.InvalidClientId)
            }
            if (numericClientId in FORBIDDEN_TV_CLIENT_IDS) {
                return Unavailable(OAuthConfigurationProblem.ForbiddenTvClientId)
            }
            if (clientId != numericClientId.toString()) {
                return Unavailable(OAuthConfigurationProblem.InvalidClientId)
            }

            return Configured(clientId)
        }
    }
}

internal sealed interface OAuthConfigurationProblem {
    data object MissingClientId : OAuthConfigurationProblem

    data object InvalidClientId : OAuthConfigurationProblem

    data object ForbiddenTvClientId : OAuthConfigurationProblem
}

internal fun interface OAuthStateGenerator {
    fun generate(): String
}

internal class SecureOAuthStateGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : OAuthStateGenerator {
    override fun generate(): String {
        val bytes = ByteArray(OAUTH_STATE_BYTE_COUNT)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

sealed interface OAuthLaunchResult {
    data class Ready(
        val authorizationUrl: String,
    ) : OAuthLaunchResult

    data object NotConfigured : OAuthLaunchResult

    data object StorageUnavailable : OAuthLaunchResult

    data object NotAllowed : OAuthLaunchResult
}

internal const val MOBILE_OAUTH_SCHEME = "putio"
internal const val MOBILE_OAUTH_HOST = "auth"
internal const val MOBILE_OAUTH_REDIRECT_URI = "$MOBILE_OAUTH_SCHEME://$MOBILE_OAUTH_HOST"
internal const val MOBILE_OAUTH_CLIENT_NAME = "put.io Android"

private const val OAUTH_STATE_BYTE_COUNT = 32
private val FORBIDDEN_TV_CLIENT_IDS = setOf(6221L, 6233L)
