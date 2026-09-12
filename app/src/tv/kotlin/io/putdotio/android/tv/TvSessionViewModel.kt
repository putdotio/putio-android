package io.putdotio.android.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.history.HistoryController
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What one signed-in TV session needs to build its controllers. */
internal class TvSessionDependencies(
    val filesRepository: FilesRepository,
    val searchRepository: SearchRepository,
    val historyRepository: HistoryRepository,
    /** Turns the file id a history event names into the item Files can open. */
    val filesItemResolver: FilesItemResolver,
    val recentSearchStore: (CoroutineScope) -> RecentSearchStoreOwner,
)

/**
 * The controllers of one signed-in session. They survive navigation and configuration
 * changes together and are closed together when the session ends.
 */
internal class TvSession internal constructor(
    internal val key: TvSessionKey,
    val files: FilesBrowserController,
    val search: SearchController,
    val history: HistoryController,
    private val recentSearches: RecentSearchStoreOwner,
    private val filesItemResolver: FilesItemResolver,
    parentScope: CoroutineScope,
) {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val historyOpenChannel = Channel<FilesItem>(Channel.BUFFERED)
    private val mutableHistoryOpenFailure = MutableStateFlow<FilesFailure?>(null)

    /**
     * Which Files row last held D-pad focus in each folder. It lives here, not in the pane,
     * because the pane is disposed whenever another destination is shown and must come
     * back to the same row. Plain, not snapshot state: a focus move must not recompose.
     */
    val filesFocusMemory: MutableMap<Long, Long> = mutableMapOf()

    val recentSearchFailure = recentSearches.failure

    /** A history event's file, resolved and ready for Files to open. */
    val historyOpens: Flow<FilesItem> = historyOpenChannel.receiveAsFlow()

    /** Why the last history row could not be resolved; cleared by the next success or dismissal. */
    val historyOpenFailure: StateFlow<FilesFailure?> = mutableHistoryOpenFailure.asStateFlow()

    init {
        // Resolved here rather than in the pane so a row chosen just before the pane is
        // disposed still opens, like a search result does.
        scope.launch {
            history.navigation.collect { request ->
                when (val result = filesItemResolver.resolveItem(FilesItemId(request.fileId.value))) {
                    is FilesRepositoryResult.Success -> {
                        mutableHistoryOpenFailure.value = null
                        historyOpenChannel.send(result.value)
                    }
                    is FilesRepositoryResult.Failure -> mutableHistoryOpenFailure.value = result.failure
                }
            }
        }
    }

    fun retryRecentSearches() = recentSearches.retry()

    fun dismissHistoryOpenFailure() {
        mutableHistoryOpenFailure.value = null
    }

    internal fun close() {
        scope.cancel()
        historyOpenChannel.close()
        search.close()
        recentSearches.close()
        history.close()
        files.close()
    }
}

internal data class TvSessionKey(
    val userId: Long,
    val sessionId: TvAuthSessionId,
)

/** Holds the controllers of the signed-in session so they never outlive it. */
internal class TvSessionViewModel(
    private val authState: StateFlow<TvAuthState>,
) : ViewModel() {
    private val lock = Any()
    private var active: TvSession? = null

    init {
        viewModelScope.launch { authState.collect { reconcile() } }
    }

    fun sessionFor(
        account: TvAccount,
        sessionId: TvAuthSessionId,
        dependencies: TvSessionDependencies,
    ): TvSession? =
        synchronized(lock) {
            val key = TvSessionKey(account.userId, sessionId)
            if (authState.value.sessionKey() != key) return@synchronized null
            active?.takeIf { it.key == key }?.let { return@synchronized it }
            active?.close()
            active = null
            val recentSearches = dependencies.recentSearchStore(viewModelScope)
            val session = TvSession(
                key = key,
                files = FilesBrowserController(dependencies.filesRepository, viewModelScope),
                search = SearchController(dependencies.searchRepository, recentSearches, viewModelScope),
                history = HistoryController(dependencies.historyRepository, account.historyEnabled, viewModelScope),
                recentSearches = recentSearches,
                filesItemResolver = dependencies.filesItemResolver,
                parentScope = viewModelScope,
            )
            if (authState.value.sessionKey() != key) {
                session.close()
                null
            } else {
                session.also { active = it }
            }
        }

    override fun onCleared() {
        synchronized(lock) {
            active?.close()
            active = null
        }
    }

    private fun reconcile() {
        synchronized(lock) {
            val current = active ?: return
            if (current.key != authState.value.sessionKey()) {
                current.close()
                active = null
            }
        }
    }

    private fun TvAuthState.sessionKey(): TvSessionKey? =
        (this as? TvAuthState.SignedIn)?.let { TvSessionKey(it.account.userId, it.sessionId) }
}

internal fun tvSessionViewModelFactory(authState: StateFlow<TvAuthState>): ViewModelProvider.Factory =
    viewModelFactory { initializer { TvSessionViewModel(authState) } }
