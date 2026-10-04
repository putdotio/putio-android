package io.putdotio.android.playback

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.errors.PutioException
import kotlinx.coroutines.CancellationException

public class SdkPlaybackPositionRepository internal constructor(
    private val setPosition: suspend (Long, Double) -> Unit,
) {
    public constructor(client: PutioClient) : this({ fileId, seconds ->
        client.files.setStartFrom(fileId, seconds)
        Unit
    })

    @Suppress("TooGenericExceptionCaught")
    public suspend fun write(fileId: Long, seconds: Double): PlaybackRepositoryResult<Unit> =
        try {
            require(fileId > 0L && seconds.isFinite() && seconds > 0.0)
            setPosition(fileId, seconds)
            PlaybackRepositoryResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            PlaybackRepositoryResult.Failure(error.toPlaybackFailure())
        } catch (error: Exception) {
            PlaybackRepositoryResult.Failure(PlaybackFailure.Putio(PutioFailure.Unexpected(error)))
        }
}
