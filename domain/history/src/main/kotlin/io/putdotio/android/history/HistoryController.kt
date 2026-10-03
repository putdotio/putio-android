package io.putdotio.android.history

import io.putdotio.android.files.FilesFailure
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

class HistoryController(
    private val repository: HistoryRepository,
    historyEnabled: Boolean,
    parentScope: CoroutineScope,
) : Closeable {
    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val initial = HistoryReducer.start(historyEnabled)
    private val mutableState = MutableStateFlow(initial.state)
    private val navigationChannel = Channel<HistoryEffect.NavigateToFile>(Channel.BUFFERED)
    private var closed = false

    val state: StateFlow<HistoryState> = mutableState.asStateFlow()
    val navigation: Flow<HistoryEffect.NavigateToFile> = navigationChannel.receiveAsFlow()

    init { initial.effect?.let(::launchEffect) }

    fun dispatch(event: HistoryEvent): Boolean {
        val transition = synchronized(lock) {
            if (closed) return false
            HistoryReducer.reduce(mutableState.value, event).also { mutableState.value = it.state }
        }
        transition.effect?.let(::launchEffect)
        return transition.consumed
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        navigationChannel.close()
        scope.cancel()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun launchEffect(effect: HistoryEffect) {
        if (effect is HistoryEffect.NavigateToFile) {
            navigationChannel.trySend(effect)
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val event = try {
                when (effect) {
                    is HistoryEffect.Load -> repository.load(effect.before).toLoadEvent(effect.requestId)
                    is HistoryEffect.Clear -> repository.clear().toClearEvent(effect.requestId)
                    is HistoryEffect.NavigateToFile -> error("Navigation is handled synchronously")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (unexpected: Exception) {
                when (effect) {
                    is HistoryEffect.Load ->
                        HistoryEvent.LoadFailed(effect.requestId, FilesFailure.Unexpected(unexpected))
                    is HistoryEffect.Clear ->
                        HistoryEvent.ClearFailed(effect.requestId, FilesFailure.Unexpected(unexpected))
                    is HistoryEffect.NavigateToFile -> error("Navigation is handled synchronously")
                }
            }
            dispatch(event)
        }
    }
}

private fun HistoryRepositoryResult<HistoryPage>.toLoadEvent(requestId: HistoryRequestId): HistoryEvent =
    when (this) {
        is HistoryRepositoryResult.Success -> HistoryEvent.LoadSucceeded(requestId, value)
        is HistoryRepositoryResult.Failure -> HistoryEvent.LoadFailed(requestId, failure)
    }

private fun HistoryRepositoryResult<Unit>.toClearEvent(requestId: HistoryRequestId): HistoryEvent =
    when (this) {
        is HistoryRepositoryResult.Success -> HistoryEvent.ClearSucceeded(requestId)
        is HistoryRepositoryResult.Failure -> HistoryEvent.ClearFailed(requestId, failure)
    }
