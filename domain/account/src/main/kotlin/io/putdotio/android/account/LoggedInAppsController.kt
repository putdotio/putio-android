package io.putdotio.android.account

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public class LoggedInAppsController(
    private val repository: LoggedInAppsRepository,
    parentScope: CoroutineScope,
) : Closeable {
    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private var machine = LoggedInAppsMachine()
    private val mutableState = MutableStateFlow(machine.state)
    private var closed = false
    public val state: StateFlow<LoggedInAppsState> = mutableState.asStateFlow()

    public fun dispatch(event: LoggedInAppsEvent): Boolean = synchronized(lock) {
        if (closed || !controllerJob.isActive) return@synchronized false
        val next = machine.transition(event) ?: return@synchronized false
        advance(next)
        true
    }

    private fun advance(next: LoggedInAppsMachine) {
        val started = next.request.takeIf { it != machine.request }
        machine = next
        mutableState.value = next.state
        started?.let(::start)
    }

    private fun start(request: LoggedInAppsRequest) {
        scope.launch {
            val completion: (LoggedInAppsMachine) -> LoggedInAppsMachine = when (request) {
                is LoggedInAppsRequest.Load -> safely { repository.list() }
                    .let { result -> { it.completeLoad(request, result) } }
                is LoggedInAppsRequest.Revoke -> safely { repository.revokeStep(request.step) }
                    .let { result -> { it.completeRevoke(request, result) } }
            }
            synchronized(lock) {
                if (!closed && controllerJob.isActive && machine.request == request) advance(completion(machine))
            }
        }
    }

    private suspend fun LoggedInAppsRepository.revokeStep(step: LoggedInAppsStep): PutioResult<Unit> =
        step.session?.let { revokeSession(it) } ?: revoke(step.app.id)

    // Injected repositories may throw anything; cancellation still has to propagate.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> safely(block: suspend () -> PutioResult<T>): PutioResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unexpected: Exception) {
        PutioResult.Failure(PutioFailure.Unexpected(unexpected))
    }

    override fun close(): Unit = synchronized(lock) {
        closed = true
        scope.cancel()
    }
}
