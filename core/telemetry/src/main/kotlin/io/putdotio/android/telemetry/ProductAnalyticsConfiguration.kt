package io.putdotio.android.telemetry

public class ProductAnalyticsConfiguration private constructor(
    internal val token: String,
    internal val app: String,
    internal val releaseChannel: String,
    internal val appVersion: String,
) {
    public companion object {
        private val TOKEN = Regex("phc_[A-Za-z0-9]{20,64}")

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
