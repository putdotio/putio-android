package io.putdotio.android.transfers

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TransfersController internal constructor(
    private val repository: TransfersRepository,
    parentScope: CoroutineScope,
    private val waitForPoll: suspend (Long) -> Unit,
) : Closeable {
    constructor(repository: TransfersRepository, parentScope: CoroutineScope) :
        this(repository, parentScope, waitForPoll = { delay(it) })

    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val mutableState = MutableStateFlow(TransfersReducer.start().state)
    private var readJob: Job? = null
    private var mutationJob: Job? = null
    private var pollJob: Job? = null
    private var closed = false

    val state: StateFlow<TransfersState> = mutableState.asStateFlow()

    init {
        launchEffect(TransfersReducer.start().effect as TransfersEffect.Load)
    }

    fun dispatch(event: TransfersEvent): Boolean {
        val (transition, readToCancel) =
            synchronized(lock) {
                if (closed) return false
                val current = mutableState.value
                val candidate = TransfersReducer.reduce(current, event)
                val next =
                    if (readJob != null && candidate.effect.isReadEffect) {
                        TransfersTransition(current, consumed = false)
                    } else {
                        candidate
                    }
                mutableState.value = next.state
                val cancelActiveRead =
                    next.consumed &&
                        (
                            event is TransfersEvent.VisibilityChanged &&
                                !event.visible &&
                                current.refresh.isRunning ||
                                event is TransfersEvent.Open &&
                                current.refresh is TransfersRefresh.Polling
                        )
                val cancelledRead = readJob.takeIf { cancelActiveRead }
                next to cancelledRead
            }
        readToCancel?.cancel()
        transition.effect?.let(::launchEffect)
        reconcilePolling()
        return transition.consumed
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            readJob = null
            mutationJob = null
            pollJob = null
        }
        scope.cancel()
    }

    private fun launchEffect(effect: TransfersEffect) {
        when (effect) {
            is TransfersEffect.Load -> launchRead(effect)
            is TransfersEffect.RefreshRows -> launchRead(effect)
            is TransfersEffect.Mutate -> launchMutation(effect)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun launchRead(effect: TransfersEffect.Load) {
        launchRead(effect.requestId) {
            repository.loadEvent(effect)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun launchRead(effect: TransfersEffect.RefreshRows) {
        launchRead(effect.requestId) {
            repository.refresh(effect.ids).toRowsEvent(effect.requestId)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun launchRead(
        requestId: TransfersRequestId,
        request: suspend () -> TransfersEvent,
    ) {
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                val event =
                    try {
                        request()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (unexpected: Exception) {
                        TransfersEvent.ListFailed(requestId, FilesFailure.Unexpected(unexpected))
                    }
                synchronized(lock) {
                    if (readJob === job) readJob = null
                }
                dispatch(event)
            }
        if (!installReadJob(requestId, job)) {
            job.cancel()
            return
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (readJob === job) readJob = null
            }
            reconcilePolling()
        }
        job.start()
    }

    private fun installReadJob(requestId: TransfersRequestId, job: Job): Boolean =
        synchronized(lock) {
            if (closed || readJob != null || !mutableState.value.hasReadRequest(requestId)) {
                false
            } else {
                readJob = job
                true
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun launchMutation(effect: TransfersEffect.Mutate) {
        val readToCancel =
            synchronized(lock) {
                readJob.also {
                    readJob = null
                }
            }
        readToCancel?.cancel()
        lateinit var job: Job
        job =
            scope.launch(start = CoroutineStart.LAZY) {
                readToCancel?.join()
                val canExecute =
                    synchronized(lock) {
                        mutationJob === job && mutableState.value.hasMutationRequest(effect.requestId)
                    }
                if (!canExecute) return@launch
                val event =
                    try {
                        repository.execute(effect.action).toMutationEvent(effect.requestId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (unexpected: Exception) {
                        TransfersEvent.MutationFailed(
                            effect.requestId,
                            FilesFailure.Unexpected(unexpected),
                        )
                    }
                synchronized(lock) {
                    if (mutationJob === job) mutationJob = null
                }
                dispatch(event)
                if (effect.action == TransferAction.Clean && event is TransfersEvent.MutationSucceeded) {
                    dispatch(TransfersEvent.Refresh)
                }
            }
        val shouldStart =
            synchronized(lock) {
                if (closed || mutationJob != null || !mutableState.value.hasMutationRequest(effect.requestId)) {
                    false
                } else {
                    mutationJob = job
                    true
                }
            }
        if (!shouldStart) {
            job.cancel()
            return
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (mutationJob === job) mutationJob = null
            }
        }
        job.start()
    }

    private fun reconcilePolling() {
        val jobToCancel: Job?
        val jobToStart: Job?
        synchronized(lock) {
            val shouldPoll =
                !closed &&
                    mutableState.value.visible &&
                    mutableState.value.hasRowsNeedingPolling() &&
                    mutableState.value.refresh == TransfersRefresh.Idle &&
                    mutableState.value.mutation !is TransferMutation.Running &&
                    mutableState.value.navigation !is TransferNavigation.Resolving &&
                    readJob == null
            if (!shouldPoll) {
                jobToCancel = pollJob
                pollJob = null
                jobToStart = null
            } else if (pollJob == null) {
                val newJob =
                    scope.launch(start = CoroutineStart.LAZY) {
                        waitForPoll(POLL_INTERVAL_MILLIS)
                        synchronized(lock) {
                            if (pollJob === coroutineContext[Job]) pollJob = null
                        }
                        dispatch(TransfersEvent.Poll)
                    }
                pollJob = newJob
                jobToStart = newJob
                jobToCancel = null
            } else {
                jobToStart = null
                jobToCancel = null
            }
        }
        jobToCancel?.cancel()
        jobToStart?.start()
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 5_000L
    }
}

private fun TransfersState.hasReadRequest(requestId: TransfersRequestId): Boolean =
    (content as? TransfersContent.InitialLoading)?.requestId == requestId ||
        refresh.hasRequest(requestId) ||
        ((content as? TransfersContent.Ready)?.paging as? TransfersPaging.Loading)?.requestId == requestId

private fun TransfersState.hasMutationRequest(requestId: TransfersRequestId): Boolean =
    (mutation as? TransferMutation.Running)?.requestId == requestId

private val TransfersEffect?.isReadEffect: Boolean
    get() = this is TransfersEffect.Load || this is TransfersEffect.RefreshRows

private suspend fun TransfersRepository.loadEvent(effect: TransfersEffect.Load): TransfersEvent =
    when (val result = load(effect.cursor)) {
        is FilesRepositoryResult.Failure -> TransfersEvent.ListFailed(effect.requestId, result.failure)
        is FilesRepositoryResult.Success -> {
            val omittedIds = effect.reconcileIds - result.value.items.mapTo(mutableSetOf(), TransferItem::id)
            if (omittedIds.isEmpty()) {
                TransfersEvent.ListSucceeded(effect.requestId, result.value)
            } else {
                when (val reconciled = refresh(omittedIds.toList())) {
                    is FilesRepositoryResult.Success ->
                        TransfersEvent.FirstPageRefreshed(
                            effect.requestId,
                            result.value,
                            reconciled.value.items,
                        )
                    is FilesRepositoryResult.Failure ->
                        TransfersEvent.ListFailed(effect.requestId, reconciled.failure)
                }
            }
        }
    }

private fun FilesRepositoryResult<TransfersRowRefresh>.toRowsEvent(
    requestId: TransfersRequestId,
): TransfersEvent =
    when (this) {
        is FilesRepositoryResult.Success ->
            TransfersEvent.RowsRefreshed(requestId, value.items, value.missingIds)
        is FilesRepositoryResult.Failure -> TransfersEvent.ListFailed(requestId, failure)
    }

private sealed interface MutationResult {
    data class Item(val item: TransferItem) : MutationResult
    data class Affected(val ids: Set<TransferId>) : MutationResult
}

private suspend fun TransfersRepository.execute(action: TransferAction): FilesRepositoryResult<MutationResult> =
    when (action) {
        is TransferAction.Add -> add(action.submission).mapSuccess(MutationResult::Item)
        is TransferAction.Cancel -> cancel(action.id).mapSuccess { MutationResult.Affected(setOf(action.id)) }
        is TransferAction.Retry -> retry(action.id).mapSuccess(MutationResult::Item)
        TransferAction.Clean -> clean(emptyList()).mapSuccess(MutationResult::Affected)
    }

private fun <T, R> FilesRepositoryResult<T>.mapSuccess(mapper: (T) -> R): FilesRepositoryResult<R> =
    when (this) {
        is FilesRepositoryResult.Success -> FilesRepositoryResult.Success(mapper(value))
        is FilesRepositoryResult.Failure -> this
    }

private fun FilesRepositoryResult<MutationResult>.toMutationEvent(
    requestId: TransfersRequestId,
): TransfersEvent =
    when (this) {
        is FilesRepositoryResult.Failure -> TransfersEvent.MutationFailed(requestId, failure)
        is FilesRepositoryResult.Success ->
            when (val result = value) {
                is MutationResult.Item -> TransfersEvent.MutationSucceeded(requestId, item = result.item)
                is MutationResult.Affected ->
                    TransfersEvent.MutationSucceeded(requestId, affectedIds = result.ids)
            }
    }
