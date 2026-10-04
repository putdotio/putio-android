package io.putdotio.android

import io.putdotio.sdk.errors.PutioException
import java.util.concurrent.CancellationException

public sealed interface PutioResult<out T> {
    public data class Success<T>(
        val value: T,
    ) : PutioResult<T>

    public data class Failure(
        val failure: PutioFailure,
    ) : PutioResult<Nothing>
}

/**
 * [request] at the SDK boundary: its value, or its failure as [classify] reads it. Cancellation
 * still propagates.
 */
// Kotlin/JVM has no typed throws contract, so the SDK boundary converts
// unknown failures after preserving cancellation.
@Suppress("TooGenericExceptionCaught")
public suspend fun <T> putioRequest(
    classify: (PutioException) -> PutioFailure = PutioException::toPutioFailure,
    request: suspend () -> T,
): PutioResult<T> =
    try {
        PutioResult.Success(request())
    } catch (error: CancellationException) {
        throw error
    } catch (error: PutioException) {
        PutioResult.Failure(classify(error))
    } catch (unexpected: Exception) {
        PutioResult.Failure(PutioFailure.Unexpected(unexpected))
    }
