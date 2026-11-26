package io.putdotio.android.telemetry

public class SentryConfiguration private constructor(
    internal val dsn: String,
    internal val environment: String,
    internal val surface: String,
) {
    public companion object {
        private const val RELAY_HOST = "relay.put.io"
        private val DSN = Regex("""https://([A-Za-z0-9]+)@[^/]+/([0-9]+)""")

        public fun of(dsn: String, environment: String, surface: String): SentryConfiguration? {
            val (key, project) = DSN.matchEntire(dsn)?.destructured ?: return null
            return SentryConfiguration("https://$key@$RELAY_HOST/$project", environment, surface)
        }
    }
}
