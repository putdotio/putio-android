package io.putdotio.android.telemetry

public class ProductAnalyticsConfiguration private constructor(
    internal val token: String,
    /** The contract's `app` and `runtime`: `android` or `androidtv`. */
    internal val app: String,
    internal val releaseChannel: String,
    /** `<version name>+<version code>`, such as `3.0.1+3000100`. */
    internal val appVersion: String,
) {
    public companion object {
        // PostHog's public project token, which only names the project.
        private val TOKEN = Regex("phc_[A-Za-z0-9]{20,64}")

        /**
         * Null, which keeps PostHog off, for the empty token of debug builds. [tv] reports
         * `androidtv`; the `nightly` flavor reports the `beta` release channel.
         */
        public fun of(
            token: String,
            tv: Boolean,
            flavor: String,
            versionName: String,
            versionCode: Int,
        ): ProductAnalyticsConfiguration? =
            token.takeIf(TOKEN::matches)?.let {
                ProductAnalyticsConfiguration(
                    token = it,
                    app = if (tv) "androidtv" else "android",
                    releaseChannel = if (flavor == "nightly") "beta" else "stable",
                    appVersion = "$versionName+$versionCode",
                )
            }
    }
}
