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
                        when (val result = repository.resolve(effect.target)) {
                            is PlaybackRepositoryResult.Success ->
                                PlaybackEvent.ResolveSucceeded(effect.requestId, result.value)

                            is PlaybackRepositoryResult.Failure ->
                                PlaybackEvent.ResolveFailed(effect.requestId, result.failure)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (unexpected: Exception) {
                        PlaybackEvent.ResolveFailed(effect.requestId, PlaybackFailure.Unexpected(unexpected))
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
                    (mutableState.value.content as? PlaybackContent.Loading)?.requestId != effect.requestId -> false
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
}
