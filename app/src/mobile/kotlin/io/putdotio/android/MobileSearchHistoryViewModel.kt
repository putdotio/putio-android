package io.putdotio.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileSessionKey
import io.putdotio.android.auth.sessionKey
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.history.HistoryController
import io.putdotio.android.history.HistoryFileOpener
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.search.AppConfigRecentSearchStore
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.SearchOutput
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.session.SessionScopedHolder
import io.putdotio.sdk.PutioClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal class MobileSearchHistoryViewModel(
    application: Application,
    authState: StateFlow<MobileAuthState>,
    private val recentSearchStoreFactory: (PutioClient, CoroutineScope) -> RecentSearchStoreOwner =
        { client, scope -> AppConfigRecentSearchStore(client, scope) },
) : AndroidViewModel(application) {
    private val activeSession = SessionScopedHolder<MobileAuthState, MobileSessionKey, ActiveSearchHistorySession>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = ActiveSearchHistorySession::close,
    )

    fun controllersFor(
        session: MobileAuthState.SignedIn,
        putioClient: PutioClient,
        searchRepository: SearchRepository,
        historyRepository: HistoryRepository,
        filesItemResolver: FilesItemResolver,
    ): ActiveSearchHistorySession? =
        activeSession.valueFor(MobileSessionKey(session.account.userId, session.sessionId)) {
            val recentSearchStore = recentSearchStoreFactory(putioClient, viewModelScope)
            ActiveSearchHistorySession(
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
        }

    override fun onCleared() {
        activeSession.clear()
    }
}

internal class ActiveSearchHistorySession(
    private val recentSearchStore: RecentSearchStoreOwner,
    val search: SearchController,
    val history: HistoryController,
    parentScope: CoroutineScope,
    filesItemResolver: FilesItemResolver,
) {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val navigationChannel = Channel<FilesItem>(Channel.BUFFERED)
    private val historyOpener = HistoryFileOpener(history.navigation, filesItemResolver, navigationChannel::send, scope)

    val navigation: Flow<FilesItem> = navigationChannel.receiveAsFlow()
    val navigationFailure: StateFlow<FilesFailure?> = historyOpener.failure
    val recentSearchFailure: StateFlow<FilesFailure?> = recentSearchStore.failure

    init {
        scope.launch {
            search.outputs.collect { output ->
                when (output) {
                    is SearchOutput.OpenResult -> navigationChannel.send(output.item)
                }
            }
        }
    }

    fun dismissNavigationFailure() = historyOpener.dismissFailure()

    /** A product link names a file; resolve it like a history row so Files opens its folder. */
    suspend fun openFile(fileId: FilesItemId) = historyOpener.open(fileId)

    fun retryRecentSearches() {
        recentSearchStore.retry()
    }

    fun close() {
        scope.cancel()
        historyOpener.close()
        navigationChannel.close()
        search.close()
        history.close()
        recentSearchStore.close()
    }
}

internal fun mobileSearchHistoryViewModelFactory(
    application: Application,
    authState: StateFlow<MobileAuthState>,
    recentSearchStoreFactory: (PutioClient, CoroutineScope) -> RecentSearchStoreOwner =
        { client, scope -> AppConfigRecentSearchStore(client, scope) },
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            MobileSearchHistoryViewModel(application, authState, recentSearchStoreFactory)
        }
    }
