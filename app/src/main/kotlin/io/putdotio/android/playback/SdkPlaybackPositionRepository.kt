package io.putdotio.android.playback

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.errors.PutioException
import kotlinx.coroutines.CancellationException

internal class SdkPlaybackPositionRepository internal constructor(
    private val setPosition: suspend (Long, Double) -> Unit,
) {
    constructor(client: PutioClient) : this({ fileId, seconds ->
        client.files.setStartFrom(fileId, seconds)
        Unit
    })

    @Suppress("TooGenericExceptionCaught")
    suspend fun write(fileId: Long, seconds: Double): PlaybackRepositoryResult<Unit> =
        try {
            require(fileId > 0L && seconds.isFinite() && seconds > 0.0)
            setPosition(fileId, seconds)
            PlaybackRepositoryResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            PlaybackRepositoryResult.Failure(error.toPlaybackFailure())
        } catch (error: Exception) {
            PlaybackRepositoryResult.Failure(PlaybackFailure.Unexpected(error))
        }
}
