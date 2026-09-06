package io.putdotio.android.trash

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult
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

class TrashController(
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
    val state: StateFlow<TrashState> = mutableState.asStateFlow()

    fun dispatch(event: TrashEvent): Boolean = synchronized(lock) {
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
        is TrashRequest.ListPage -> {
            val result = safely {
                if (request.cursor == null) repository.load() else repository.loadNextPage(request.cursor)
            }
            val completion: (TrashMachine) -> TrashMachine = { it.completeList(request, result) }
            completion
        }
        is TrashRequest.Restore -> {
            val result = safely { repository.restore(request.item.id) }
            val completion: (TrashMachine) -> TrashMachine = { it.completeRestore(request, result) }
            completion
        }
        is TrashRequest.Check -> {
            val result = safely { repository.resolveItem(request.item.id) }
            val completion: (TrashMachine) -> TrashMachine = { it.completeCheck(request, result) }
            completion
        }
    }

    // Injected repositories must preserve the same cancellation contract as the SDK adapter.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> safely(block: suspend () -> FilesRepositoryResult<T>): FilesRepositoryResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unexpected: Exception) {
        FilesRepositoryResult.Failure(FilesFailure.Unexpected(unexpected))
    }

    override fun close() = synchronized(lock) {
        closed = true
        job?.cancel()
        scope.cancel()
    }
}
