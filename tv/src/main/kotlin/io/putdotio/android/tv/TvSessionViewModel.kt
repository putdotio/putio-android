package io.putdotio.android.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.files.ContinueWatching
import io.putdotio.android.files.ContinueWatchingEntry
import io.putdotio.android.files.ContinueWatchingStore
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesMoveOutcome
import io.putdotio.android.files.FilesMoveStatus
import io.putdotio.android.tv.files.TvFilesEdits
import io.putdotio.android.tv.files.isConfirmedTrash
import io.putdotio.android.tv.files.TvItemChanges
import io.putdotio.android.tv.files.TvItemThumbnails
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesOpenOrigin
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.FilesStreamUrlResult
import io.putdotio.android.files.FilesStreamUrls
import io.putdotio.android.files.FilesSubtitleLanguages
import io.putdotio.android.files.FilesWatchedRepository
import io.putdotio.android.files.PinnedFiles
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.confirmedGone
import io.putdotio.android.tv.home.TvHomePins
import io.putdotio.android.tv.home.TvHomeReturn
import io.putdotio.android.tv.home.TvMemoryContinueWatchingStore
import io.putdotio.android.tv.home.TvNoPinnedFiles
import io.putdotio.android.tv.home.TvShowHomeSetting
import io.putdotio.android.tv.home.TvShowHomeStore
import io.putdotio.android.history.HistoryController
import io.putdotio.android.history.HistoryFileOpener
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.tv.history.TvHistoryFiles
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.captureSavedAppConfig
import io.putdotio.android.settings.captureSavedSettings
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.SearchFolderNames
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.session.SessionScopedHolder
import io.putdotio.android.settings.AccountSettingsController
import io.putdotio.android.settings.AccountSettingsRepository
import io.putdotio.android.settings.AndroidAppConfigController
import io.putdotio.android.settings.AndroidAppConfigRepository
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.android.settings.diagnosticsAllowed
import io.putdotio.android.settings.productAnalyticsAnswers
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.player.TvPlaybackReporting
import io.putdotio.android.tv.watchnext.TvWatchNextPlayback
import io.putdotio.android.tv.watchnext.TvWatchNextRecorder
import io.putdotio.android.tv.watchnext.toWatchNextMedia
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** What one signed-in TV session needs to build its controllers. */
internal data class TvSessionDependencies(
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
    /** Resolves playable sources; reads this app's playback method at each resolution. */
    val playbackRepository: (appConfig: () -> AndroidAppConfigState) -> PlaybackRepository,
    /** Saves a media file's playback position (`start_from`), as mobile's reporting does. */
    val writePlaybackPosition: suspend (fileId: Long, seconds: Double) -> PlaybackRepositoryResult<Unit>,
    /** The launcher's Watch Next row, as one signed-in session reports to it. */
    val watchNext: (userId: Long, sessionId: TvAuthSessionId) -> TvWatchNextRecorder =
        { _, _ -> TvWatchNextRecorder.None },
    val folderCounts: FilesFolderCounts? = null,
    val subtitleLanguages: FilesSubtitleLanguages? = null,
    val pinnedFiles: (CoroutineScope) -> PinnedFiles = { TvNoPinnedFiles },
    val continueWatchingStore: (userId: Long) -> ContinueWatchingStore = { TvMemoryContinueWatchingStore() },
    val showHomeStore: (userId: Long) -> TvShowHomeStore = { TvShowHomeStore.Memory() },
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
    dependencies: TvSessionDependencies,
    historyEnabled: Boolean,
    sessionCurrent: () -> Boolean,
    parentScope: CoroutineScope,
    private val watchNext: TvWatchNextRecorder = TvWatchNextRecorder.None,
    /** The signed-in account, which product analytics identifies; null in tests. */
    accountId: Long? = null,
) {
    private val recentSearches = dependencies.recentSearchStore(parentScope)
    val continueWatching = ContinueWatching(dependencies.continueWatchingStore(accountId ?: 0L))
    val pins: PinnedFiles = dependencies.pinnedFiles(parentScope)
    val showHome = TvShowHomeSetting(dependencies.showHomeStore(accountId ?: 0L))
    val files = FilesBrowserController(dependencies.filesRepository, parentScope)
    val search = SearchController(dependencies.searchRepository, recentSearches, parentScope)
    val history = HistoryController(dependencies.historyRepository, historyEnabled, parentScope)
    val trash = TrashController(dependencies.trashRepository, parentScope)

    /** Account-wide `/account/settings`; shared with mobile, read once per session. */
    val settings = AccountSettingsController(dependencies.settingsRepository, parentScope)

    /** This app's `/config` playback keys; shared with mobile. */
    val appConfig = AndroidAppConfigController(dependencies.appConfigRepository, parentScope)
    private val filesRepository = dependencies.filesRepository
    val folderCounts = dependencies.folderCounts
    val subtitleLanguages = dependencies.subtitleLanguages
    private val filesItemResolver = dependencies.filesItemResolver
    private val watchedRepository = dependencies.watchedRepository
    private val streamUrls = dependencies.streamUrls
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val historyOpenChannel = Channel<FilesItem>(Channel.BUFFERED)
    private val historyOpener = HistoryFileOpener(
        history.navigation,
        dependencies.filesItemResolver,
        { item, _ -> historyOpenChannel.send(item) },
        scope,
    )
    private val fileActionFailures = TvActionFailure()
    private val watchedJobs = mutableMapOf<FilesItemId, Job>()
    private val handledBrowserEdits = mutableSetOf<FilesRequestId>()

    private val watchNextHandledDeletes = mutableSetOf<FilesRequestId>()
    private val playbackRepository = dependencies.playbackRepository { appConfig.state.value }
    private val mutablePlayback = MutableStateFlow<PlaybackController?>(null)
    /** The item that started playback; autoplay stays within its folder. */
    private var playbackStart: FilesItem? = null
    private var durationLookup: Job? = null
    private var continueSaved: Job? = null
    private var continueWatchingFollow: Job? = null
    private var playingFromHome = false
    val homeReturn = TvHomeReturn()

    /** Pairs saved positions with the videos they belong to, for Watch Next. */
    internal val watchNextPlayback = TvWatchNextPlayback(watchNext, scope)

    val trashUndo: TvTrashUndo = TvTrashUndo(
        dependencies.trashRepository,
        files,
        scope,
        onUndone = { itemEditEffects.undone(it) },
    )

    val trashUndoOffer = TvTrashUndoOffer(scope, trashUndo::undo)

    val homePins = TvHomePins(pins, dependencies.filesItemResolver, dependencies.folderCounts, scope)

    val searchFolderNames = SearchFolderNames(dependencies.filesItemResolver, search.state, scope)

    internal val positionSaved: (fileId: Long, seconds: Double) -> Unit = { fileId, seconds ->
        files.dispatch(FilesBrowserEvent.PlaybackPositionReported(FilesItemId(fileId), seconds))
        search.positionSaved(FilesItemId(fileId), seconds)
        watchNextPlayback.saved(fileId, seconds)
        continueWatching.positionSaved(FilesItemId(fileId), seconds)
    }

    /** Start-from write-back for this session's playback; see [TvPlaybackReporting]. */
    val playbackReporting = TvPlaybackReporting(
        scope = scope,
        settings = settings.state,
        sessionCurrent = sessionCurrent,
        write = dependencies.writePlaybackPosition,
        onSaved = positionSaved,
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

    val historyFiles = TvHistoryFiles(dependencies.filesItemResolver)

    private val itemEditEffects: TvItemEditEffects = TvItemEditEffects(
        search,
        files,
        historyFiles,
        homePins,
        refreshTrash = { trash.refreshIfLoaded() },
        onGone = { item, outcome ->
            watchNext.fileGone(item.id.value)
            continueWatching.removed(listOf(outcome))
            if (outcome.isConfirmedTrash) trashUndoOffer.offer(outcome)
        },
    )

    val itemThumbnails = TvItemThumbnails(::originalStreamUrl)

    val filesEdits = TvFilesEdits(
        repository = filesRepository,
        scope = scope,
        changes = TvItemChanges(
            onUpdated = itemEditEffects::edited,
            onRemoved = itemEditEffects::removed,
            onSessionRejected = fileActionFailures::report,
        ),
    )

    val recentSearchFailure = recentSearches.failure

    /** A history event's file, resolved and ready for Files to open. */
    val historyOpens: Flow<FilesItem> = historyOpenChannel.receiveAsFlow()

    /** Why the last history row could not be resolved; cleared by the next success or dismissal. */
    val historyOpenFailure: StateFlow<PutioFailure?> = historyOpener.failure

    /** Why the last watched toggle failed; cleared by the next attempt or dismissal. */
    val fileActionFailure: StateFlow<PutioFailure?> = fileActionFailures.failure

    /** Files that system search and Watch Next cards ask to open. */
    val links = TvLinkOpener(
        resolver = filesItemResolver,
        scope = scope,
        open = { item -> openExternal(item, FilesOpenOrigin.LINK) },
        onCardGone = watchNext::fileGone,
    )

    init {
        // A card whose file put.io no longer has, or that another device finished, leaves the row.
        scope.launch {
            watchNext.reconcile(filesItemResolver::resolveItem)?.let(fileActionFailures::report)
        }
        scope.launch {
            continueWatching.reconcile(filesItemResolver::resolveItem)?.let(fileActionFailures::report)
        }
        scope.launch {
            files.state
                .map { state -> state.stack.mapNotNull { it.deleteOutcome?.takeIf(::confirmsAnyGone) } }
                .distinctUntilChanged()
                .collect { deletes ->
                    continueWatching.removed(deletes)
                    deletes.filter { watchNextHandledDeletes.add(it.requestId) }
                        .flatMap { it.confirmedGone() }
                        .forEach {
                            watchNext.fileGone(it.value)
                            homePins.itemRemoved(it)
                        }
                }
        }
        scope.launch {
            files.state
                .map { state -> state.stack.mapNotNull { it.deleteOutcome?.takeIf(::movesAnyToTrash) } }
                .distinctUntilChanged()
                .collect { moved -> if (moved.isNotEmpty()) trash.refreshIfLoaded() }
        }
        scope.launch {
            files.state
                .map { state -> state.stack.mapNotNull { it.renameCompletion } }
                .distinctUntilChanged()
                .collect { renames ->
                    renames.filter { handledBrowserEdits.add(it.requestId) }
                        .forEach { itemEditEffects.renamedInFiles(it.intent.itemId, it.intent.name) }
                }
        }
        scope.launch {
            files.state
                .map { state -> state.stack.mapNotNull { it.moveOutcome?.takeIf { move -> move.moved } } }
                .distinctUntilChanged()
                .collect { moves ->
                    moves.filter { handledBrowserEdits.add(it.requestId) }.forEach { move ->
                        move.intent.itemIds.forEach { itemEditEffects.movedInFiles(it, move.intent.destinationId) }
                    }
                }
        }
        scope.launch {
            continueWatching.recheckAfterDeletes(filesItemResolver::resolveItem)?.let {
                fileActionFailures.report(it)
            }
        }
        scope.launch { settings.state.captureSavedSettings() }
        scope.launch { appConfig.state.captureSavedAppConfig() }
        scope.launch {
            settings.state.collect { state -> state.diagnosticsAllowed()?.let(PutioDiagnostics::update) }
        }
        if (accountId != null) {
            scope.launch { settings.state.productAnalyticsAnswers().collect { ProductAnalytics.update(accountId, it) } }
        }
    }

    fun retryRecentSearches() = recentSearches.retry()

    /**
     * The file playing full-screen, or null while the shell shows. It lives here so playback
     * survives configuration changes and ends with the session.
     */
    val playback: StateFlow<PlaybackController?> = mutablePlayback.asStateFlow()

    /** The video after a target in its folder, by autoplay's walk; Next episode offers it. */
    val findNextVideo: suspend (PlaybackTarget) -> PlaybackNextResult = playbackRepository::findNextVideo

    val findNeighbours: suspend (PlaybackTarget) -> PlaybackRepositoryResult<PlaybackNeighbours> =
        playbackRepository::findNeighbours

    val findSubtitles: suspend (PlaybackTarget) -> PlaybackRepositoryResult<List<PlaybackSubtitle>> =
        playbackRepository::listSubtitles

    fun play(item: FilesItem, start: TvPlayStart = TvPlayStart.Resume, method: StreamingMethod? = null) {
        durationLookup?.cancel()
        startPlayback(item, start, fromHome = false, method)
    }

    /**
     * Opens a pick from [origin]: media plays unless [playMedia] is false, a folder opens in Files,
     * and any other file opens its folder with focus on it. Back returns to [origin] either way.
     */
    fun openExternal(
        item: FilesItem,
        origin: FilesOpenOrigin,
        playMedia: Boolean = true,
    ): TvExternalOpen {
        // A newer pick wins over media still waiting for its duration.
        durationLookup?.cancel()
        return when {
            item.isPlayable && playMedia -> {
                val start = TvPlayStart.Resume
                val fromHome = origin == FilesOpenOrigin.HOME
                if (item.playback?.durationSeconds != null) {
                    startPlayback(item, start, fromHome)
                } else {
                    durationLookup = scope.launch {
                        startPlayback(filesRepository.withListedDuration(item), start, fromHome)
                    }
                }
                TvExternalOpen.PLAYING
            }
            !files.dispatch(FilesBrowserEvent.OpenExternalItem(item, origin)) -> TvExternalOpen.REFUSED
            else -> {
                if (!item.isFolder) item.parentId?.let { filesFocusMemory[it.value] = item.id.value }
                homeReturn.opened(item.id.takeIf { origin == FilesOpenOrigin.HOME })
                TvExternalOpen.IN_FILES
            }
        }
    }

    private fun startPlayback(
        item: FilesItem,
        start: TvPlayStart,
        fromHome: Boolean,
        method: StreamingMethod? = null,
    ) {
        val mediaType = PlaybackMediaType.fromFileType(item.type) ?: return
        if (!scope.isActive) return
        playingFromHome = fromHome
        val target = PlaybackTarget(
            item.id,
            item.name,
            mediaType,
            item.playback?.durationSeconds,
            item.screenshotUrl,
            method,
        )
        val controller = PlaybackController(target, playbackRepository, scope, analytics = true)
        playbackReporting.startPlayback()
        mutablePlayback.getAndUpdate { controller }?.close()
        playbackStart = item
        watchNextPlayback.follow(controller)
        continueWatchingFollow?.cancel()
        continueWatchingFollow = scope.launch {
            controller.state.map { it.target }.distinctUntilChanged().collect { target ->
                if (target.mediaType == PlaybackMediaType.VIDEO) {
                    continueWatching.opened(target.fileId, target.name, target.durationSeconds)
                }
            }
        }
        continueSaved?.cancel()
        continueSaved = scope.launch { controller.answerResumePrompt(start.answer) }
    }

    /**
     * Leaves playback; the shell shows again. After autoplay moved on, Files focuses the row of
     * the video that played last rather than the one that started, reading later pages when that
     * row is not loaded yet.
     */
    fun stopPlayback() {
        durationLookup?.cancel()
        continueSaved?.cancel()
        watchNextPlayback.stopFollowing()
        continueWatchingFollow?.cancel()
        val stopped = mutablePlayback.value ?: return
        val last = stopped.state.value.target.fileId
        if (playingFromHome) homeReturn.played(last)
        mutablePlayback.value = null
        val start = playbackStart
        val folder = start?.parentId
        if (folder != null && last != start.id) {
            filesFocusMemory[folder.value] = last.value
            files.dispatch(FilesBrowserEvent.RevealItem(folder, last))
        }
        stopped.close()
    }

    fun setWatched(item: FilesItem, watched: Boolean) {
        val seconds = if (watched) item.playback?.durationSeconds ?: return else 0.0
        fileActionFailures.dismiss()
        watchedJobs.remove(item.id)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = playbackReporting.supersede(item.id.value) {
                if (watched) {
                    watchedRepository.setPosition(item.id, seconds)
                } else {
                    watchedRepository.clearPosition(item.id)
                }
            }
            if (!isActive || watchedJobs[item.id] !== coroutineContext[Job]) return@launch
            when (result) {
                is PutioResult.Success -> {
                    files.dispatch(FilesBrowserEvent.PlaybackPositionReported(item.id, seconds))
                    search.positionSaved(item.id, seconds)
                    watchNext.positionSaved(item.toWatchNextMedia(), seconds)
                    continueWatching.positionSaved(item.id, seconds)
                }
                is PutioResult.Failure -> fileActionFailures.report(result.failure)
            }
        }
        watchedJobs[item.id] = job
        job.invokeOnCompletion { if (watchedJobs[item.id] === job) watchedJobs.remove(item.id) }
        job.start()
    }

    /**
     * The original file's URL for an external player, carrying the account's download token.
     * A 401 is recorded as the session verdict; the caller explains every other failure.
     */
    suspend fun originalStreamUrl(id: FilesItemId): FilesStreamUrlResult =
        streamUrls.originalStreamUrl(id).also { result ->
            val failure = (result as? FilesStreamUrlResult.Failure)?.failure
            if (failure is PutioFailure.AuthenticationRequired) fileActionFailures.report(failure)
        }

    /** Drops the explanation the pane showed; a 401 stays, since it is a session verdict. */
    fun dismissFileActionFailure() = fileActionFailures.dismiss()

    /** Drops the explanation the pane showed; a 401 stays, since it is a session verdict. */
    fun dismissHistoryOpenFailure() = historyOpener.dismissFailure()

    internal fun close() {
        stopPlayback()
        playbackReporting.close()
        scope.cancel()
        historyOpener.close()
        historyOpenChannel.close()
        links.close()
        searchFolderNames.close()
        search.close()
        recentSearches.close()
        pins.close()
        continueWatching.close()
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
    private val buildContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    private val active = SessionScopedHolder<TvAuthState, TvSessionKey, TvSession>(
        authState = authState,
        keyOf = { it.sessionKey() },
        scope = viewModelScope,
        close = TvSession::close,
    )
    private val mutablePrepared = MutableStateFlow<TvSessionKey?>(null)
    private var preparing: TvSessionKey? = null

    /** The session [prepare] last finished building; [sessionFor] then returns it without building. */
    val prepared: StateFlow<TvSessionKey?> = mutablePrepared.asStateFlow()

    /** Builds the session's controllers on [buildContext], so no frame waits for them or their disk reads. */
    fun prepare(
        account: TvAccount,
        sessionId: TvAuthSessionId,
        dependencies: TvSessionDependencies,
    ) {
        val key = TvSessionKey(account.userId, sessionId)
        if (preparing == key) return
        preparing = key
        viewModelScope.launch {
            val session = withContext(buildContext) { newSession(account, key, dependencies) }
            var adopted = false
            val held = active.valueFor(key) { session.also { adopted = true } }
            if (!adopted) session.close()
            if (held != null) mutablePrepared.value = key
        }
    }

    fun sessionFor(
        account: TvAccount,
        sessionId: TvAuthSessionId,
        dependencies: TvSessionDependencies,
    ): TvSession? {
        val key = TvSessionKey(account.userId, sessionId)
        return active.valueFor(key) { newSession(account, key, dependencies) }
    }

    private fun newSession(account: TvAccount, key: TvSessionKey, dependencies: TvSessionDependencies) =
        TvSession(
            dependencies = dependencies,
            historyEnabled = account.historyEnabled,
            sessionCurrent = { authState.value.sessionKey() == key },
            parentScope = viewModelScope,
            watchNext = dependencies.watchNext(key.userId, key.sessionId),
            accountId = key.userId,
        )

    override fun onCleared() {
        active.clear()
    }

    private fun TvAuthState.sessionKey(): TvSessionKey? =
        (this as? TvAuthState.SignedIn)?.let { TvSessionKey(it.account.userId, it.sessionId) }
}

internal enum class TvPlayStart(val answer: PlaybackEvent) {
    Resume(PlaybackEvent.Resume),

    StartOver(PlaybackEvent.Restart),
}

private suspend fun PlaybackController.answerResumePrompt(answer: PlaybackEvent) {
    val settled = state.map { it.content }
        .first { it !is PlaybackContent.Loading && it !is PlaybackContent.Conversion }
    if (settled is PlaybackContent.AwaitingResume) dispatch(answer)
}

// A position within 10 s of the end starts over, which needs the duration that search results
// and single-file reads omit; listing the file itself returns it as the parent. Without one,
// playback continues from the saved position.
private suspend fun FilesRepository.withListedDuration(item: FilesItem): FilesItem {
    val listed = (loadFolder(item.id) as? PutioResult.Success)?.value?.parent
    val duration = listed?.takeIf { it.id == item.id }?.playback?.durationSeconds
    val progress = duration?.let { FilesPlaybackProgress(item.playback?.startFromSeconds ?: 0.0, it) }
    return if (progress == null) item else item.copy(playback = progress)
}

internal fun TvSession.removeFromContinueWatching(entry: ContinueWatchingEntry) {
    continueWatching.removed(entry.fileId)
    watchNextPlayback.hidden(entry.fileId.value)
}

internal fun tvSessionViewModelFactory(authState: StateFlow<TvAuthState>): ViewModelProvider.Factory =
    viewModelFactory { initializer { TvSessionViewModel(authState) } }

internal class TvActionFailure {
    private val mutableFailure = MutableStateFlow<PutioFailure?>(null)
    val failure: StateFlow<PutioFailure?> = mutableFailure.asStateFlow()

    fun report(failure: PutioFailure) {
        mutableFailure.update { current -> if (current is PutioFailure.AuthenticationRequired) current else failure }
    }

    fun dismiss() {
        mutableFailure.update { it?.takeIf { failure -> failure is PutioFailure.AuthenticationRequired } }
    }
}

private fun confirmsAnyGone(outcome: FilesDeleteOutcome): Boolean = outcome.confirmedGone().isNotEmpty()

private fun TrashController.refreshIfLoaded() {
    if (state.value.content is TrashContent.Loaded) dispatch(TrashEvent.Refresh)
}

private fun movesAnyToTrash(outcome: FilesDeleteOutcome): Boolean =
    outcome.intent.mode == FilesDeleteMode.TRASH && confirmsAnyGone(outcome)

private val FilesMoveOutcome.moved: Boolean get() = status == FilesMoveStatus.MOVED
