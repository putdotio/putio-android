package io.putdotio.android.settings

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.config.AppConfigUpdate
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioSerializationException
import io.putdotio.sdk.errors.PutioTransportException
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal sealed interface AndroidAppConfigRepositoryResult<out T> {
    data class Success<T>(
        val value: T,
    ) : AndroidAppConfigRepositoryResult<T>

    data class Failure(
        val failure: AndroidAppConfigFailure,
    ) : AndroidAppConfigRepositoryResult<Nothing>
}

internal sealed interface AndroidAppConfigFailure {
    val cause: Throwable

    data class AuthenticationRequired(override val cause: PutioException) : AndroidAppConfigFailure
    data class AccessDenied(override val cause: PutioException) : AndroidAppConfigFailure
    data class RateLimited(override val cause: PutioException) : AndroidAppConfigFailure
    data class ServerUnavailable(val statusCode: Int, override val cause: PutioException) : AndroidAppConfigFailure
    data class ApiRejected(
        val statusCode: Int,
        val errorType: String?,
        override val cause: PutioException,
    ) : AndroidAppConfigFailure
    data class NetworkUnavailable(override val cause: PutioException) : AndroidAppConfigFailure
    data class InvalidResponse(override val cause: PutioException) : AndroidAppConfigFailure
    data class Misconfigured(override val cause: PutioException) : AndroidAppConfigFailure
    data class Unexpected(override val cause: Throwable) : AndroidAppConfigFailure
}

internal interface AndroidAppConfigRepository {
    suspend fun load(): AndroidAppConfigRepositoryResult<AndroidAppConfigPreferences>

    suspend fun save(change: AndroidAppConfigChange): AndroidAppConfigRepositoryResult<Unit>
}

internal class SdkAndroidAppConfigRepository(
    private val getConfig: suspend () -> AppConfig,
    private val saveConfig: suspend (AppConfigUpdate) -> Unit,
) : AndroidAppConfigRepository {
    constructor(client: PutioClient) : this(
        getConfig = client.appConfig::get,
        saveConfig = { update -> client.appConfig.save(update) },
    )

    override suspend fun load(): AndroidAppConfigRepositoryResult<AndroidAppConfigPreferences> =
        request { getConfig().toAndroidPreferences() }

    override suspend fun save(change: AndroidAppConfigChange): AndroidAppConfigRepositoryResult<Unit> =
        request { saveConfig(change.toUpdate()) }

    // This SDK boundary converts unexpected implementation failures into the app's stable failure taxonomy.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> request(block: suspend () -> T): AndroidAppConfigRepositoryResult<T> =
        try {
            AndroidAppConfigRepositoryResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            AndroidAppConfigRepositoryResult.Failure(error.toAndroidAppConfigFailure())
        } catch (unexpected: Exception) {
            AndroidAppConfigRepositoryResult.Failure(AndroidAppConfigFailure.Unexpected(unexpected))
        }
}

internal suspend fun AndroidAppConfigRepository.execute(effect: AndroidAppConfigEffect): AndroidAppConfigEvent =
    when (effect) {
        is AndroidAppConfigEffect.Load ->
            when (val result = load()) {
                is AndroidAppConfigRepositoryResult.Success ->
                    AndroidAppConfigEvent.LoadSucceeded(effect.requestId, result.value)
                is AndroidAppConfigRepositoryResult.Failure ->
                    AndroidAppConfigEvent.LoadFailed(effect.requestId, result.failure)
            }
        is AndroidAppConfigEffect.Save ->
            when (val result = save(effect.change)) {
                is AndroidAppConfigRepositoryResult.Success ->
                    AndroidAppConfigEvent.SaveSucceeded(effect.requestId)
                is AndroidAppConfigRepositoryResult.Failure ->
                    AndroidAppConfigEvent.SaveFailed(effect.requestId, result.failure)
            }
        is AndroidAppConfigEffect.Refresh ->
            when (val result = load()) {
                is AndroidAppConfigRepositoryResult.Success ->
                    AndroidAppConfigEvent.RefreshSucceeded(effect.requestId, result.value)
                is AndroidAppConfigRepositoryResult.Failure ->
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

internal fun AndroidAppConfigChange.toUpdate(): AppConfigUpdate =
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

internal const val VIDEO_PLAYBACK_TYPE_KEY = "video_playback_type"
internal const val AUTOPLAY_NEXT_VIDEO_KEY = "autoplay_next_video"
private const val VIDEO_PLAYBACK_TYPE_HLS = "hls"
private const val VIDEO_PLAYBACK_TYPE_MP4 = "mp4"

private fun PutioException.toAndroidAppConfigFailure(): AndroidAppConfigFailure {
    if (findPutioApiException()?.errorType == INVALID_SCOPE_ERROR_TYPE) {
        return AndroidAppConfigFailure.AccessDenied(this)
    }
    var current: PutioException = this
    var reasonFailure: AndroidAppConfigFailure? = null
    val visited = mutableSetOf<PutioException>()
    while (current is PutioOperationException && visited.add(current) && reasonFailure == null) {
        reasonFailure = current.reasonFailure(context = this)
        current = current.underlyingError
    }
    return reasonFailure ?: current.leafFailure(context = this)
}

private fun Throwable.findPutioApiException(): PutioApiException? {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is PutioApiException) return current
        current = if (current is PutioOperationException) current.underlyingError else current.cause
    }
    return null
}

private fun PutioOperationException.reasonFailure(
    context: PutioException,
): AndroidAppConfigFailure? =
    when ((reason as? PutioOperationErrorReason.StatusCode)?.statusCode) {
        HTTP_UNAUTHORIZED -> AndroidAppConfigFailure.AuthenticationRequired(context)
        HTTP_FORBIDDEN -> AndroidAppConfigFailure.AccessDenied(context)
        else -> null
    }

private fun PutioException.leafFailure(context: PutioException): AndroidAppConfigFailure =
    when (this) {
        is PutioApiException ->
            when (statusCode) {
                HTTP_UNAUTHORIZED -> AndroidAppConfigFailure.AuthenticationRequired(context)
                HTTP_FORBIDDEN -> AndroidAppConfigFailure.AccessDenied(context)
                HTTP_TOO_MANY_REQUESTS -> AndroidAppConfigFailure.RateLimited(context)
                in HTTP_SERVER_ERROR_RANGE -> AndroidAppConfigFailure.ServerUnavailable(statusCode, context)
                else -> AndroidAppConfigFailure.ApiRejected(statusCode, errorType, context)
            }
        is PutioTransportException -> AndroidAppConfigFailure.NetworkUnavailable(context)
        is PutioSerializationException -> AndroidAppConfigFailure.InvalidResponse(context)
        is PutioConfigurationException -> AndroidAppConfigFailure.Misconfigured(context)
        is PutioOperationException -> AndroidAppConfigFailure.Unexpected(context)
    }

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val INVALID_SCOPE_ERROR_TYPE = "invalid_scope"
private val HTTP_SERVER_ERROR_RANGE = HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END
private const val HTTP_SERVER_ERROR_START = 500
private const val HTTP_SERVER_ERROR_END = 599
