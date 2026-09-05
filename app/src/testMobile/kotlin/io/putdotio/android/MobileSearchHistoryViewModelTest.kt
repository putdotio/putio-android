package io.putdotio.android

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.history.HistoryController
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryPage
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.history.HistoryRepositoryResult
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.search.SearchTerm
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobileSearchHistoryViewModelTest {
    @Test
    fun navigationProducedBeforeCollectorStartsIsRetained() =
        runBlocking {
            val item =
                FilesItem(
                    id = FilesItemId(7L),
                    parentId = FilesItemId(0L),
                    name = "movie.mkv",
                    type = PutioFileType.VIDEO,
                    sizeBytes = 1L,
                    createdAt = "2026-08-31T10:00:00Z",
                )
            val recentSearchStore = FakeRecentSearchStore()
            val search =
                SearchController(
                    repository =
                        object : SearchRepository {
                            override suspend fun search(term: SearchTerm): FilesRepositoryResult<SearchPage> =
                                FilesRepositoryResult.Success(SearchPage(listOf(item), nextCursor = null, total = 1))

                            override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<SearchPage> =
                                error("No continuation expected")
                        },
                    recentSearchStore = recentSearchStore,
                    parentScope = this,
                )
            val session =
                ActiveSearchHistorySession(
                    key = SessionKey(USER_ID, SessionOne),
                    recentSearchStore = recentSearchStore,
                    search = search,
                    history = HistoryController(EmptyHistoryRepository, historyEnabled = true, parentScope = this),
                    parentScope = this,
                    filesItemResolver = EmptyFilesItemResolver,
                )

            try {
                search.updateQuery("movie")
                search.submit()
                search.state.first { it.content is SearchContent.Ready }
                assertTrue(search.openResult(item.id))
                yield()

                assertEquals(item, withTimeout(TIMEOUT) { session.navigation.first() })
            } finally {
                session.close()
            }
        }

    @Test
    fun resolvedHistoryNavigationIsRetainedAndDeliveredExactlyOnce() =
        runBlocking {
            val item =
                FilesItem(
                    id = FilesItemId(32L),
                    parentId = FilesItemId(0L),
                    name = "history.mkv",
                    type = PutioFileType.VIDEO,
                    sizeBytes = 1L,
                    createdAt = "2026-08-31T10:00:00Z",
                )
            val resolved = CompletableDeferred<Unit>()
            var resolverCalls = 0
            val recentSearchStore = FakeRecentSearchStore()
            val history = HistoryController(EmptyHistoryRepository, historyEnabled = true, parentScope = this)
            val session =
                ActiveSearchHistorySession(
                    key = SessionKey(USER_ID, SessionOne),
                    recentSearchStore = recentSearchStore,
                    search = SearchController(RecordingSearchRepository(), recentSearchStore, this),
                    history = history,
                    parentScope = this,
                    filesItemResolver =
                        object : FilesItemResolver {
                            override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> {
                                resolverCalls += 1
                                resolved.complete(Unit)
                                return FilesRepositoryResult.Success(item)
                            }
                        },
                )

            try {
                assertTrue(history.dispatch(HistoryEvent.OpenFile(HistoryFileId(item.id.value))))
                withTimeout(TIMEOUT) { resolved.await() }
                yield()

                assertEquals(item, withTimeout(TIMEOUT) { session.navigation.first() })
                assertEquals(
                    null,
                    withTimeoutOrNull(NO_SECOND_EVENT_TIMEOUT) { session.navigation.first() },
                )
                assertEquals(1, resolverCalls)
            } finally {
                session.close()
            }
        }

    @Test
    fun reauthenticationClosesOldSessionAndUsesReplacementRepositories() {
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val stores = mutableListOf<FakeRecentSearchStore>()
        val viewModelStore = ViewModelStore()
        val viewModel = viewModel(viewModelStore, authState, stores)
        val firstSearch = RecordingSearchRepository()
        val secondSearch = RecordingSearchRepository()

        PutioClient(PutioConfig(accessToken = "token")).use { client ->
            val first = checkNotNull(viewModel.controllersFor(firstSearch, SessionOne, client))
            shadowOf(Looper.getMainLooper()).idle()
            first.search.updateQuery("first")
            first.search.submit()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(SearchTerm("first") in firstSearch.terms)

            authState.value = signedIn(SessionTwo)
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(first.search.updateQuery("stale"))
            assertTrue(stores.single().closed)

            val second = checkNotNull(viewModel.controllersFor(secondSearch, SessionTwo, client))
            shadowOf(Looper.getMainLooper()).idle()
            second.search.updateQuery("second")
            second.search.submit()
            shadowOf(Looper.getMainLooper()).idle()

            assertNotSame(first, second)
            assertTrue(SearchTerm("second") in secondSearch.terms)
            assertFalse(SearchTerm("stale") in firstSearch.terms)
        }
        viewModelStore.clear()
    }

    @Test
    fun signOutAndViewModelClearCloseOwnedSessions() {
        val authState = MutableStateFlow<MobileAuthState>(signedIn(SessionOne))
        val stores = mutableListOf<FakeRecentSearchStore>()
        val viewModelStore = ViewModelStore()
        val viewModel = viewModel(viewModelStore, authState, stores)

        PutioClient(PutioConfig(accessToken = "token")).use { client ->
            val first = checkNotNull(viewModel.controllersFor(RecordingSearchRepository(), SessionOne, client))
            shadowOf(Looper.getMainLooper()).idle()
            authState.value = MobileAuthState.SignedOut()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(first.search.updateQuery("stale"))
            assertTrue(stores.single().closed)

            authState.value = signedIn(SessionTwo)
            shadowOf(Looper.getMainLooper()).idle()
            val second = checkNotNull(viewModel.controllersFor(RecordingSearchRepository(), SessionTwo, client))
            shadowOf(Looper.getMainLooper()).idle()
            viewModelStore.clear()

            assertFalse(second.search.updateQuery("closed"))
            assertTrue(stores.last().closed)
        }
    }

    private fun viewModel(
        store: ViewModelStore,
        authState: MutableStateFlow<MobileAuthState>,
        stores: MutableList<FakeRecentSearchStore>,
    ): MobileSearchHistoryViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return ViewModelProvider(
            store,
            mobileSearchHistoryViewModelFactory(application, authState) { _, _ ->
                FakeRecentSearchStore().also(stores::add)
            },
        )[MobileSearchHistoryViewModel::class.java]
    }

    private fun MobileSearchHistoryViewModel.controllersFor(
        searchRepository: SearchRepository,
        sessionId: MobileAuthSessionId,
        client: PutioClient,
    ): ActiveSearchHistorySession? =
        controllersFor(
            session = signedIn(sessionId),
            putioClient = client,
            searchRepository = searchRepository,
            historyRepository = EmptyHistoryRepository,
            filesItemResolver = EmptyFilesItemResolver,
        )

    private class FakeRecentSearchStore : MobileRecentSearchStoreOwner {
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

    private class RecordingSearchRepository : SearchRepository {
        val terms = mutableListOf<SearchTerm>()

        override suspend fun search(term: SearchTerm): FilesRepositoryResult<SearchPage> {
            terms += term
            return FilesRepositoryResult.Success(SearchPage(emptyList(), nextCursor = null, total = 0))
        }

        override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<SearchPage> =
            error("No continuation expected")
    }

    private object EmptyHistoryRepository : HistoryRepository {
        override suspend fun load(
            before: io.putdotio.android.history.HistoryEventId?,
        ): HistoryRepositoryResult<HistoryPage> =
            HistoryRepositoryResult.Success(HistoryPage(emptyList(), hasMore = false))

        override suspend fun clear(): HistoryRepositoryResult<Unit> = HistoryRepositoryResult.Success(Unit)
    }

    private object EmptyFilesItemResolver : FilesItemResolver {
        override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> =
            FilesRepositoryResult.Success(
                FilesItem(
                    id = itemId,
                    parentId = FilesItemId(0L),
                    name = "file.mkv",
                    type = PutioFileType.VIDEO,
                    sizeBytes = 1L,
                    createdAt = "2026-08-30T00:00:00Z",
                ),
            )
    }

    private companion object {
        const val USER_ID = 42L
        const val TIMEOUT = 2_000L
        const val NO_SECOND_EVENT_TIMEOUT = 100L
        val Account = MobileAccount(USER_ID, "user", "user@example.com", historyEnabled = true)
        val SessionOne = MobileAuthSessionId(1L)
        val SessionTwo = MobileAuthSessionId(2L)

        fun signedIn(sessionId: MobileAuthSessionId): MobileAuthState.SignedIn =
            MobileAuthState.SignedIn(Account, sessionId)
    }
}
