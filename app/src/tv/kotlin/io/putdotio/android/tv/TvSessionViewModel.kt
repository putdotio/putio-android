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
import io.putdotio.android.files.FilesOpenOrigin
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesStreamUrls
import io.putdotio.android.files.FilesWatchedRepository
import io.putdotio.android.history.HistoryController
import io.putdotio.android.history.HistoryFileOpener
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.playbackPreference
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.session.SessionScopedHolder
import io.putdotio.android.settings.AccountSettingsController
import io.putdotio.android.settings.AccountSettingsRepository
import io.putdotio.android.settings.AndroidAppConfigController
import io.putdotio.android.settings.AndroidAppConfigRepository
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.player.TvPlaybackReporting
import io.putdotio.sdk.files.PlaybackPreference
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
import kotlinx.coroutines.flow.getAndUpdate
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
    /** Resolves playable sources; reads the account's HLS/MP4 choice at each resolution. */
    val playbackRepository: (preference: () -> PlaybackPreference) -> PlaybackRepository,
    /** Saves a media file's playback position (`start_from`), as mobile's reporting does. */
    val writePlaybackPosition: suspend (fileId: Long, seconds: Double) -> PlaybackRepositoryResult<Unit>,
)

/** What choosing a Search or History row did. */
internal enum class TvExternalOpen {
    /** Media plays over the pane it was chosen on. */
    PLAYING,

    /** Files shows the folder, or the file's folder with focus on it. */
    IN_FILES,

    /** Files refused while a move or deletion settles. */
    REFUSED,
}

/**
 * The controllers of one signed-in session. They survive navigation and configuration
 * changes together and are closed together when the session ends.
 */
internal class TvSession internal constructor(
    val files: FilesBrowserController,
    private val filesRepository: FilesRepository,
    val search: SearchController,
    val history: HistoryController,
    val trash: TrashController,
    /** Account-wide `/account/settings`; shared with mobile, read once per session. */
    val settings: AccountSettingsController,
    /** This app's `/config` playback keys; shared with mobile. */
    val appConfig: AndroidAppConfigController,
    private val recentSearches: RecentSearchStoreOwner,
    filesItemResolver: FilesItemResolver,
    private val watchedRepository: FilesWatchedRepository,
    private val streamUrls: FilesStreamUrls,
    playbackRepositoryFor: (preference: () -> PlaybackPreference) -> PlaybackRepository,
    writePlaybackPosition: suspend (fileId: Long, seconds: Double) -> PlaybackRepositoryResult<Unit>,
    sessionCurrent: () -> Boolean,
    parentScope: CoroutineScope,
) {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val historyOpenChannel = Channel<FilesItem>(Channel.BUFFERED)
    private val historyOpener =
        HistoryFileOpener(history.navigation, filesItemResolver, { item, _ -> historyOpenChannel.send(item) }, scope)
    private val mutableFileActionFailure = MutableStateFlow<FilesFailure?>(null)
    private val watchedJobs = mutableMapOf<FilesItemId, Job>()
    private val playbackRepository = playbackRepositoryFor { appConfig.state.value.playbackPreference() }
    private val mutablePlayback = MutableStateFlow<PlaybackController?>(null)
    private var durationLookup: Job? = null

    /** Start-from write-back for this session's playback; see [TvPlaybackReporting]. */
    val playbackReporting = TvPlaybackReporting(
        scope = scope,
        settings = settings.state,
        sessionCurrent = sessionCurrent,
        write = writePlaybackPosition,
        // The Files row shows the saved position once the server has it.
        onSaved = { fileId, seconds ->
            files.dispatch(FilesBrowserEvent.PlaybackPositionReported(FilesItemId(fileId), seconds))
        },
    )

    /**
     * Which Files row last held D-pad focus in each folder. It lives here, not in the pane,
     * because the pane is disposed whenever another destination is shown and must come
     * back to the same row. Plain, not snapshot state: a focus move must not recompose.
     */
    val filesFocusMemory: MutableMap<Long, Long> = mutableMapOf()

    /** The Search result and the History event last opened, focused again when their pane returns. */
    val searchPickedRow = TvPickedRow()
    val historyPickedRow = TvPickedRow()

    val recentSearchFailure = recentSearches.failure

    /** A history event's file, resolved and ready for Files to open. */
    val historyOpens: Flow<FilesItem> = historyOpenChannel.receiveAsFlow()

    /** Why the last history row could not be resolved; cleared by the next success or dismissal. */
    val historyOpenFailure: StateFlow<FilesFailure?> = historyOpener.failure

    /** Why the last watched toggle failed; cleared by the next attempt or dismissal. */
    val fileActionFailure: StateFlow<FilesFailure?> = mutableFileActionFailure.asStateFlow()

    fun retryRecentSearches() = recentSearches.retry()

    /**
     * The file playing full-screen, or null while the shell shows. It lives here so playback
     * survives configuration changes and ends with the session.
     */
    val playback: StateFlow<PlaybackController?> = mutablePlayback.asStateFlow()

    /** Starts resolving [item] for playback, replacing whatever was playing. */
    fun play(item: FilesItem) {
        durationLookup?.cancel()
        startPlayback(item)
    }

    /**
     * Opens a Search or History pick: media plays, a folder opens in Files, and any other file
     * opens its folder with focus on it. Back returns to [origin] either way.
     */
    fun openExternal(item: FilesItem, origin: FilesOpenOrigin): TvExternalOpen {
        if (item.isPlayable) {
            playWithDuration(item)
            return TvExternalOpen.PLAYING
        }
        if (!files.dispatch(FilesBrowserEvent.OpenExternalItem(item, origin))) return TvExternalOpen.REFUSED
        if (!item.isFolder) item.parentId?.let { filesFocusMemory[it.value] = item.id.value }
        return TvExternalOpen.IN_FILES
    }

    // The resume dialog needs the duration, which search results and single-file reads omit;
    // listing the file itself returns it as the parent. Without one, playback continues from
    // the saved position without asking, as for any row without a duration.
    private fun playWithDuration(item: FilesItem) {
        durationLookup?.cancel()
        if (item.playback?.durationSeconds != null) {
            startPlayback(item)
            return
        }
        durationLookup = scope.launch {
            val listed = (filesRepository.loadFolder(item.id) as? FilesRepositoryResult.Success)?.value?.parent
            val duration = listed?.takeIf { it.id == item.id }?.playback?.durationSeconds
            val progress = duration?.let { FilesPlaybackProgress(item.playback?.startFromSeconds ?: 0.0, it) }
            startPlayback(if (progress == null) item else item.copy(playback = progress))
        }
    }

    private fun startPlayback(item: FilesItem) {
        val mediaType = PlaybackMediaType.fromFileType(item.type) ?: return
        if (!scope.isActive) return
        val target = PlaybackTarget(item.id, item.name, mediaType, item.playback?.durationSeconds)
        val controller = PlaybackController(target, playbackRepository, scope)
        playbackReporting.startPlayback()
        mutablePlayback.getAndUpdate { controller }?.close()
    }

    /** Leaves playback; the shell shows again. */
    fun stopPlayback() {
        durationLookup?.cancel()
        mutablePlayback.getAndUpdate { null }?.close()
    }

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
    fun dismissHistoryOpenFailure() = historyOpener.dismissFailure()

    internal fun close() {
        stopPlayback()
        playbackReporting.close()
        scope.cancel()
        historyOpener.close()
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
    private val active = SessionScopedHolder<TvAuthState, TvSessionKey, TvSession>(
        authState = authState,
        keyOf = { it.sessionKey() },
        scope = viewModelScope,
        close = TvSession::close,
    )

    fun sessionFor(
        account: TvAccount,
        sessionId: TvAuthSessionId,
        dependencies: TvSessionDependencies,
    ): TvSession? {
        val key = TvSessionKey(account.userId, sessionId)
        return active.valueFor(key) {
            val recentSearches = dependencies.recentSearchStore(viewModelScope)
            TvSession(
                files = FilesBrowserController(dependencies.filesRepository, viewModelScope),
                filesRepository = dependencies.filesRepository,
                search = SearchController(dependencies.searchRepository, recentSearches, viewModelScope),
                history = HistoryController(dependencies.historyRepository, account.historyEnabled, viewModelScope),
                trash = TrashController(dependencies.trashRepository, viewModelScope),
                settings = AccountSettingsController(dependencies.settingsRepository, viewModelScope),
                appConfig = AndroidAppConfigController(dependencies.appConfigRepository, viewModelScope),
                recentSearches = recentSearches,
                filesItemResolver = dependencies.filesItemResolver,
                watchedRepository = dependencies.watchedRepository,
                streamUrls = dependencies.streamUrls,
                playbackRepositoryFor = dependencies.playbackRepository,
                writePlaybackPosition = dependencies.writePlaybackPosition,
                sessionCurrent = { authState.value.sessionKey() == key },
                parentScope = viewModelScope,
            )
        }
    }

    override fun onCleared() {
        active.clear()
    }

    private fun TvAuthState.sessionKey(): TvSessionKey? =
        (this as? TvAuthState.SignedIn)?.let { TvSessionKey(it.account.userId, it.sessionId) }
}

internal fun tvSessionViewModelFactory(authState: StateFlow<TvAuthState>): ViewModelProvider.Factory =
    viewModelFactory { initializer { TvSessionViewModel(authState) } }
