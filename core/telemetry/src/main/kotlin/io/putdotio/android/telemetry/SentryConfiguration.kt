package io.putdotio.android.telemetry

public class SentryConfiguration private constructor(
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
        private const val RELAY_HOST = "relay.put.io"

        // The build accepts only an empty DSN or this shape.
        private val DSN = Regex("""https://([A-Za-z0-9]+)@[^/]+/([0-9]+)""")

        /** Null for the empty DSN every debug build and every build without `putioSentryDsn` has. */
        public fun of(
            dsn: String,
            environment: String,
            release: String,
            surface: String,
        ): SentryConfiguration? {
            val (key, project) = DSN.matchEntire(dsn)?.destructured ?: return null
            return SentryConfiguration("https://$key@$RELAY_HOST/$project", environment, release, surface)
        }
    }
}
