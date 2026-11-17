package io.putdotio.android.search

import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchTotalTest {
    @Test
    fun theMatchTotalHoldsWhileLaterPagesLoad() =
        runTest(StandardTestDispatcher()) {
            val nextPage = CompletableDeferred<PutioResult<SearchPage>>()
            val repository = FakeSearchRepository(
                search = { _, _ -> page(497, cursor = "next", item(1L), item(2L)) },
                next = { nextPage.await() },
            )
            searchController(repository).use { controller ->
                controller.updateQuery("mkv")
                controller.submit()
                advanceUntilIdle()
                assertEquals(SearchTotal.Exact(497), controller.ready().total)

                assertTrue(controller.loadNextPage())
                advanceUntilIdle()
                assertEquals(SearchTotal.Exact(497), controller.ready().total)

                nextPage.complete(page(2, cursor = "more", item(3L), item(4L)))
                advanceUntilIdle()
                assertEquals(listOf(1L, 2L, 3L, 4L), controller.ready().items.map { it.id.value })
                assertEquals(SearchTotal.Exact(497), controller.ready().total)
            }
        }

    @Test
    fun aTotalAtPutioCapReadsAsAtLeastTheCap() =
        runTest(StandardTestDispatcher()) {
            val repository = FakeSearchRepository(
                search = { _, _ -> page(SEARCH_TOTAL_CAP, cursor = "next", item(1L)) },
                next = { page(SEARCH_TOTAL_CAP, cursor = null, item(2L)) },
            )
            searchController(repository).use { controller ->
                controller.updateQuery("mkv")
                controller.submit()
                advanceUntilIdle()
                assertEquals(SearchTotal.AtLeast(1000), controller.ready().total)

                controller.loadNextPage()
                advanceUntilIdle()
                assertEquals(SearchPaging.Complete, controller.ready().paging)
                assertEquals(SearchTotal.AtLeast(1000), controller.ready().total)
            }
        }

    @Test
    fun anotherTypeChipTakesItsOwnTotal() =
        runTest(StandardTestDispatcher()) {
            val repository = FakeSearchRepository(
                search = { _, filter ->
                    if (filter == SearchFilter.Videos) {
                        page(300, cursor = "next", item(1L))
                    } else {
                        page(497, cursor = "next", item(1L), folder(2L))
                    }
                },
            )
            searchController(repository).use { controller ->
                controller.updateQuery("mkv")
                controller.submit()
                advanceUntilIdle()
                assertEquals(SearchTotal.Exact(497), controller.ready().total)

                controller.filter = SearchFilter.Videos
                assertTrue(controller.state.value.content is SearchContent.Loading)
                advanceUntilIdle()
                assertEquals(SearchTotal.Exact(300), controller.ready().total)
            }
        }

    @Test
    fun subtitlesCountOnlyOnceEveryPageHasLoaded() =
        runTest(StandardTestDispatcher()) {
            val repository = FakeSearchRepository(
                search = { _, _ -> page(40, cursor = "next", item(1L, "Sintel.srt"), item(2L, "Sintel.mkv")) },
                next = { page(40, cursor = null, item(3L, "Sintel.vtt")) },
            )
            searchController(repository).use { controller ->
                controller.filter = SearchFilter.Subtitles
                controller.updateQuery("sintel")
                controller.submit()
                advanceUntilIdle()
                assertNull(controller.ready().total)

                controller.loadNextPage()
                advanceUntilIdle()
                assertEquals(SearchTotal.Exact(2), controller.ready().total)
            }
        }

    @Test
    fun removingAndRestoringAResultMovesAnExactTotal() =
        runTest(StandardTestDispatcher()) {
            val repository = FakeSearchRepository(search = { _, _ -> page(497, cursor = "next", item(1L), item(2L)) })
            searchController(repository).use { controller ->
                controller.updateQuery("mkv")
                controller.submit()
                advanceUntilIdle()
                val removed = controller.ready().items.first()

                assertTrue(controller.itemRemoved(removed.id))
                assertEquals(SearchTotal.Exact(496), controller.ready().total)

                assertTrue(controller.itemRestored(removed, 0, SearchTerm("mkv"), SearchFilter.All))
                assertEquals(SearchTotal.Exact(497), controller.ready().total)
            }
        }

    @Test
    fun removingAResultLowersACappedTotalThroughLaterPages() =
        runTest(StandardTestDispatcher()) {
            val repository = FakeSearchRepository(
                search = { _, _ -> page(SEARCH_TOTAL_CAP, cursor = "next", item(1L), item(2L)) },
                next = { page(SEARCH_TOTAL_CAP, cursor = null, item(3L)) },
            )
            searchController(repository).use { controller ->
                controller.updateQuery("mkv")
                controller.submit()
                advanceUntilIdle()

                assertTrue(controller.itemRemoved(FilesItemId(1L)))
                assertEquals(SearchTotal.AtLeast(999), controller.ready().total)

                controller.loadNextPage()
                advanceUntilIdle()
                assertEquals(SearchTotal.AtLeast(999), controller.ready().total)
            }
        }

    @Test
    fun aCompleteSearchCountsWhatItLoaded() =
        runTest(StandardTestDispatcher()) {
            val repository = FakeSearchRepository(search = { _, _ -> page(3, cursor = null, item(1L), item(1L)) })
            searchController(repository).use { controller ->
                controller.updateQuery("mkv")
                controller.submit()
                advanceUntilIdle()
                assertEquals(SearchTotal.Exact(1), controller.ready().total)
            }
        }

    private fun TestScope.searchController(repository: SearchRepository): SearchController =
        SearchController(repository, StaticRecentSearchStore(), this, delaySearch = {})

    private fun SearchController.ready(): SearchContent.Ready = state.value.content as SearchContent.Ready

    private class FakeSearchRepository(
        private val search: suspend (SearchTerm, SearchFilter) -> PutioResult<SearchPage>,
        private val next: suspend (FilesCursor) -> PutioResult<SearchPage> = { error("Unexpected continuation") },
    ) : SearchRepository {
        override suspend fun search(term: SearchTerm): PutioResult<SearchPage> = search(term, SearchFilter.All)

        override suspend fun search(term: SearchTerm, filter: SearchFilter): PutioResult<SearchPage> =
            search.invoke(term, filter)

        override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage> = next(cursor)
    }

    private class StaticRecentSearchStore : RecentSearchStore {
        override val terms = MutableStateFlow(emptyList<SearchTerm>())
        override val enabled = MutableStateFlow<Boolean?>(true)

        override fun record(term: SearchTerm) = Unit

        override fun remove(term: SearchTerm) = Unit

        override fun clear() = Unit

        override fun setEnabled(enabled: Boolean) = Unit
    }

    private fun page(total: Int, cursor: String?, vararg items: FilesItem): PutioResult<SearchPage> =
        PutioResult.Success(SearchPage(items.toList(), cursor?.let(::FilesCursor), total))

    private fun item(id: Long, name: String = "$id.mkv"): FilesItem =
        FilesItem(
            id = FilesItemId(id),
            parentId = FilesItemId(0L),
            name = name,
            type = PutioFileType.VIDEO,
            sizeBytes = 1L,
            createdAt = "2026-08-30T00:00:00Z",
        )

    private fun folder(id: Long): FilesItem = item(id, "folder $id").copy(type = PutioFileType.FOLDER)
}
