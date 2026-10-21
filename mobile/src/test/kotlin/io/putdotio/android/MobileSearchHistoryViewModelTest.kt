package io.putdotio.android

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesExternalOpen
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesOpenOrigin
import io.putdotio.android.history.HistoryController
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryPage
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.search.SearchTerm
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.putdotio.android.playback.dispatch
import io.putdotio.android.search.ActiveSearchHistorySession

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
                            override suspend fun search(term: SearchTerm): PutioResult<SearchPage> =
                                PutioResult.Success(SearchPage(listOf(item), nextCursor = null, total = 1))

                            override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage> =
                                error("No continuation expected")
                        },
                    recentSearchStore = recentSearchStore,
                    parentScope = this,
                )
            val session =
                ActiveSearchHistorySession(
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

                assertEquals(
                    FilesExternalOpen(item, FilesOpenOrigin.SEARCH),
                    withTimeout(TIMEOUT) { session.navigation.first() },
                )
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
                    recentSearchStore = recentSearchStore,
                    search = SearchController(RecordingSearchRepository(), recentSearchStore, this),
                    history = history,
                    parentScope = this,
                    filesItemResolver =
                        object : FilesItemResolver {
                            override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> {
                                resolverCalls += 1
                                resolved.complete(Unit)
                                return PutioResult.Success(item)
                            }
                        },
                )

            try {
                assertTrue(history.dispatch(HistoryEvent.OpenFile(HistoryFileId(item.id.value))))
                withTimeout(TIMEOUT) { resolved.await() }
                yield()

                assertEquals(
                    FilesExternalOpen(item, FilesOpenOrigin.HISTORY),
                    withTimeout(TIMEOUT) { session.navigation.first() },
                )
                session.openFile(item.id)
                assertEquals(
                    FilesExternalOpen(item, FilesOpenOrigin.LINK),
                    withTimeout(TIMEOUT) { session.navigation.first() },
                )
                assertEquals(
                    null,
                    withTimeoutOrNull(NO_SECOND_EVENT_TIMEOUT) { session.navigation.first() },
                )
                assertEquals(2, resolverCalls)
            } finally {
                session.close()
            }
        }

    @Test
    fun secondHistoryTapWhileTheFirstResolvesNavigatesOnlyToTheSecond() =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val recentSearchStore = FakeRecentSearchStore()
            val history = HistoryController(EmptyHistoryRepository, historyEnabled = true, parentScope = this)
            val session =
                ActiveSearchHistorySession(
                    recentSearchStore = recentSearchStore,
                    search = SearchController(RecordingSearchRepository(), recentSearchStore, this),
                    history = history,
                    parentScope = this,
                    filesItemResolver =
                        object : FilesItemResolver {
                            override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> {
                                if (itemId.value == 1L) {
                                    firstStarted.complete(Unit)
                                    releaseFirst.await()
                                } else {
                                    secondStarted.complete(Unit)
                                }
                                return EmptyFilesItemResolver.resolveItem(itemId)
                            }
                        },
                )

            try {
                assertTrue(history.dispatch(HistoryEvent.OpenFile(HistoryFileId(1L))))
                withTimeout(TIMEOUT) { firstStarted.await() }
                assertTrue(history.dispatch(HistoryEvent.OpenFile(HistoryFileId(2L))))
                // Sequential collection never starts the second before the first ends; let it lapse.
                withTimeoutOrNull(NO_SECOND_EVENT_TIMEOUT) { secondStarted.await() }
                releaseFirst.complete(Unit)

                assertEquals(FilesItemId(2L), withTimeout(TIMEOUT) { session.navigation.first() }.item.id)
                assertEquals(
                    null,
                    withTimeoutOrNull(NO_SECOND_EVENT_TIMEOUT) { session.navigation.first() },
                )
            } finally {
                session.close()
            }
        }

    @Test
    fun closingTheSessionClosesItsSearchAndRecentSearches() =
        runBlocking {
            val recentSearchStore = FakeRecentSearchStore()
            val search = SearchController(RecordingSearchRepository(), recentSearchStore, this)
            val session =
                ActiveSearchHistorySession(
                    recentSearchStore = recentSearchStore,
                    search = search,
                    history = HistoryController(EmptyHistoryRepository, historyEnabled = true, parentScope = this),
                    parentScope = this,
                    filesItemResolver = EmptyFilesItemResolver,
                )

            session.close()

            assertFalse(search.updateQuery("closed"))
            assertTrue(recentSearchStore.closed)
        }

    private class FakeRecentSearchStore : RecentSearchStoreOwner {
        override val terms = MutableStateFlow<List<SearchTerm>>(emptyList())
        override val enabled = MutableStateFlow<Boolean?>(true)
        override val failure = MutableStateFlow<PutioFailure?>(null)
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

        override fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }

        override fun retry() = Unit

        override fun close() {
            closed = true
        }
    }

    private class RecordingSearchRepository : SearchRepository {
        val terms = mutableListOf<SearchTerm>()

        override suspend fun search(term: SearchTerm): PutioResult<SearchPage> {
            terms += term
            return PutioResult.Success(SearchPage(emptyList(), nextCursor = null, total = 0))
        }

        override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage> =
            error("No continuation expected")
    }

    private object EmptyHistoryRepository : HistoryRepository {
        override suspend fun load(
            before: io.putdotio.android.history.HistoryEventId?,
        ): PutioResult<HistoryPage> =
            PutioResult.Success(HistoryPage(emptyList(), hasMore = false))

        override suspend fun clear(): PutioResult<Unit> = PutioResult.Success(Unit)
    }

    private object EmptyFilesItemResolver : FilesItemResolver {
        override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> =
            PutioResult.Success(
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
        const val TIMEOUT = 2_000L
        const val NO_SECOND_EVENT_TIMEOUT = 100L
    }
}
