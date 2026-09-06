package io.putdotio.android.files

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

class FilesMoveDestinationController(
    sourceItem: FilesItem,
    sourceFolderId: FilesItemId,
    private val repository: FilesRepository,
    parentScope: CoroutineScope,
) : Closeable {
    init {
        require(sourceItem.id.value > 0L && sourceFolderId.value >= 0L) { "Move picker requires a non-root source" }
    }

    private val lock = Any()
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val firstRequest = FilesMoveDestinationRequest(FilesFolder.Root.id, FilesRequestId(1L))
    private val mutableState = MutableStateFlow(FilesMoveDestinationState(
        sourceItem, sourceFolderId,
        listOf(FilesMoveDestinationFolder(FilesFolder.Root, FilesContent.Loading(firstRequest.requestId))),
        nextRequestValue = 2L,
    ))
    private var job: Job? = null
    private var closed = false
    val state: StateFlow<FilesMoveDestinationState> = mutableState.asStateFlow()

    init {
        synchronized(lock) { startRequest(firstRequest) }
    }

    fun dispatch(event: FilesMoveDestinationEvent): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val transition = mutableState.value.reduce(event)
        if (!transition.consumed) return@synchronized false
        mutableState.value = transition.state
        job?.cancel()
        job = null
        transition.request?.let(::startRequest)
        true
    }

    // One current-folder read is owned at a time; navigation cancels it and IDs reject late completions.
    private fun startRequest(request: FilesMoveDestinationRequest) {
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val result = load(request)
            synchronized(lock) {
                if (!closed) mutableState.value = mutableState.value.complete(request, result)
            }
        }
        job = next
        next.start()
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun load(request: FilesMoveDestinationRequest): FilesRepositoryResult<FilesPage> = try {
        repository.loadMoveDestinations(request.folderId, request.cursor)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unexpected: Exception) {
        FilesRepositoryResult.Failure(FilesFailure.Unexpected(unexpected))
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            job?.cancel()
            scope.cancel()
        }
    }
}
