package io.putdotio.android.telemetry

import io.sentry.android.core.BuildConfig as SentryAndroidBuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryNoticesTest {
    @Test
    fun theSentryNativeNoticeFollowsTheSentryRelease() {
        assertEquals(
            "Set telemetryNotices to the sentry-native-ndk Sentry ${SentryAndroidBuildConfig.VERSION_NAME} depends on",
            SentryAndroidBuildConfig.VERSION_NAME,
            NOTICED_SENTRY_ANDROID,
        )
    }
}
