package io.putdotio.android.history

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

public class HistoryController(
    private val repository: HistoryRepository,
    historyEnabled: Boolean,
    parentScope: CoroutineScope,
    private val clock: TimeSource = TimeSource.Monotonic,
) : Closeable {
    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val initial = HistoryReducer.start(historyEnabled)
    private val mutableState = MutableStateFlow(initial.state)
    private val navigationChannel = Channel<HistoryEffect.NavigateToFile>(Channel.BUFFERED)
    private var closed = false
    private var lastRead = clock.markNow()

    public val state: StateFlow<HistoryState> = mutableState.asStateFlow()
    public val navigation: Flow<HistoryEffect.NavigateToFile> = navigationChannel.receiveAsFlow()

    init { initial.effect?.let(::launchEffect) }

    public fun dispatch(event: HistoryEvent): Boolean {
        val transition = synchronized(lock) {
            if (closed || !controllerJob.isActive || throttles(event)) return false
            HistoryReducer.reduce(mutableState.value, event).also {
                mutableState.value = it.state
                if (it.effect.readsFirstPage()) lastRead = clock.markNow()
            }
        }
        transition.effect?.let(::launchEffect)
        return transition.consumed
    }

    private fun throttles(event: HistoryEvent): Boolean =
        event == HistoryEvent.Refresh &&
            mutableState.value.content !is HistoryContent.Failed &&
            lastRead.elapsedNow() < REFRESH_AFTER

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        navigationChannel.close()
        scope.cancel()
    }

    private fun launchEffect(effect: HistoryEffect) {
        if (effect is HistoryEffect.NavigateToFile) {
            navigationChannel.trySend(effect)
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val event = when (effect) {
                is HistoryEffect.Load -> safely { repository.load(effect.before) }.toLoadEvent(effect.requestId)
                is HistoryEffect.Clear -> safely { repository.clear() }.toClearEvent(effect.requestId)
                is HistoryEffect.NavigateToFile -> error("Navigation is handled synchronously")
            }
            dispatch(event)
        }
    }
}

private val REFRESH_AFTER = 1.minutes

private fun HistoryEffect?.readsFirstPage(): Boolean = this is HistoryEffect.Load && before == null

// Injected repositories must preserve the same cancellation contract as the SDK adapter.
@Suppress("TooGenericExceptionCaught")
private suspend fun <T> safely(block: suspend () -> PutioResult<T>): PutioResult<T> =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unexpected: Exception) {
        PutioResult.Failure(PutioFailure.Unexpected(unexpected))
    }

private fun PutioResult<HistoryPage>.toLoadEvent(requestId: HistoryRequestId): HistoryEvent =
    when (this) {
        is PutioResult.Success -> HistoryEvent.LoadSucceeded(requestId, value)
        is PutioResult.Failure -> HistoryEvent.LoadFailed(requestId, failure)
    }

private fun PutioResult<Unit>.toClearEvent(requestId: HistoryRequestId): HistoryEvent =
    when (this) {
        is PutioResult.Success -> HistoryEvent.ClearSucceeded(requestId)
        is PutioResult.Failure -> HistoryEvent.ClearFailed(requestId, failure)
    }
