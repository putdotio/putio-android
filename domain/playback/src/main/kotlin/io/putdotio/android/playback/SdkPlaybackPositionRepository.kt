package io.putdotio.android.playback

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.errors.PutioException
import kotlinx.coroutines.CancellationException

public class SdkPlaybackPositionRepository internal constructor(
    private val setPosition: suspend (Long, Double) -> Unit,
    private val getPosition: suspend (Long) -> Double = { error("Position reads are not configured") },
    private val loadResumeSetting: suspend () -> Boolean = { error("Setting reads are not configured") },
) {
    public constructor(client: PutioClient) : this(
        setPosition = { fileId, seconds ->
            client.files.setStartFrom(fileId, seconds)
            Unit
        },
        getPosition = client.files::getStartFrom,
        loadResumeSetting = { client.account.getSettings().useStartFrom },
    )

    public suspend fun write(fileId: Long, seconds: Double): PlaybackRepositoryResult<Unit> = request {
        require(fileId > 0L && seconds.isFinite() && seconds > 0.0)
        setPosition(fileId, seconds)
    }

    /** The account's saved position for [fileId], in seconds. */
    public suspend fun read(fileId: Long): PlaybackRepositoryResult<Double> = request {
        require(fileId > 0L)
        getPosition(fileId).also { require(it.isFinite() && it >= 0.0) { "start_from must be finite and nonnegative" } }
    }

    /** The account's `use_start_from`, read now rather than from a settings screen's state. */
    public suspend fun resumeEnabled(): PlaybackRepositoryResult<Boolean> = request { loadResumeSetting() }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> request(block: suspend () -> T): PlaybackRepositoryResult<T> =
        try {
            PlaybackRepositoryResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            PlaybackRepositoryResult.Failure(error.toPlaybackFailure())
        } catch (error: Exception) {
            PlaybackRepositoryResult.Failure(PlaybackFailure.Putio(PutioFailure.Unexpected(error)))
        }
}
