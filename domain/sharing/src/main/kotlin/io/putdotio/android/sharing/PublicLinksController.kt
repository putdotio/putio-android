package io.putdotio.android.sharing

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

/** The signed-in session's public links, shared by the Files sheet and the Account list. */
public class PublicLinksController(
    private val repository: PublicLinksRepository,
    parentScope: CoroutineScope,
) : Closeable {
    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private var machine = PublicLinksMachine()
    private val mutableState = MutableStateFlow(machine.state)
    private var closed = false
    public val state: StateFlow<PublicLinksState> = mutableState.asStateFlow()

    /** Whether the event was accepted; a refused one changes nothing. */
    public fun dispatch(event: PublicLinksEvent): Boolean = synchronized(lock) {
        if (closed || !controllerJob.isActive) return@synchronized false
        val next = machine.transition(event) ?: return@synchronized false
        advance(next)
        true
    }

    // A completion can chain the next request, so every state change starts whatever it asks for.
    private fun advance(next: PublicLinksMachine) {
        val started = next.request.takeIf { it != machine.request }
        machine = next
        mutableState.value = next.state
        started?.let(::start)
    }

    private fun start(request: PublicLinksRequest) {
        scope.launch {
            val completion: (PublicLinksMachine) -> PublicLinksMachine = when (request) {
                is PublicLinksRequest.Load -> safely { repository.list() }
                    .let { result -> { it.completeLoad(request, result) } }
                is PublicLinksRequest.Create -> safely { repository.create(request.fileId) }
                    .let { result -> { it.completeCreate(request.fileId, result) } }
                is PublicLinksRequest.Revoke -> safely { repository.revoke(request.link.id) }
                    .let { result -> { it.completeRevoke(request.link, result) } }
            }
            synchronized(lock) {
                if (!closed && controllerJob.isActive && machine.request == request) advance(completion(machine))
            }
        }
    }

    // Injected repositories must preserve the same cancellation contract as the SDK adapter.
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
