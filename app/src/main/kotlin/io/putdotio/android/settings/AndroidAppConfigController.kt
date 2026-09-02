package io.putdotio.android.settings

import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class AndroidAppConfigController(
    private val repository: AndroidAppConfigRepository,
    parentScope: CoroutineScope,
) : Closeable {
    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val initial = AndroidAppConfigReducer.start()
    private val mutableState = MutableStateFlow(initial.state)
    private var activeJob: Job? = null
    private var closed = false

    val state: StateFlow<AndroidAppConfigState> = mutableState.asStateFlow()

    init {
        initial.effect?.let(::launchEffect)
    }

    fun dispatch(event: AndroidAppConfigEvent): Boolean {
        val transition =
            synchronized(lock) {
                if (closed) return false
                AndroidAppConfigReducer.reduce(mutableState.value, event).also {
                    mutableState.value = it.state
                }
            }
        transition.effect?.let(::launchEffect)
        return transition.consumed
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            activeJob = null
        }
        controllerScope.cancel()
    }

    // This controller boundary contains repository implementations that escape the declared result contract.
    @Suppress("TooGenericExceptionCaught")
    private fun launchEffect(effect: AndroidAppConfigEffect) {
        val job =
            controllerScope.launch(start = CoroutineStart.LAZY) {
                val event =
                    try {
                        repository.execute(effect)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (unexpected: Exception) {
                        effect.unexpectedFailure(unexpected)
                    }
                releaseActiveJob(coroutineContext[Job])
                dispatch(event)
            }
        val shouldStart =
            synchronized(lock) {
                if (closed || activeJob != null || !mutableState.value.hasRequest(effect.requestId)) {
                    false
                } else {
                    activeJob = job
                    true
                }
            }
        if (!shouldStart) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { releaseActiveJob(job) }
        job.start()
    }

    private fun releaseActiveJob(job: Job?) {
        synchronized(lock) {
            if (activeJob === job) activeJob = null
        }
    }
}

private fun AndroidAppConfigEffect.unexpectedFailure(error: Exception): AndroidAppConfigEvent =
    when (this) {
        is AndroidAppConfigEffect.Load ->
            AndroidAppConfigEvent.LoadFailed(requestId, AndroidAppConfigFailure.Unexpected(error))
        is AndroidAppConfigEffect.Save ->
            AndroidAppConfigEvent.SaveFailed(requestId, AndroidAppConfigFailure.Unexpected(error))
        is AndroidAppConfigEffect.Refresh ->
            AndroidAppConfigEvent.RefreshFailed(requestId, AndroidAppConfigFailure.Unexpected(error))
    }

private fun AndroidAppConfigState.hasRequest(requestId: AndroidAppConfigRequestId): Boolean =
    (content as? AndroidAppConfigContent.Loading)?.requestId == requestId ||
        (mutation as? AndroidAppConfigMutation.Saving)?.requestId == requestId
