package io.putdotio.android.tv

import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesStreamUrls
import io.putdotio.android.files.FilesWatchedRepository
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryPage
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.history.HistoryRepositoryResult
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
import io.putdotio.android.trash.TrashBulkSelection
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashPage
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvSessionViewModelTest {
    private val auth = MutableStateFlow<TvAuthState>(signedIn(1))
    private val stores = mutableListOf<FakeRecentSearchStore>()
    private val watched = FakeWatchedRepository()
    private val dependencies = TvSessionDependencies(
        filesRepository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId) =
                FilesRepositoryResult.Success(FilesPage(emptyList(), null))
        },
        searchRepository = object : SearchRepository {
            override suspend fun search(term: SearchTerm) =
                FilesRepositoryResult.Success(SearchPage(emptyList(), nextCursor = null, total = 0))

            override suspend fun loadNextPage(cursor: FilesCursor) = error("No continuation expected")
        },
        historyRepository = object : HistoryRepository {
            override suspend fun load(before: HistoryEventId?) =
                HistoryRepositoryResult.Success(HistoryPage(emptyList(), hasMore = false))

            override suspend fun clear() = HistoryRepositoryResult.Success(Unit)
        },
        trashRepository = StubTrashRepository,
        settingsRepository = StubAccountSettingsRepository,
        appConfigRepository = StubAndroidAppConfigRepository,
        watchedRepository = watched,
        streamUrls = FilesStreamUrls { "https://api.put.io/v2/files/${it.value}/stream?oauth_token=t" },
        filesItemResolver = object : FilesItemResolver {
            override suspend fun resolveItem(itemId: FilesItemId) = FilesRepositoryResult.Success(
                FilesItem(
                    id = itemId,
                    parentId = FilesItemId(0L),
                    name = "resolved-${itemId.value}",
                    type = PutioFileType.VIDEO,
                    sizeBytes = 1L,
                    createdAt = "2026-04-20T10:00:00Z",
                ),
            )
        },
        recentSearchStore = { FakeRecentSearchStore().also { stores += it } },
    )

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun `the same session reuses its controllers and a new session replaces them`() {
        val viewModel = TvSessionViewModel(auth)
        val first = checkNotNull(viewModel.sessionFor(account(), TvAuthSessionId(1), dependencies))
        first.filesFocusMemory[0L] = 7L

        assertSame(first, viewModel.sessionFor(account(), TvAuthSessionId(1), dependencies))

        auth.value = signedIn(2)
        val second = checkNotNull(viewModel.sessionFor(account(), TvAuthSessionId(2), dependencies))
        assertNotSame(first, second)
        assertNull(second.filesFocusMemory[0L])
        assertFalse(first.files.dispatch(FilesBrowserEvent.Refresh))
        assertFalse(first.search.updateQuery("late"))
        assertFalse(first.trash.dispatch(TrashEvent.Open))
        assertTrue(stores.first().closed)
        assertFalse(stores.last().closed)
    }

    @Test
    fun `a history row resolves its file for Files to open`() = runTest {
        val viewModel = TvSessionViewModel(auth)
        val session = checkNotNull(viewModel.sessionFor(account(), TvAuthSessionId(1), dependencies))

        assertTrue(session.history.dispatch(HistoryEvent.OpenFile(HistoryFileId(55))))

        assertEquals("resolved-55", session.historyOpens.first().name)
        assertNull(session.historyOpenFailure.value)
    }

    @Test
    fun `dismissing a history open failure keeps a session verdict`() = runTest {
        val rejected = FilesFailure.AuthenticationRequired(PutioConfigurationException("401"))
        val deps = TvSessionDependencies(
            filesRepository = dependencies.filesRepository,
            searchRepository = dependencies.searchRepository,
            historyRepository = dependencies.historyRepository,
            trashRepository = dependencies.trashRepository,
            settingsRepository = dependencies.settingsRepository,
            appConfigRepository = dependencies.appConfigRepository,
            watchedRepository = dependencies.watchedRepository,
            streamUrls = dependencies.streamUrls,
            filesItemResolver = object : FilesItemResolver {
                override suspend fun resolveItem(itemId: FilesItemId) = FilesRepositoryResult.Failure(rejected)
            },
            recentSearchStore = dependencies.recentSearchStore,
        )
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), deps))

        session.history.dispatch(HistoryEvent.OpenFile(HistoryFileId(55)))
        assertEquals(rejected, session.historyOpenFailure.value)

        session.dismissHistoryOpenFailure()
        assertEquals(rejected, session.historyOpenFailure.value)
    }

    @Test
    fun `marking watched writes the duration and the listing row follows`() = runTest {
        val viewModel = TvSessionViewModel(auth)
        val session = checkNotNull(viewModel.sessionFor(account(), TvAuthSessionId(1), dependencies))
        val video = FilesItem(
            id = FilesItemId(9),
            parentId = FilesItemId(0L),
            name = "clip.mp4",
            type = PutioFileType.VIDEO,
            sizeBytes = 1L,
            createdAt = "2026-04-20T10:00:00Z",
            playback = FilesPlaybackProgress(0.0, 120.0),
        )
        session.setWatched(video, watched = true)
        assertEquals(listOf(FilesItemId(9) to 120.0), watched.calls)
        assertNull(session.fileActionFailure.value)

        session.setWatched(video.copy(playback = FilesPlaybackProgress(120.0, 120.0)), watched = false)
        assertEquals(FilesItemId(9) to null, watched.calls.last())
    }

    @Test
    fun `a failed watched write is reported and a session verdict survives dismissal`() = runTest {
        val rejected = FilesFailure.AuthenticationRequired(PutioConfigurationException("401"))
        watched.failure = rejected
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))
        val video = FilesItem(
            id = FilesItemId(9),
            parentId = FilesItemId(0L),
            name = "clip.mp4",
            type = PutioFileType.VIDEO,
            sizeBytes = 1L,
            createdAt = "2026-04-20T10:00:00Z",
            playback = FilesPlaybackProgress(30.0, 120.0),
        )

        session.setWatched(video, watched = false)
        assertEquals(rejected, session.fileActionFailure.value)
        session.dismissFileActionFailure()
        assertEquals(rejected, session.fileActionFailure.value)
        assertEquals("https://api.put.io/v2/files/9/stream?oauth_token=t", session.originalStreamUrl(video))
    }

    @Test
    fun `a session is refused when it is not the signed-in one`() {
        val viewModel = TvSessionViewModel(auth)

        assertNull(viewModel.sessionFor(account(), TvAuthSessionId(9), dependencies))
        assertNull(viewModel.sessionFor(account(userId = 7), TvAuthSessionId(1), dependencies))
    }

    @Test
    fun `signing out closes the live session`() {
        val viewModel = TvSessionViewModel(auth)
        val session = checkNotNull(viewModel.sessionFor(account(), TvAuthSessionId(1), dependencies))

        auth.value = TvAuthState.Initializing

        assertFalse(session.files.dispatch(FilesBrowserEvent.Refresh))
        assertFalse(session.search.updateQuery("late"))
        assertTrue(stores.single().closed)
        assertNull(viewModel.sessionFor(account(), TvAuthSessionId(1), dependencies))
    }

    private fun account(userId: Long = 42) =
        TvAccount(userId = userId, username = "u", email = "u@example.com", historyEnabled = true)

    private fun signedIn(session: Long) = TvAuthState.SignedIn(account(), TvAuthSessionId(session))

    private object StubTrashRepository : TrashRepository {
        override suspend fun load() = FilesRepositoryResult.Success(TrashPage(emptyList(), nextCursor = null))
        override suspend fun loadNextPage(cursor: FilesCursor) = error("No continuation expected")
        override suspend fun restore(itemId: FilesItemId) = FilesRepositoryResult.Success(Unit)
        override suspend fun resolveItem(itemId: FilesItemId) = error("No check expected")
        override suspend fun deleteItem(itemId: FilesItemId) = FilesRepositoryResult.Success(Unit)
        override suspend fun restoreAll(selection: TrashBulkSelection) = FilesRepositoryResult.Success(Unit)
        override suspend fun empty() = FilesRepositoryResult.Success(Unit)
    }

    private class FakeWatchedRepository : FilesWatchedRepository {
        val calls = mutableListOf<Pair<FilesItemId, Double?>>()
        var failure: FilesFailure? = null

        override suspend fun setPosition(itemId: FilesItemId, seconds: Double): FilesRepositoryResult<Unit> {
            calls += itemId to seconds
            return failure?.let { FilesRepositoryResult.Failure(it) } ?: FilesRepositoryResult.Success(Unit)
        }

        override suspend fun clearPosition(itemId: FilesItemId): FilesRepositoryResult<Unit> {
            calls += itemId to null
            return failure?.let { FilesRepositoryResult.Failure(it) } ?: FilesRepositoryResult.Success(Unit)
        }
    }

    private object StubAccountSettingsRepository : AccountSettingsRepository {
        override suspend fun load() = AccountSettingsRepositoryResult.Success(
            AccountSettingsPreferences(
                historyEnabled = true,
                trashEnabled = true,
                showSubtitles = true,
                autoSelectSubtitles = true,
            ),
        )

        override suspend fun save(change: AccountSettingsChange) = AccountSettingsRepositoryResult.Success(Unit)

        override suspend fun loadTunnelRoutes() = error("No route list expected")
    }

    private object StubAndroidAppConfigRepository : AndroidAppConfigRepository {
        override suspend fun load() = AndroidAppConfigRepositoryResult.Success(AndroidAppConfigPreferences())

        override suspend fun save(change: AndroidAppConfigChange) = AndroidAppConfigRepositoryResult.Success(Unit)
    }

    private class FakeRecentSearchStore : RecentSearchStoreOwner {
        override val terms = MutableStateFlow<List<SearchTerm>>(emptyList())
        override val failure = MutableStateFlow<FilesFailure?>(null)
        var closed = false

        override fun record(term: SearchTerm) {
            terms.value = listOf(term) + terms.value.filterNot { it == term }
        }

        override fun remove(term: SearchTerm) {
            terms.value = terms.value.filterNot { it == term }
        }

        override fun clear() {
            terms.value = emptyList()
        }

        override fun retry() = Unit

        override fun close() {
            closed = true
        }
    }
}
