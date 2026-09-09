package io.putdotio.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.history.HistoryController
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.SearchOutput
import io.putdotio.android.search.SearchRepository
import io.putdotio.sdk.PutioClient
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

internal class MobileSearchHistoryViewModel(
    application: Application,
    private val authState: StateFlow<MobileAuthState>,
    private val recentSearchStoreFactory: (PutioClient, CoroutineScope) -> MobileRecentSearchStoreOwner =
        { client, scope -> MobileRecentSearchStore(client, scope) },
) : AndroidViewModel(application) {
    private val lock = Any()
    private var activeSession: ActiveSearchHistorySession? = null

    init {
        viewModelScope.launch {
            authState.collect { reconcileActiveSession() }
        }
    }

    fun controllersFor(
        session: MobileAuthState.SignedIn,
        putioClient: PutioClient,
        searchRepository: SearchRepository,
        historyRepository: HistoryRepository,
        filesItemResolver: FilesItemResolver,
    ): ActiveSearchHistorySession? =
        synchronized(lock) {
            val key = SessionKey(session.account.userId, session.sessionId)
            if (authState.value.sessionKey() != key) return@synchronized null
            activeSession?.takeIf { it.key == key }?.let { return@synchronized it }

            activeSession?.close()
            activeSession = null
            if (authState.value.sessionKey() != key) return@synchronized null

            val recentSearchStore = recentSearchStoreFactory(putioClient, viewModelScope)
            val session =
                ActiveSearchHistorySession(
                    key = key,
                    recentSearchStore = recentSearchStore,
                    search =
                        SearchController(
                            repository = searchRepository,
                            recentSearchStore = recentSearchStore,
                            parentScope = viewModelScope,
                        ),
                    history =
                        HistoryController(
                            repository = historyRepository,
                            historyEnabled = session.account.historyEnabled,
                            parentScope = viewModelScope,
                        ),
                    parentScope = viewModelScope,
                    filesItemResolver = filesItemResolver,
                )
            if (authState.value.sessionKey() != key) {
                session.close()
                null
            } else {
                activeSession = session
                session
            }
        }

    override fun onCleared() {
        clearActiveSession()
    }

    private fun reconcileActiveSession() {
        synchronized(lock) {
            val active = activeSession ?: return
            if (active.key != authState.value.sessionKey()) {
                active.close()
                activeSession = null
            }
        }
    }

    private fun clearActiveSession() {
        synchronized(lock) {
            activeSession?.close()
            activeSession = null
        }
    }

    private fun MobileAuthState.sessionKey(): SessionKey? =
        (this as? MobileAuthState.SignedIn)?.let { SessionKey(it.account.userId, it.sessionId) }
}

internal class ActiveSearchHistorySession(
    internal val key: SessionKey,
    private val recentSearchStore: MobileRecentSearchStoreOwner,
    val search: SearchController,
    val history: HistoryController,
    parentScope: CoroutineScope,
    private val filesItemResolver: FilesItemResolver,
) {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val navigationChannel = Channel<FilesItem>(Channel.BUFFERED)
    private val mutableNavigationFailure = MutableStateFlow<FilesFailure?>(null)

    val navigation: Flow<FilesItem> = navigationChannel.receiveAsFlow()
    val navigationFailure: StateFlow<FilesFailure?> = mutableNavigationFailure.asStateFlow()
    val recentSearchFailure: StateFlow<FilesFailure?> = recentSearchStore.failure

    init {
        scope.launch {
            search.outputs.collect { output ->
                when (output) {
                    is SearchOutput.OpenResult -> navigationChannel.send(output.item)
                }
            }
        }
        scope.launch {
            history.navigation.collect { output ->
                when (val result = filesItemResolver.resolveItem(FilesItemId(output.fileId.value))) {
                    is FilesRepositoryResult.Success -> {
                        mutableNavigationFailure.value = null
                        navigationChannel.send(result.value)
                    }
                    is FilesRepositoryResult.Failure -> mutableNavigationFailure.value = result.failure
                }
            }
        }
    }

    fun dismissNavigationFailure() {
        mutableNavigationFailure.value = null
    }

    /** A product link names a file; resolve it like a history row so Files opens its folder. */
    /** Resolves a linked item and navigates to it; the caller's scope bounds the resolve. */
    suspend fun openFile(fileId: FilesItemId) {
        when (val result = filesItemResolver.resolveItem(fileId)) {
            is FilesRepositoryResult.Success -> {
                mutableNavigationFailure.value = null
                navigationChannel.send(result.value)
            }
            is FilesRepositoryResult.Failure -> mutableNavigationFailure.value = result.failure
        }
    }

    fun retryRecentSearches() {
        recentSearchStore.retry()
    }

    fun close() {
        scope.cancel()
        navigationChannel.close()
        search.close()
        history.close()
        recentSearchStore.close()
    }
}

internal data class SessionKey(
    val userId: Long,
    val sessionId: MobileAuthSessionId,
)

internal fun mobileSearchHistoryViewModelFactory(
    application: Application,
    authState: StateFlow<MobileAuthState>,
    recentSearchStoreFactory: (PutioClient, CoroutineScope) -> MobileRecentSearchStoreOwner =
        { client, scope -> MobileRecentSearchStore(client, scope) },
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            MobileSearchHistoryViewModel(application, authState, recentSearchStoreFactory)
        }
    }
