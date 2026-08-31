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

internal class AccountSettingsController(
    private val repository: AccountSettingsRepository,
    parentScope: CoroutineScope,
) : Closeable {
    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val initial = AccountSettingsReducer.start()
    private val mutableState = MutableStateFlow(initial.state)
    private var activeJob: Job? = null
    private var closed = false

    val state: StateFlow<AccountSettingsState> = mutableState.asStateFlow()

    init {
        initial.effect?.let(::launchEffect)
    }

    fun dispatch(event: AccountSettingsEvent): Boolean {
        val transition =
            synchronized(lock) {
                if (closed) {
                    return false
                }
                AccountSettingsReducer.reduce(mutableState.value, event).also {
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
    private fun launchEffect(effect: AccountSettingsEffect) {
        val job =
            controllerScope.launch(start = CoroutineStart.LAZY) {
                val event =
                    try {
                        repository.execute(effect)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (unexpected: Exception) {
                        when (effect) {
                            is AccountSettingsEffect.Load ->
                                AccountSettingsEvent.LoadFailed(
                                    effect.requestId,
                                    AccountSettingsFailure.Unexpected(unexpected),
                                )
                            is AccountSettingsEffect.Save ->
                                AccountSettingsEvent.SaveFailed(
                                    effect.requestId,
                                    AccountSettingsFailure.Unexpected(unexpected),
                                )
                        }
                    }
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

private fun AccountSettingsState.hasRequest(requestId: AccountSettingsRequestId): Boolean =
    (content as? AccountSettingsContent.Loading)?.requestId == requestId ||
        (mutation as? AccountSettingsMutation.Saving)?.requestId == requestId
