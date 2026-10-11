package io.putdotio.android.telemetry

import java.net.URI
import java.net.URISyntaxException

/** Where and as what an app reports. Built only from a usable DSN; see [of]. */
@ConsistentCopyVisibility
public data class SentryConfiguration internal constructor(
    internal val dsn: String,
    internal val environment: String,
    internal val release: String,
    /** `mobile` or `tv`, sent as the `surface` tag on every event. */
    internal val surface: String,
) {
    public companion object {
        /**
         * put.io's tunnel to Sentry ingest (putio-web `infra/telemetry`). Some networks reset
         * connections to Sentry's own host, and the tunnel forwards only allowlisted project ids.
         */
        internal const val RELAY_HOST: String = "relay.put.io"

        /**
         * Null is the build-time kill switch: an empty or malformed [dsn], which every debug
         * build and every build without `putioSentryDsn` has.
         */
        public fun of(
            dsn: String,
            environment: String,
            release: String,
            surface: String,
        ): SentryConfiguration? =
            relayDsn(dsn)?.let { SentryConfiguration(it, environment, release, surface) }

        /** Rewrites `https://<key>@<host>/<project id>` to post through the relay; anything else is null. */
        internal fun relayDsn(dsn: String): String? {
            val uri = try {
                URI(dsn.trim())
            } catch (_: URISyntaxException) {
                return null
            }
            val key = uri.rawUserInfo?.substringBefore(':')
            val projectId = uri.rawPath?.trimEnd('/')?.substringAfterLast('/')
            val usable = (uri.scheme == "https" || uri.scheme == "http") &&
                !uri.host.isNullOrEmpty() &&
                !key.isNullOrEmpty() && key.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' } &&
                !projectId.isNullOrEmpty() && projectId.all { it in '0'..'9' }
            return if (usable) "https://$key@$RELAY_HOST/$projectId" else null
        }
    }
}
