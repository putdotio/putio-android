package io.putdotio.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppDiagnosticsTest {
    private val diagnostics =
        AppDiagnostics(
            appVersion = "0.1.0-mobile",
            versionCode = 1,
            releaseChannel = AppDiagnostics.ReleaseChannel.Internal,
            buildType = "debug",
            runtime = AppDiagnostics.Runtime.Android,
            runtimeVersion = 37,
            deviceClass = AppDiagnostics.DeviceClass.Phone,
            player = "media3/hls",
        )

    @Test
    fun supportTextUsesTheContractKeysOnePerLine() {
        assertEquals(
            """
            app: android
            app_version: 0.1.0-mobile
            version_code: 1
            release_channel: internal
            build_type: debug
            runtime_version: 37
            device_class: phone
            player: media3/hls
            """.trimIndent(),
            diagnostics.supportText(),
        )
    }

    @Test
    fun nightlyMapsToInternalAndEverythingElseToStable() {
        assertEquals(AppDiagnostics.ReleaseChannel.Internal, AppDiagnostics.ReleaseChannel.fromFlavor("nightly"))
        assertEquals(AppDiagnostics.ReleaseChannel.Stable, AppDiagnostics.ReleaseChannel.fromFlavor("production"))
        assertEquals(AppDiagnostics.ReleaseChannel.Stable, AppDiagnostics.ReleaseChannel.fromFlavor(""))
    }

    @Test
    fun supportTextExposesOnlyTheContractKeys() {
        val keys = diagnostics.supportText().lines().map { it.substringBefore(':') }
        assertEquals(
            listOf(
                "app", "app_version", "version_code", "release_channel",
                "build_type", "runtime_version", "device_class", "player",
            ),
            keys,
        )
        for (forbidden in listOf("model", "fingerprint", "serial", "token", "account", "user", "file", "url", "id")) {
            assertFalse("no key may carry $forbidden", keys.any { it.contains(forbidden) })
        }
    }
}
