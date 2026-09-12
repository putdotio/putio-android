package io.putdotio.android.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesStreamUrls
import io.putdotio.android.files.FilesWatchedRepository
import io.putdotio.android.history.HistoryController
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.settings.AccountSettingsController
import io.putdotio.android.settings.AccountSettingsRepository
import io.putdotio.android.settings.AndroidAppConfigController
import io.putdotio.android.settings.AndroidAppConfigRepository
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What one signed-in TV session needs to build its controllers. */
internal class TvSessionDependencies(
    val filesRepository: FilesRepository,
    val searchRepository: SearchRepository,
    val historyRepository: HistoryRepository,
    val trashRepository: TrashRepository,
    val settingsRepository: AccountSettingsRepository,
    val appConfigRepository: AndroidAppConfigRepository,
    /** Saves or clears a media file's position, which is what marks it watched. */
    val watchedRepository: FilesWatchedRepository,
    /** Original stream URLs for handing a file to another player. */
    val streamUrls: FilesStreamUrls,
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
    val trash: TrashController,
    /** Account-wide `/account/settings`; shared with mobile, read once per session. */
    val settings: AccountSettingsController,
    /** This app's `/config` playback keys; shared with mobile. */
    val appConfig: AndroidAppConfigController,
    private val recentSearches: RecentSearchStoreOwner,
    private val filesItemResolver: FilesItemResolver,
    private val watchedRepository: FilesWatchedRepository,
    private val streamUrls: FilesStreamUrls,
    parentScope: CoroutineScope,
) {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val historyOpenChannel = Channel<FilesItem>(Channel.BUFFERED)
    private val mutableHistoryOpenFailure = MutableStateFlow<FilesFailure?>(null)
    private val mutableFileActionFailure = MutableStateFlow<FilesFailure?>(null)
    private val watchedJobs = mutableMapOf<FilesItemId, Job>()

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

    /** Why the last watched toggle failed; cleared by the next attempt or dismissal. */
    val fileActionFailure: StateFlow<FilesFailure?> = mutableFileActionFailure.asStateFlow()

    init {
        // Resolved here rather than in the pane so a row chosen just before the pane is
        // disposed still opens, like a search result does. Only the latest choice counts:
        // a second Center while the first still resolves must not open two folders in turn.
        scope.launch {
            history.navigation.collectLatest { request ->
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

    /**
     * Marks a media file watched (its position becomes its duration) or unwatched (no
     * position). Runs here so a choice made just before the pane is disposed still lands;
     * the listing row follows through the browser's own position invalidation.
     */
    fun setWatched(item: FilesItem, watched: Boolean) {
        val seconds = if (watched) item.playback?.durationSeconds ?: return else 0.0
        // One write per file at a time: a newer choice for the same file supersedes an
        // unfinished one, so a slow first request cannot report after a faster second one
        // has settled the row; writes for other files run on their own.
        watchedJobs.remove(item.id)?.cancel()
        // Started lazily so the write is registered before its body can run and compare itself.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // A session verdict from any write stays until the session is rejected.
            mutableFileActionFailure.update { it?.takeIf { failure -> failure is FilesFailure.AuthenticationRequired } }
            val result = if (watched) {
                watchedRepository.setPosition(item.id, seconds)
            } else {
                watchedRepository.clearPosition(item.id)
            }
            // Cancellation is cooperative: a superseded write that still returns must not
            // settle the row or report, so only the write still registered for the file does.
            if (!isActive || watchedJobs[item.id] !== coroutineContext[Job]) return@launch
            when (result) {
                is FilesRepositoryResult.Success ->
                    files.dispatch(FilesBrowserEvent.PlaybackPositionReported(item.id, seconds))
                is FilesRepositoryResult.Failure -> mutableFileActionFailure.update { current ->
                    if (current is FilesFailure.AuthenticationRequired) current else result.failure
                }
            }
        }
        watchedJobs[item.id] = job
        job.invokeOnCompletion { if (watchedJobs[item.id] === job) watchedJobs.remove(item.id) }
        job.start()
    }

    /** The original file's URL for an external player, or null without a session token. */
    fun originalStreamUrl(item: FilesItem): String? = streamUrls.originalStreamUrl(item.id)

    /** Drops the explanation the pane showed; a 401 stays, since it is a session verdict. */
    fun dismissFileActionFailure() {
        mutableFileActionFailure.update { it?.takeIf { failure -> failure is FilesFailure.AuthenticationRequired } }
    }

    /** Drops the explanation the pane showed; a 401 stays, since it is a session verdict. */
    fun dismissHistoryOpenFailure() {
        mutableHistoryOpenFailure.update { it?.takeIf { failure -> failure is FilesFailure.AuthenticationRequired } }
    }

    internal fun close() {
        scope.cancel()
        historyOpenChannel.close()
        search.close()
        recentSearches.close()
        history.close()
        trash.close()
        appConfig.close()
        settings.close()
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
                trash = TrashController(dependencies.trashRepository, viewModelScope),
                settings = AccountSettingsController(dependencies.settingsRepository, viewModelScope),
                appConfig = AndroidAppConfigController(dependencies.appConfigRepository, viewModelScope),
                recentSearches = recentSearches,
                filesItemResolver = dependencies.filesItemResolver,
                watchedRepository = dependencies.watchedRepository,
                streamUrls = dependencies.streamUrls,
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
