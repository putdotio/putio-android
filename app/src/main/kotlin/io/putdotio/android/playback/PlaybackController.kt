package io.putdotio.android.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.CancellationException

class PlaybackController(
    target: PlaybackTarget,
    private val repository: PlaybackRepository,
    parentScope: CoroutineScope,
) : Closeable {
    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val initial = PlaybackReducer.start(target)
    private val mutableState = MutableStateFlow(initial.state)
    private var activeJob: Job? = null
    private var closed = false

    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    init {
        requireNotNull(initial.effect).let(::launchEffect)
    }

    fun dispatch(event: PlaybackEvent): Boolean {
        val transition =
            synchronized(lock) {
                if (closed) {
                    return false
                }
                PlaybackReducer.reduce(mutableState.value, event).also {
                    mutableState.value = it.state
                }
            }
        transition.effect?.let(::launchEffect)
        return transition.consumed
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            activeJob = null
        }
        controllerScope.cancel()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun launchEffect(effect: PlaybackEffect) {
        val job =
            controllerScope.launch(start = CoroutineStart.LAZY) {
                val event =
                    try {
                        when (effect) {
                            is PlaybackEffect.Resolve -> effect.resolveEvent()
                            is PlaybackEffect.FindNext -> effect.findNextEvent()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (unexpected: Exception) {
                        effect.failureEvent(PlaybackFailure.Unexpected(unexpected))
                    }
                synchronized(lock) {
                    if (activeJob === coroutineContext[Job]) {
                        activeJob = null
                    }
                }
                dispatch(event)
            }

        val shouldStart =
            synchronized(lock) {
                when {
                    closed -> false
                    !mutableState.value.isActive(effect) -> false
                    activeJob != null -> false
                    else -> {
                        activeJob = job
                        true
                    }
                }
            }
        if (!shouldStart) {
            job.cancel()
            return
        }

        job.invokeOnCompletion {
            synchronized(lock) {
                if (activeJob === job) {
                    activeJob = null
                }
            }
        }
        job.start()
    }

    private suspend fun PlaybackEffect.Resolve.resolveEvent(): PlaybackEvent =
        when (val result = repository.resolve(target)) {
            is PlaybackRepositoryResult.Success -> PlaybackEvent.ResolveSucceeded(requestId, result.value)
            is PlaybackRepositoryResult.Failure -> PlaybackEvent.ResolveFailed(requestId, result.failure)
        }

    private suspend fun PlaybackEffect.FindNext.findNextEvent(): PlaybackEvent =
        when (val result = repository.findNextVideo(target)) {
            is PlaybackNextResult.Found -> PlaybackEvent.NextFound(requestId, result.target)
            PlaybackNextResult.Ended -> PlaybackEvent.NextEnded(requestId)
            is PlaybackNextResult.Failure -> PlaybackEvent.NextFailed(requestId, result.failure)
        }
}

private fun PlaybackState.isActive(effect: PlaybackEffect): Boolean =
    when (effect) {
        is PlaybackEffect.Resolve -> (content as? PlaybackContent.Loading)?.requestId == effect.requestId
        is PlaybackEffect.FindNext -> (content as? PlaybackContent.FindingNext)?.requestId == effect.requestId
    }

private fun PlaybackEffect.failureEvent(failure: PlaybackFailure): PlaybackEvent =
    when (this) {
        is PlaybackEffect.Resolve -> PlaybackEvent.ResolveFailed(requestId, failure)
        is PlaybackEffect.FindNext -> PlaybackEvent.NextFailed(requestId, failure)
    }
