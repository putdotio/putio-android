package io.putdotio.android.search

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepositoryResult
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class SearchController internal constructor(
    private val repository: SearchRepository,
    private val recentSearchStore: RecentSearchStore,
    parentScope: CoroutineScope,
    private val delaySearch: suspend (Long) -> Unit,
) : Closeable {
    constructor(
        repository: SearchRepository,
        recentSearchStore: RecentSearchStore,
        parentScope: CoroutineScope,
    ) : this(repository, recentSearchStore, parentScope, delaySearch = { delay(it) })

    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val mutableState =
        MutableStateFlow(
            SearchState(
                query = "",
                content = SearchContent.Idle,
                recentTerms = recentSearchStore.terms.value,
                consumedCursors = emptySet(),
                nextRequestValue = INITIAL_REQUEST_VALUE,
            ),
        )
    private val outputChannel = Channel<SearchOutput>(Channel.BUFFERED)
    private val requestIds = SearchRequestIds(mutableState)
    private var activeJob: Job? = null
    private var closed = false

    val state: StateFlow<SearchState> = mutableState.asStateFlow()
    val outputs: Flow<SearchOutput> = outputChannel.receiveAsFlow()

    init {
        controllerScope.launch {
            recentSearchStore.terms.collectLatest { terms ->
                synchronized(lock) {
                    if (!closed) mutableState.value = mutableState.value.copy(recentTerms = terms)
                }
            }
        }
    }

    fun updateQuery(query: String): Boolean {
        val term = query.trim().takeIf(String::isNotBlank)?.let(::SearchTerm)
        val requestId: SearchRequestId?
        val previousJob: Job?
        synchronized(lock) {
            if (closed) return false
            previousJob = activeJob
            activeJob = null
            if (term == null) {
                mutableState.value = mutableState.value.copy(query = query, content = SearchContent.Idle)
                requestId = null
            } else {
                requestId = requestIds.next()
                mutableState.value =
                    mutableState.value.copy(
                        query = query,
                        content = SearchContent.Debouncing(term, requestId),
                        consumedCursors = emptySet(),
                    )
            }
        }
        previousJob?.cancel()
        requestId?.let { launchInitial(term = requireNotNull(term), requestId = it, debounce = true) }
        return true
    }

    fun submit(): Boolean {
        val term: SearchTerm
        val requestId: SearchRequestId
        val previousJob: Job?
        synchronized(lock) {
            val trimmed = mutableState.value.query.trim()
            if (closed || trimmed.isBlank()) return false
            term = SearchTerm(trimmed)
            requestId = requestIds.next()
            previousJob = activeJob
            activeJob = null
            mutableState.value =
                mutableState.value.copy(
                    query = trimmed,
                    content = SearchContent.Loading(term, requestId),
                    consumedCursors = emptySet(),
                )
        }
        previousJob?.cancel()
        recentSearchStore.record(term)
        launchInitial(term, requestId, debounce = false)
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    fun loadNextPage(): Boolean {
        val request =
            synchronized(lock) {
                if (closed || activeJob != null) {
                    null
                } else {
                    val cursor = mutableState.value.content.availableCursor()
                    if (cursor == null || cursor in mutableState.value.consumedCursors) {
                        null
                    } else {
                        val requestId = requestIds.next()
                        mutableState.value = mutableState.value.withPaging(SearchPaging.Loading(cursor, requestId))
                        PageRequest(cursor, requestId)
                    }
                }
            } ?: return false
        launch(request.requestId) {
            val result =
                try {
                    repository.loadNextPage(request.cursor)
                } catch (error: CancellationException) {
                    throw error
                } catch (unexpected: Exception) {
                    FilesRepositoryResult.Failure(FilesFailure.Unexpected(unexpected))
                }
            applyPageResult(lock, mutableState, request.cursor, request.requestId, result)
        }
        return true
    }

    fun retry(): Boolean {
        val content = state.value.content
        return when (content) {
            is SearchContent.Failed -> retryInitial(content.term)
            is SearchContent.Empty -> if (content.paging is SearchPaging.Failed) loadNextPage() else false
            is SearchContent.Ready -> if (content.paging is SearchPaging.Failed) loadNextPage() else false
            else -> false
        }
    }

    fun openResult(itemId: FilesItemId): Boolean {
        val item =
            synchronized(lock) {
                if (closed) null else mutableState.value.content.items().firstOrNull { it.id == itemId }
            }
        return item?.let { outputChannel.trySend(SearchOutput.OpenResult(it)).isSuccess } ?: false
    }

    fun editRecentSearches(edit: RecentSearchEdit): Boolean {
        val canEdit =
            synchronized(lock) {
                when (edit) {
                    is RecentSearchEdit.Remove -> !closed && edit.term in mutableState.value.recentTerms
                    RecentSearchEdit.Clear -> !closed && mutableState.value.recentTerms.isNotEmpty()
                }
            }
        if (!canEdit) return false
        when (edit) {
            is RecentSearchEdit.Remove -> recentSearchStore.remove(edit.term)
            RecentSearchEdit.Clear -> recentSearchStore.clear()
        }
        return true
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            activeJob = null
        }
        outputChannel.close()
        controllerScope.cancel()
    }

    private fun retryInitial(term: SearchTerm): Boolean {
        val requestId: SearchRequestId
        synchronized(lock) {
            if (closed || activeJob != null || mutableState.value.content !is SearchContent.Failed) return false
            requestId = requestIds.next()
            mutableState.value =
                mutableState.value.copy(
                    content = SearchContent.Loading(term, requestId),
                    consumedCursors = emptySet(),
                )
        }
        launchInitial(term, requestId, debounce = false)
        return true
    }

    @Suppress("TooGenericExceptionCaught")
    private fun launchInitial(
        term: SearchTerm,
        requestId: SearchRequestId,
        debounce: Boolean,
    ) {
        launch(requestId) {
            if (debounce) {
                delaySearch(SEARCH_DEBOUNCE_MILLIS)
                val committed = synchronized(lock) {
                    val content = mutableState.value.content
                    if (content !is SearchContent.Debouncing || content.requestId != requestId) {
                        false
                    } else {
                        mutableState.value = mutableState.value.copy(content = SearchContent.Loading(term, requestId))
                        true
                    }
                }
                if (!committed) return@launch
                recentSearchStore.record(term)
            }
            val result =
                try {
                    repository.search(term)
                } catch (error: CancellationException) {
                    throw error
                } catch (unexpected: Exception) {
                    FilesRepositoryResult.Failure(FilesFailure.Unexpected(unexpected))
                }
            applyInitialResult(lock, mutableState, term, requestId, result)
        }
    }

    private fun launch(
        requestId: SearchRequestId,
        block: suspend () -> Unit,
    ) {
        val job = controllerScope.launch(start = CoroutineStart.LAZY) { block() }
        val shouldStart =
            synchronized(lock) {
                if (closed || !mutableState.value.hasRequest(requestId) || activeJob != null) {
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
                if (activeJob === job) activeJob = null
            }
        }
        job.start()
    }

}

private data class PageRequest(
    val cursor: FilesCursor,
    val requestId: SearchRequestId,
)

private class SearchRequestIds(
    private val mutableState: MutableStateFlow<SearchState>,
) {
    fun next(): SearchRequestId {
        val state = mutableState.value
        val requestId = SearchRequestId(state.nextRequestValue)
        mutableState.value = state.copy(nextRequestValue = state.nextRequestValue + 1)
        return requestId
    }
}

private fun applyInitialResult(
    lock: Any,
    mutableState: MutableStateFlow<SearchState>,
    term: SearchTerm,
    requestId: SearchRequestId,
    result: FilesRepositoryResult<SearchPage>,
) {
    synchronized(lock) {
        val content = mutableState.value.content
        if (content !is SearchContent.Loading || content.requestId != requestId || content.term != term) return
        mutableState.value =
            when (result) {
                is FilesRepositoryResult.Success -> mutableState.value.withInitialPage(term, result.value)
                is FilesRepositoryResult.Failure ->
                    mutableState.value.copy(content = SearchContent.Failed(term, result.failure))
            }
    }
}

private fun applyPageResult(
    lock: Any,
    mutableState: MutableStateFlow<SearchState>,
    cursor: FilesCursor,
    requestId: SearchRequestId,
    result: FilesRepositoryResult<SearchPage>,
) {
    synchronized(lock) {
        val state = mutableState.value
        if (!state.content.hasPagingRequest(cursor, requestId)) return
        mutableState.value =
            when (result) {
                is FilesRepositoryResult.Success -> state.withNextPage(cursor, result.value)
                is FilesRepositoryResult.Failure -> state.withPaging(SearchPaging.Failed(cursor, result.failure))
            }
    }
}

private fun SearchState.withInitialPage(
    term: SearchTerm,
    page: SearchPage,
): SearchState {
    val items = page.items.distinctBy(FilesItem::id)
    val paging = page.nextCursor?.let(SearchPaging::Available) ?: SearchPaging.Complete
    val content =
        if (items.isEmpty()) SearchContent.Empty(term, paging) else SearchContent.Ready(term, items, paging)
    return copy(content = content, consumedCursors = emptySet())
}

private fun SearchState.withNextPage(
    cursor: FilesCursor,
    page: SearchPage,
): SearchState {
    val consumed = consumedCursors + cursor
    val paging = page.nextCursor?.takeUnless(consumed::contains)?.let(SearchPaging::Available) ?: SearchPaging.Complete
    val items = (content.items() + page.items).distinctBy(FilesItem::id)
    val term =
        when (val current = content) {
            is SearchContent.Empty -> current.term
            is SearchContent.Ready -> current.term
            else -> error("Paging requires loaded search content")
        }
    val nextContent =
        if (items.isEmpty()) SearchContent.Empty(term, paging) else SearchContent.Ready(term, items, paging)
    return copy(content = nextContent, consumedCursors = consumed)
}

private fun SearchState.withPaging(paging: SearchPaging): SearchState =
    when (val current = content) {
        is SearchContent.Empty -> copy(content = current.copy(paging = paging))
        is SearchContent.Ready -> copy(content = current.copy(paging = paging))
        else -> this
    }

private fun SearchContent.availableCursor(): FilesCursor? =
    when (this) {
        is SearchContent.Empty ->
            when (val current = paging) {
                is SearchPaging.Available -> current.cursor
                is SearchPaging.Failed -> current.cursor
                SearchPaging.Complete, is SearchPaging.Loading -> null
            }

        is SearchContent.Ready ->
            when (val current = paging) {
                is SearchPaging.Available -> current.cursor
                is SearchPaging.Failed -> current.cursor
                SearchPaging.Complete, is SearchPaging.Loading -> null
            }

        else -> null
    }

private fun SearchContent.items(): List<FilesItem> =
    when (this) {
        is SearchContent.Ready -> items
        else -> emptyList()
    }

private fun SearchContent.hasPagingRequest(
    cursor: FilesCursor,
    requestId: SearchRequestId,
): Boolean =
    when (this) {
        is SearchContent.Empty -> paging == SearchPaging.Loading(cursor, requestId)
        is SearchContent.Ready -> paging == SearchPaging.Loading(cursor, requestId)
        else -> false
    }

private fun SearchState.hasRequest(requestId: SearchRequestId): Boolean =
    when (val current = content) {
        is SearchContent.Debouncing -> current.requestId == requestId
        is SearchContent.Loading -> current.requestId == requestId
        is SearchContent.Empty -> (current.paging as? SearchPaging.Loading)?.requestId == requestId
        is SearchContent.Ready -> (current.paging as? SearchPaging.Loading)?.requestId == requestId
        is SearchContent.Failed, SearchContent.Idle -> false
    }

internal const val SEARCH_DEBOUNCE_MILLIS = 300L
private const val INITIAL_REQUEST_VALUE = 1L
