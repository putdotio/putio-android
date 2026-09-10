package io.putdotio.android.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What one signed-in TV session needs to build its controllers. */
internal class TvSessionDependencies(
    val filesRepository: FilesRepository,
    val searchRepository: SearchRepository,
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
    private val recentSearches: RecentSearchStoreOwner,
) {
    /**
     * Which Files row last held D-pad focus in each folder. It lives here, not in the pane,
     * because the pane is disposed whenever another destination is shown and must come
     * back to the same row. Plain, not snapshot state: a focus move must not recompose.
     */
    val filesFocusMemory: MutableMap<Long, Long> = mutableMapOf()

    val recentSearchFailure = recentSearches.failure

    fun retryRecentSearches() = recentSearches.retry()

    internal fun close() {
        search.close()
        recentSearches.close()
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
        userId: Long,
        sessionId: TvAuthSessionId,
        dependencies: TvSessionDependencies,
    ): TvSession? =
        synchronized(lock) {
            val key = TvSessionKey(userId, sessionId)
            if (authState.value.sessionKey() != key) return@synchronized null
            active?.takeIf { it.key == key }?.let { return@synchronized it }
            active?.close()
            active = null
            val recentSearches = dependencies.recentSearchStore(viewModelScope)
            val session = TvSession(
                key = key,
                files = FilesBrowserController(dependencies.filesRepository, viewModelScope),
                search = SearchController(dependencies.searchRepository, recentSearches, viewModelScope),
                recentSearches = recentSearches,
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
