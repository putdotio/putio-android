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
        fun fromClientId(clientId: String?): MobileOAuthConfiguration = fromClientId(clientId, debugOverride = null)

        /**
         * [debugOverride] is the ignored local.properties client id. The BuildConfig
         * field exists in every variant but only the debug build type carries the
         * local value, and the runtime passes it only when BuildConfig.DEBUG is true.
         * When non-empty it replaces [clientId] and skips the TV-client guard for
         * local harness proof.
         */
        fun fromClientId(
            clientId: String?,
            debugOverride: String?,
        ): MobileOAuthConfiguration {
            val override = debugOverride?.takeUnless { it.isEmpty() }
            val effectiveClientId = override ?: clientId
            val numericClientId = effectiveClientId?.toLongOrNull()
            return when {
                effectiveClientId.isNullOrEmpty() -> Unavailable(OAuthConfigurationProblem.MissingClientId)
                effectiveClientId != effectiveClientId.trim() || numericClientId == null || numericClientId <= 0 ->
                    Unavailable(OAuthConfigurationProblem.InvalidClientId)
                override == null && numericClientId in FORBIDDEN_TV_CLIENT_IDS ->
                    Unavailable(OAuthConfigurationProblem.ForbiddenTvClientId)
                effectiveClientId != numericClientId.toString() -> Unavailable(OAuthConfigurationProblem.InvalidClientId)
                else -> Configured(effectiveClientId)
            }
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
private const val LEGACY_ANDROID_TV_CLIENT_ID = 6221L
private const val LEGACY_FIRE_TV_CLIENT_ID = 6233L
private val FORBIDDEN_TV_CLIENT_IDS = setOf(LEGACY_ANDROID_TV_CLIENT_ID, LEGACY_FIRE_TV_CLIENT_ID)
