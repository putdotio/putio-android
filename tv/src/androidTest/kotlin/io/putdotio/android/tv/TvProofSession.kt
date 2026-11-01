package io.putdotio.android.tv

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import android.view.KeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertIsFocused
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.TvSessionShell
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.files.FilesCopyId
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.FilesStreamUrlResult
import io.putdotio.android.files.FilesStreamUrls
import io.putdotio.android.files.FilesWatchedRepository
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPage
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsRepository
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigRepository
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.trash.TrashBulkSelection
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashPage
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/**
 * Fake account repositories for the controlled-state TV proofs: Files serves [listings] and
 * their later pages [continuations], Search answers every term with [searchResults], History
 * and Trash list one page of [history] and [trash], account settings confirm [historyEnabled],
 * and playback resolves to [playback], read only when something plays. Nothing reaches the API.
 */
internal fun tvProofDependencies(
    listings: Map<FilesItemId, FilesPage>,
    searchResults: List<FilesItem>,
    continuations: Map<FilesCursor, FilesPage> = emptyMap(),
    pageDelayMillis: Long = 0L,
    recentSearchStore: (CoroutineScope) -> RecentSearchStoreOwner,
    playback: () -> PlaybackResolution = { error("No playback in this proof") },
    history: List<HistoryItem> = emptyList(),
    trash: List<TrashItem> = emptyList(),
    trashSizeBytes: Long? = null,
    historyEnabled: Boolean = true,
    settings: AccountSettingsPreferences = AccountSettingsPreferences(
        historyEnabled = historyEnabled,
        trashEnabled = true,
        showSubtitles = true,
        autoSelectSubtitles = true,
        resumePlayback = true,
    ),
    appConfig: AndroidAppConfigPreferences = AndroidAppConfigPreferences(),
    tunnelRoutes: List<TunnelRouteOption> = emptyList(),
) = TvSessionDependencies(
    filesRepository = ProofFilesRepository(listings, continuations, pageDelayMillis),
    searchRepository = object : SearchRepository {
        override suspend fun search(term: SearchTerm) =
            PutioResult.Success(SearchPage(searchResults, null, total = searchResults.size))

        override suspend fun loadNextPage(cursor: FilesCursor) = error("One page")
    },
    historyRepository = object : HistoryRepository {
        override suspend fun load(before: HistoryEventId?) =
            PutioResult.Success(HistoryPage(history, hasMore = false))

        override suspend fun clear() = PutioResult.Success(Unit)
    },
    trashRepository = ProofTrashRepository(trash, trashSizeBytes),
    settingsRepository = object : AccountSettingsRepository {
        override suspend fun load() = AccountSettingsRepositoryResult.Success(settings)

        override suspend fun save(change: AccountSettingsChange) = AccountSettingsRepositoryResult.Success(Unit)

        override suspend fun loadTunnelRoutes() = AccountSettingsRepositoryResult.Success(tunnelRoutes)
    },
    appConfigRepository = object : AndroidAppConfigRepository {
        override suspend fun load() = PutioResult.Success(appConfig)

        override suspend fun save(change: AndroidAppConfigChange) = PutioResult.Success(Unit)
    },
    watchedRepository = object : FilesWatchedRepository {
        override suspend fun setPosition(itemId: FilesItemId, seconds: Double) =
            PutioResult.Success(Unit)

        override suspend fun clearPosition(itemId: FilesItemId) = PutioResult.Success(Unit)
    },
    streamUrls = FilesStreamUrls { FilesStreamUrlResult.DownloadTokenUnavailable },
    filesItemResolver = object : FilesItemResolver {
        override suspend fun resolveItem(itemId: FilesItemId) =
            listings.values.flatMap { it.items }.firstOrNull { it.id == itemId }?.let { PutioResult.Success(it) }
                ?: PutioResult.Failure(PutioFailure.Unexpected(IllegalStateException("No item $itemId")))
    },
    recentSearchStore = recentSearchStore,
    playbackRepository = {
        object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget) =
                PlaybackRepositoryResult.Success(playback())

            override suspend fun findNextVideo(target: PlaybackTarget) = error("No next video expected")
        }
    },
    writePlaybackPosition = { _, _ -> PlaybackRepositoryResult.Success(Unit) },
)

internal fun AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>.mountTvProofSession(
    dependencies: TvSessionDependencies,
    onExit: (() -> Unit)? = null,
    account: TvAccount = TvAccount(userId = 1, username = "proof", email = "proof@example.invalid", historyEnabled = true),
    overlay: @Composable BoxScope.() -> Unit = {},
    tab: String? = "Files",
    tunnelRoutes: List<TunnelRouteOption> = emptyList(),
): TvSession {
    val auth = MutableStateFlow<TvAuthState>(TvAuthState.SignedIn(account, TvAuthSessionId(1)))
    lateinit var session: TvSession
    runOnUiThread {
        session = checkNotNull(TvSessionViewModel(auth).sessionFor(account, TvAuthSessionId(1), dependencies))
    }
    setContent {
        if (onExit != null) BackHandler(onBack = onExit)
        PutioTvTheme {
            Box {
                TvSessionShell(
                    session = session,
                    account = account,
                    sessionKey = 1L,
                    onSignOut = {},
                    onSessionRejected = { error("Unexpected rejection") },
                    loadTunnelRoutes = { AccountSettingsRepositoryResult.Success(tunnelRoutes) },
                )
                overlay()
            }
        }
    }
    tab?.let(::openTvTab)
    return session
}

internal fun SemanticsNodeInteractionsProvider.onTvTab(label: String) =
    onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

internal fun ComposeTestRule.openTvTab(label: String) {
    focusTvTab(label)
    pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
}

internal fun ComposeTestRule.focusTvTab(label: String) {
    waitForIdle()
    var climbs = 0
    while (tvTabs().none { it.focused }) {
        check(climbs++ < MAX_TAB_BAR_CLIMB) { "No tab took focus after $MAX_TAB_BAR_CLIMB Up presses" }
        pressDpad(KeyEvent.KEYCODE_DPAD_UP)
    }
    val tabs = tvTabs()
    val from = tabs.indexOfFirst { it.focused }
    val to = tabs.indexOfFirst { it.label == label }
    check(to >= 0) { "No $label tab in ${tabs.map { it.label }}" }
    repeat(abs(to - from)) { pressDpad(if (to > from) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT) }
    onTvTab(label).assertIsFocused()
}

private class TvTabNode(val label: String, val focused: Boolean)

private fun ComposeTestRule.tvTabs() =
    onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
        .sortedBy { it.boundsInRoot.left }
        .map { node ->
            TvTabNode(
                label = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text },
                focused = node.config.getOrNull(SemanticsProperties.Focused) == true,
            )
        }

private fun ComposeTestRule.pressDpad(keyCode: Int) {
    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
    waitForIdle()
}

private const val MAX_TAB_BAR_CLIMB = 30

internal fun proofItem(id: Long, name: String, type: PutioFileType, parentId: FilesItemId) = FilesItem(
    id = FilesItemId(id),
    parentId = parentId,
    name = name,
    type = type,
    sizeBytes = 1_048_576L,
    createdAt = "2026-09-30T10:00:00Z",
)

internal class ProofFilesRepository(
    private val listings: Map<FilesItemId, FilesPage>,
    private val continuations: Map<FilesCursor, FilesPage> = emptyMap(),
    private val pageDelayMillis: Long = 0L,
) : FilesRepository {
    override suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage> =
        listings[folderId]?.let { PutioResult.Success(it) }
            ?: PutioResult.Failure(PutioFailure.Unexpected(IllegalStateException("No listing $folderId")))

    override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<FilesPage> {
        val page = continuations[cursor] ?: error("One page")
        delay(pageDelayMillis)
        return PutioResult.Success(page)
    }

    override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) = error("No moves")

    override suspend fun move(itemIds: List<FilesItemId>, destinationId: FilesItemId):
        PutioResult<List<FileMoveError>> = error("No moves")

    override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort) = error("No sorting")

    override suspend fun rename(itemId: FilesItemId, name: String) = error("No renames")

    override suspend fun delete(itemIds: List<FilesItemId>, mode: FilesDeleteMode):
        PutioResult<FileDeleteResult> = error("No deletes")

    override suspend fun resolveItem(itemId: FilesItemId) = error("No checks")

    override suspend fun startCopy(itemId: FilesItemId, destinationId: FilesItemId) = error("No copies")

    override suspend fun checkCopy(copyId: FilesCopyId) = error("No copies")
}

internal class ProofTrashRepository(
    private val items: List<TrashItem>,
    private val trashSizeBytes: Long? = null,
) : TrashRepository {
    override suspend fun load() =
        PutioResult.Success(TrashPage(items, nextCursor = null, trashSizeBytes = trashSizeBytes))

    override suspend fun loadNextPage(cursor: FilesCursor) = error("One page")

    override suspend fun restore(itemId: FilesItemId) = error("No restores")

    override suspend fun resolveItem(itemId: FilesItemId) = error("No checks")

    override suspend fun deleteItem(itemId: FilesItemId) = error("No deletes")

    override suspend fun restoreAll(selection: TrashBulkSelection) = error("No restores")

    override suspend fun empty() = error("No empties")
}
