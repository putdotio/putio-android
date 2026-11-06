package io.putdotio.android.telemetry

import io.putdotio.android.OpenSourceNotice

internal const val NOTICED_SENTRY_ANDROID = "8.62.0"

public val telemetryNotices: List<OpenSourceNotice> = listOf(
    OpenSourceNotice(
        name = "sentry-native",
        version = "0.17.4",
        license = "MIT",
        copyright = "Copyright © 2019 Sentry and individual contributors",
        sourceUrl = "https://github.com/getsentry/sentry-native/tree/0.17.4",
        licenseUrl = "https://github.com/getsentry/sentry-native/blob/0.17.4/LICENSE",
    ),
)
