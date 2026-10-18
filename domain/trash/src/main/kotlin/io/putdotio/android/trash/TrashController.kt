package io.putdotio.android.trash

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

public class TrashController(
    private val repository: TrashRepository,
    parentScope: CoroutineScope,
) : Closeable {
    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private var machine = TrashMachine()
    private val mutableState = MutableStateFlow(machine.state)
    private var job: Job? = null
    private var closed = false
    public val state: StateFlow<TrashState> = mutableState.asStateFlow()

    public fun dispatch(event: TrashEvent): Boolean = synchronized(lock) {
        if (closed || !controllerJob.isActive) return@synchronized false
        val next = machine.transition(event) ?: return@synchronized false
        update(next)
        true
    }

    private fun update(next: TrashMachine) {
        val previous = machine.request
        machine = next
        mutableState.value = next.state
        if (previous != next.request) {
            job?.cancel()
            job = null
            next.request?.let(::startRequest)
        }
    }

    // One request is owned at a time. IDs reject results from cancelled reads and old sessions.
    private fun startRequest(request: TrashRequest) {
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val completion = execute(request)
            synchronized(lock) {
                if (!closed && controllerJob.isActive && machine.request == request) {
                    job = null
                    update(completion(machine))
                }
            }
        }
        job = next
        next.start()
    }

    private suspend fun execute(request: TrashRequest): (TrashMachine) -> TrashMachine = when (request) {
        is TrashRequest.ListPage -> safely {
            if (request.cursor == null) repository.load() else repository.loadNextPage(request.cursor)
        }.let { result -> { it.completeList(request, result) } }
        is TrashRequest.Restore ->
            safely { repository.restore(request.item.id) }.let { result -> { it.completeRestore(request, result) } }
        is TrashRequest.Check ->
            safely { repository.resolveItem(request.item.id) }.let { result -> { it.completeCheck(request, result) } }
        is TrashRequest.Act -> safely {
            when (val action = request.action) {
                is TrashAction.DeleteItem -> repository.deleteItem(action.item.id)
                TrashAction.RestoreAll -> repository.restoreAll(checkNotNull(request.selection))
                TrashAction.Empty -> repository.empty()
            }
        }.let { result -> { it.completeAction(request, result) } }
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
        job?.cancel()
        scope.cancel()
    }
}
