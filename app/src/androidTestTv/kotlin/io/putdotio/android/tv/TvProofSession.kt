package io.putdotio.android.tv

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.TvSessionShell
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.FilesStreamUrls
import io.putdotio.android.files.FilesWatchedRepository
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryPage
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.history.HistoryRepositoryResult
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
import io.putdotio.android.settings.AndroidAppConfigRepositoryResult
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.trash.TrashBulkSelection
import io.putdotio.android.trash.TrashPage
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake account repositories for the controlled-state TV proofs: Files serves [listings],
 * Search answers every term with [searchResults], and playback resolves to [playback], read only
 * when something plays.
 * Nothing reaches the API.
 */
internal fun tvProofDependencies(
    listings: Map<FilesItemId, FilesPage>,
    searchResults: List<FilesItem>,
    recentSearchStore: (CoroutineScope) -> RecentSearchStoreOwner,
    playback: () -> PlaybackResolution = { error("No playback in this proof") },
) = TvSessionDependencies(
    filesRepository = ProofFilesRepository(listings),
    searchRepository = object : SearchRepository {
        override suspend fun search(term: SearchTerm) =
            FilesRepositoryResult.Success(SearchPage(searchResults, null, total = searchResults.size))

        override suspend fun loadNextPage(cursor: FilesCursor) = error("One page")
    },
    historyRepository = object : HistoryRepository {
        override suspend fun load(before: HistoryEventId?) =
            HistoryRepositoryResult.Success(HistoryPage(emptyList(), hasMore = false))

        override suspend fun clear() = HistoryRepositoryResult.Success(Unit)
    },
    trashRepository = ProofTrashRepository,
    settingsRepository = object : AccountSettingsRepository {
        override suspend fun load() = AccountSettingsRepositoryResult.Success(
            AccountSettingsPreferences(
                historyEnabled = true,
                trashEnabled = true,
                showSubtitles = true,
                autoSelectSubtitles = true,
                resumePlayback = true,
            ),
        )

        override suspend fun save(change: AccountSettingsChange) = AccountSettingsRepositoryResult.Success(Unit)

        override suspend fun loadTunnelRoutes() =
            AccountSettingsRepositoryResult.Success(emptyList<TunnelRouteOption>())
    },
    appConfigRepository = object : AndroidAppConfigRepository {
        override suspend fun load() = AndroidAppConfigRepositoryResult.Success(AndroidAppConfigPreferences())

        override suspend fun save(change: AndroidAppConfigChange) = AndroidAppConfigRepositoryResult.Success(Unit)
    },
    watchedRepository = object : FilesWatchedRepository {
        override suspend fun setPosition(itemId: FilesItemId, seconds: Double) =
            FilesRepositoryResult.Success(Unit)

        override suspend fun clearPosition(itemId: FilesItemId) = FilesRepositoryResult.Success(Unit)
    },
    streamUrls = FilesStreamUrls { null },
    filesItemResolver = object : FilesItemResolver {
        override suspend fun resolveItem(itemId: FilesItemId) = error("No history rows")
    },
    recentSearchStore = recentSearchStore,
    playbackRepository = {
        object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget) =
                PlaybackRepositoryResult.Success(playback())

            override suspend fun findNextVideo(target: PlaybackTarget) = error("No autoplay on TV")
        }
    },
    writePlaybackPosition = { _, _ -> PlaybackRepositoryResult.Success(Unit) },
)

/**
 * Mounts the production TV session and signed-in shell on [dependencies]. [onExit] stands in for
 * the system leaving the app; it is registered before the shell.
 */
internal fun AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>.mountTvProofSession(
    dependencies: TvSessionDependencies,
    onExit: (() -> Unit)? = null,
): TvSession {
    val account = TvAccount(userId = 1, username = "proof", email = "proof@example.invalid", historyEnabled = true)
    val auth = MutableStateFlow<TvAuthState>(TvAuthState.SignedIn(account, TvAuthSessionId(1)))
    lateinit var session: TvSession
    runOnUiThread {
        session = checkNotNull(TvSessionViewModel(auth).sessionFor(account, TvAuthSessionId(1), dependencies))
    }
    setContent {
        if (onExit != null) BackHandler(onBack = onExit)
        MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
            TvSessionShell(
                session = session,
                account = account,
                sessionKey = 1L,
                onSignOut = {},
                onSessionRejected = { error("Unexpected rejection") },
                loadTunnelRoutes = { AccountSettingsRepositoryResult.Success(emptyList<TunnelRouteOption>()) },
            )
        }
    }
    return session
}

internal fun proofItem(id: Long, name: String, type: PutioFileType, parentId: FilesItemId) = FilesItem(
    id = FilesItemId(id),
    parentId = parentId,
    name = name,
    type = type,
    sizeBytes = 1_048_576L,
    createdAt = "2026-09-30T10:00:00Z",
)

private class ProofFilesRepository(private val listings: Map<FilesItemId, FilesPage>) : FilesRepository {
    override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
        listings[folderId]?.let { FilesRepositoryResult.Success(it) }
            ?: FilesRepositoryResult.Failure(FilesFailure.Unexpected(IllegalStateException("No listing $folderId")))

    override suspend fun loadNextPage(cursor: FilesCursor) = error("One page")

    override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) = error("No moves")

    override suspend fun move(itemId: FilesItemId, destinationId: FilesItemId):
        FilesRepositoryResult<List<FileMoveError>> = error("No moves")

    override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort) = error("No sorting")

    override suspend fun rename(itemId: FilesItemId, name: String) = error("No renames")

    override suspend fun delete(itemId: FilesItemId, mode: FilesDeleteMode):
        FilesRepositoryResult<FileDeleteResult> = error("No deletes")

    override suspend fun resolveItem(itemId: FilesItemId) = error("No checks")
}

private object ProofTrashRepository : TrashRepository {
    override suspend fun load() = FilesRepositoryResult.Success(TrashPage(emptyList(), nextCursor = null))

    override suspend fun loadNextPage(cursor: FilesCursor) = error("One page")

    override suspend fun restore(itemId: FilesItemId) = error("No restores")

    override suspend fun resolveItem(itemId: FilesItemId) = error("No checks")

    override suspend fun deleteItem(itemId: FilesItemId) = error("No deletes")

    override suspend fun restoreAll(selection: TrashBulkSelection) = error("No restores")

    override suspend fun empty() = error("No empties")
}
