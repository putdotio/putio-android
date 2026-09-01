package io.putdotio.android.files

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

class FilesBrowserController(
    private val repository: FilesRepository,
    parentScope: CoroutineScope,
) : Closeable {
    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val jobs = mutableMapOf<FilesRequestId, Job>()
    private val initial = FilesBrowserReducer.start()
    private val mutableState = MutableStateFlow(initial.state)
    private var closed = false

    val state: StateFlow<FilesBrowserState> = mutableState.asStateFlow()

    init {
        initial.effect?.let(::launchEffect)
    }

    fun dispatch(event: FilesBrowserEvent): Boolean {
        val (transition, removedJobs) =
            synchronized(lock) {
                if (closed) {
                    return false
                }

                val previousState = mutableState.value
                val next = FilesBrowserReducer.reduce(previousState, event)
                mutableState.value = next.state
                val completedRequestId = event.completedRequestId()
                val removedJobs =
                    jobs.keys
                        .filter { requestId ->
                            previousState.hasRequest(requestId) && !next.state.hasRequest(requestId)
                        }.mapNotNull { requestId ->
                            jobs.remove(requestId)?.let { requestId to it }
                        }
                next to RemovedJobs(completedRequestId, removedJobs)
            }
        removedJobs.jobs
            .filterNot { (requestId) -> requestId == removedJobs.completedRequestId }
            .forEach { (_, job) -> job.cancel() }
        transition.effect?.let(::launchEffect)
        return transition.consumed
    }

    private fun FilesBrowserEvent.completedRequestId(): FilesRequestId? =
        when (this) {
            is FilesBrowserEvent.LoadSucceeded -> requestId
            is FilesBrowserEvent.LoadFailed -> requestId
            is FilesBrowserEvent.SortPersisted -> requestId
            is FilesBrowserEvent.LoadNextPage,
            is FilesBrowserEvent.OpenFolder,
            is FilesBrowserEvent.SelectSort,
            is FilesBrowserEvent.ViewportChanged,
            FilesBrowserEvent.NavigateBack,
            FilesBrowserEvent.Refresh,
            FilesBrowserEvent.Retry,
            -> null
        }

    private data class RemovedJobs(
        val completedRequestId: FilesRequestId?,
        val jobs: List<Pair<FilesRequestId, Job>>,
    )

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            jobs.clear()
        }
        controllerScope.cancel()
    }

    // Coroutine jobs surface JVM exceptions without a typed throws contract; cancellation remains control flow.
    @Suppress("TooGenericExceptionCaught")
    private fun launchEffect(effect: FilesBrowserEffect) {
        val job =
            controllerScope.launch(start = CoroutineStart.LAZY) {
                val event =
                    try {
                        repository.execute(effect)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (unexpected: Exception) {
                        FilesBrowserEvent.LoadFailed(effect.requestId, FilesFailure.Unexpected(unexpected))
                    }
                dispatch(event)
            }

        val shouldStart =
            synchronized(lock) {
                when {
                    closed -> false
                    !mutableState.value.hasRequest(effect.requestId) -> false
                    jobs.containsKey(effect.requestId) -> false
                    else -> {
                        jobs[effect.requestId] = job
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
                if (jobs[effect.requestId] === job) {
                    jobs.remove(effect.requestId)
                }
            }
        }
        job.start()
    }
}
