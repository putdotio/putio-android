package io.putdotio.android.settings

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.findPutioApiException
import io.putdotio.android.putioRequest
import io.putdotio.android.toPutioFailure
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.config.AppConfigUpdate
import io.putdotio.sdk.errors.PutioException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

public interface AndroidAppConfigRepository {
    public suspend fun load(): PutioResult<AndroidAppConfigPreferences>

    public suspend fun save(change: AndroidAppConfigChange): PutioResult<Unit>
}

public class SdkAndroidAppConfigRepository(
    private val getConfig: suspend () -> AppConfig,
    private val saveConfig: suspend (AppConfigUpdate) -> Unit,
) : AndroidAppConfigRepository {
    public constructor(client: PutioClient) : this(
        getConfig = client.appConfig::get,
        saveConfig = { update -> client.appConfig.save(update) },
    )

    override suspend fun load(): PutioResult<AndroidAppConfigPreferences> =
        putioRequest(PutioException::toSettingsFailure) { getConfig().toAndroidPreferences() }

    override suspend fun save(change: AndroidAppConfigChange): PutioResult<Unit> =
        putioRequest(PutioException::toSettingsFailure) { saveConfig(change.toUpdate()) }
}

internal suspend fun AndroidAppConfigRepository.execute(effect: AndroidAppConfigEffect): AndroidAppConfigEvent =
    when (effect) {
        is AndroidAppConfigEffect.Load ->
            when (val result = load()) {
                is PutioResult.Success ->
                    AndroidAppConfigEvent.LoadSucceeded(effect.requestId, result.value)
                is PutioResult.Failure ->
                    AndroidAppConfigEvent.LoadFailed(effect.requestId, result.failure)
            }
        is AndroidAppConfigEffect.Save ->
            when (val result = save(effect.change)) {
                is PutioResult.Success ->
                    AndroidAppConfigEvent.SaveSucceeded(effect.requestId)
                is PutioResult.Failure ->
                    AndroidAppConfigEvent.SaveFailed(effect.requestId, result.failure)
            }
        is AndroidAppConfigEffect.Refresh ->
            when (val result = load()) {
                is PutioResult.Success ->
                    AndroidAppConfigEvent.RefreshSucceeded(effect.requestId, result.value)
                is PutioResult.Failure ->
                    AndroidAppConfigEvent.RefreshFailed(effect.requestId, result.failure)
            }
    }

internal fun AppConfig.toAndroidPreferences(): AndroidAppConfigPreferences =
    AndroidAppConfigPreferences(
        videoPlaybackType =
            when ((this[VIDEO_PLAYBACK_TYPE_KEY] as? JsonPrimitive)?.contentOrNull) {
                VIDEO_PLAYBACK_TYPE_MP4 -> VideoPlaybackType.Mp4
                VIDEO_PLAYBACK_TYPE_HLS -> VideoPlaybackType.Hls
                else -> VideoPlaybackType.Hls
            },
        autoplayNextVideo =
            (this[AUTOPLAY_NEXT_VIDEO_KEY] as? JsonPrimitive)
                ?.takeUnless(JsonPrimitive::isString)
                ?.booleanOrNull
                ?: false,
    )

public fun AndroidAppConfigChange.toUpdate(): AppConfigUpdate =
    when (this) {
        is AndroidAppConfigChange.VideoPlayback ->
            AppConfigUpdate(
                key = VIDEO_PLAYBACK_TYPE_KEY,
                value = JsonPrimitive(value.wireValue),
            )
        is AndroidAppConfigChange.AutoplayNextVideo ->
            AppConfigUpdate(
                key = AUTOPLAY_NEXT_VIDEO_KEY,
                value = JsonPrimitive(enabled),
            )
    }

public const val VIDEO_PLAYBACK_TYPE_KEY: String = "video_playback_type"
internal const val AUTOPLAY_NEXT_VIDEO_KEY = "autoplay_next_video"
private const val VIDEO_PLAYBACK_TYPE_HLS = "hls"
private const val VIDEO_PLAYBACK_TYPE_MP4 = "mp4"

/**
 * [toPutioFailure], except that an `invalid_scope` refusal anywhere in the chain is AccessDenied
 * rather than a 401 that expires the session.
 */
public fun PutioException.toSettingsFailure(): PutioFailure =
    if (findPutioApiException()?.errorType == INVALID_SCOPE_ERROR_TYPE) {
        PutioFailure.AccessDenied(this)
    } else {
        toPutioFailure()
    }

private const val INVALID_SCOPE_ERROR_TYPE = "invalid_scope"
