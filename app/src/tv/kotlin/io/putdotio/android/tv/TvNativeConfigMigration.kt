package io.putdotio.android.tv

import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.SdkAndroidAppConfigRepository
import io.putdotio.android.settings.VIDEO_PLAYBACK_TYPE_KEY
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.settings.toUpdate
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.config.AppConfigUpdate
import io.putdotio.sdk.errors.PutioException
import kotlinx.serialization.json.JsonPrimitive

/** The TV app config repository: the shared one, reading tv-native's playback type forward. */
internal fun tvAppConfigRepository(client: PutioClient): SdkAndroidAppConfigRepository =
    tvAppConfigRepository(getConfig = client.appConfig::get, saveConfig = { update -> client.appConfig.save(update) })

internal fun tvAppConfigRepository(
    getConfig: suspend () -> AppConfig,
    saveConfig: suspend (AppConfigUpdate) -> Unit,
): SdkAndroidAppConfigRepository =
    SdkAndroidAppConfigRepository(
        getConfig = { getConfig().withTvNativePlaybackType(saveConfig) },
        saveConfig = saveConfig,
    )

/**
 * `/config` is per user and OAuth app, and this app links as tv-native's
 * clients, so a tv-native viewer's `playbackType` (`hls` or `mp4`) is already
 * there. With no `video_playback_type` yet, it is written as that key and read
 * as if it had been. An existing `video_playback_type`, even one the app cannot
 * parse, always wins, so the write happens at most until it lands. A failed
 * write still applies the value to this read; the next read writes again.
 */
internal suspend fun AppConfig.withTvNativePlaybackType(save: suspend (AppConfigUpdate) -> Unit): AppConfig {
    if (values.containsKey(VIDEO_PLAYBACK_TYPE_KEY)) return this
    val legacy = (this[TV_NATIVE_PLAYBACK_TYPE_KEY] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    val playbackType = VideoPlaybackType.entries.firstOrNull { it.wireValue == legacy } ?: return this
    val update = AndroidAppConfigChange.VideoPlayback(playbackType).toUpdate()
    try {
        save(update)
    } catch (_: PutioException) {
        // The key stays absent, so the next read writes again.
    }
    return AppConfig(values + (update.key to update.value))
}

internal const val TV_NATIVE_PLAYBACK_TYPE_KEY = "playbackType"
