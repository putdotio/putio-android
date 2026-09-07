package io.putdotio.android.settings

/**
 * Support-safe facts about this install. Values follow the frontend analytics
 * contract's allowed dimensions: coarse classes and versions only, never
 * device model strings, build fingerprints, identifiers, or account data.
 */
internal data class AppDiagnostics(
    val appVersion: String,
    val versionCode: Int,
    val releaseChannel: ReleaseChannel,
    val buildType: String,
    val runtime: Runtime,
    val runtimeVersion: Int,
    val deviceClass: DeviceClass,
    val player: String,
) {
    enum class ReleaseChannel(val wireValue: String) {
        Stable("stable"),
        Internal("internal"),
        ;

        companion object {
            fun fromFlavor(channel: String): ReleaseChannel =
                if (channel == "nightly") Internal else Stable
        }
    }

    enum class Runtime(val wireValue: String) {
        Android("android"),
        AndroidTv("androidtv"),
    }

    enum class DeviceClass(val wireValue: String) {
        Phone("phone"),
        Tablet("tablet"),
        Tv("tv"),
    }

    /** Plain-text block for pasting into a support ticket; one `key: value` per line. */
    fun supportText(): String =
        listOf(
            "app" to runtime.wireValue,
            "app_version" to appVersion,
            "version_code" to versionCode.toString(),
            "release_channel" to releaseChannel.wireValue,
            "build_type" to buildType,
            "runtime_version" to runtimeVersion.toString(),
            "device_class" to deviceClass.wireValue,
            "player" to player,
        ).joinToString("\n") { (key, value) -> "$key: $value" }
}
