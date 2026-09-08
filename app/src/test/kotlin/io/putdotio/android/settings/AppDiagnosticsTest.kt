package io.putdotio.android.settings

import org.junit.Assert.assertEquals
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
}
