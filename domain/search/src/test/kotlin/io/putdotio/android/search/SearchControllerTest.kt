package io.putdotio.android.search

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchControllerTest {
    @Test
    fun debouncesForExactlyThreeHundredMillisecondsAndCancelsOnBlankQuery() =
        runBlocking {
            val delayStarted = CompletableDeferred<Long>()
            val delayCancelled = CompletableDeferred<Unit>()
            var searches = 0
            val controller =
                controller(
                    repository = repository(search = {
                        searches += 1
                        success()
                    }),
                    delaySearch = { millis ->
                        delayStarted.complete(millis)
                        try {
                            awaitCancellation()
                        } finally {
                            delayCancelled.complete(Unit)
                        }
                    },
                )

            try {
                assertTrue(controller.updateQuery("  movie  "))
                assertEquals(SEARCH_DEBOUNCE_MILLIS, withTimeout(TIMEOUT) { delayStarted.await() })
                val debouncing = controller.state.value.content as SearchContent.Debouncing
                assertEquals(SearchTerm("movie"), debouncing.term)

                assertTrue(controller.updateQuery("   "))
                withTimeout(TIMEOUT) { delayCancelled.await() }

                assertTrue(controller.state.value.content is SearchContent.Idle)
                assertEquals(0, searches)
            } finally {
                controller.close()
            }
        }

    @Test
    fun ignoresAStaleSearchThatReturnsAfterCancellation() =
        runBlocking {
            val oldStarted = CompletableDeferred<Unit>()
            val repository =
                repository { term ->
                    if (term.value == "old") {
                        oldStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (_: CancellationException) {
                            success(item(1L, "old.mkv"))
                        }
                    } else {
                        success(item(2L, "new.mkv"))
                    }
                }
            val controller = controller(repository)

            try {
                controller.updateQuery("old")
                withTimeout(TIMEOUT) { oldStarted.await() }
                controller.updateQuery("new")

                val ready = controller.awaitContent<SearchContent.Ready>()
                assertEquals(SearchTerm("new"), ready.term)
                assertEquals(listOf("new.mkv"), ready.items.map(FilesItem::name))
            } finally {
                controller.close()
            }
        }

    @Test
    fun submitTrimsAndRecordsHistoryBeforeSearching() =
        runBlocking {
            val effects = mutableListOf<String>()
            val store = FakeRecentSearchStore(onRecord = { effects += "record:${it.value}" })
            val controller =
                controller(
                    repository = repository { term ->
                        effects += "search:${term.value}"
                        success()
                    },
                    store = store,
                    delaySearch = { awaitCancellation() },
                )

            try {
                controller.updateQuery("  documentary  ")
                assertTrue(controller.submit())
                controller.awaitContent<SearchContent.Empty>()

                assertEquals("documentary", controller.state.value.query)
                assertEquals(listOf("record:documentary", "search:documentary"), effects)
            } finally {
                controller.close()
            }
        }

    @Test
    fun searchesSettledWhileTypingSlowlyAreNotRecorded() =
        runBlocking {
            val store = FakeRecentSearchStore()
            val searched = mutableListOf<String>()
            val controller =
                controller(
                    repository = repository { term ->
                        searched += term.value
                        success(item(1L, "${term.value}.mkv"))
                    },
                    store = store,
                )

            try {
                // Each keystroke lands after the debounce has settled, as D-pad typing on TV does.
                listOf("m", "ma", "map").forEach { query ->
                    controller.updateQuery(query)
                    controller.awaitContent<SearchContent.Ready> { it.term == SearchTerm(query) }
                }

                assertEquals(listOf("m", "ma", "map"), searched)
                assertEquals(emptyList<SearchTerm>(), store.recorded)
            } finally {
                controller.close()
            }
        }

    @Test
    fun openingAResultRecordsTheSearchItCameFrom() =
        runBlocking {
            val store = FakeRecentSearchStore()
            val controller = controller(repository { success(item(9L, "sample.mkv")) }, store = store)

            try {
                controller.updateQuery("  sample ")
                controller.awaitContent<SearchContent.Ready>()
                assertEquals(emptyList<SearchTerm>(), store.recorded)

                assertFalse(controller.openResult(FilesItemId(8L)))
                assertEquals(emptyList<SearchTerm>(), store.recorded)
                assertTrue(controller.openResult(FilesItemId(9L)))

                assertEquals(listOf(SearchTerm("sample")), store.recorded)
            } finally {
                controller.close()
            }
        }

    @Test
    fun retriesPagingAndStopsARepeatedCursor() =
        runBlocking {
            var pagingAttempts = 0
            val failure = PutioFailure.Unexpected(IllegalStateException("offline"))
            val repository =
                object : SearchRepository {
                    override suspend fun search(term: SearchTerm): PutioResult<SearchPage> =
                        success(item(1L, "one.mkv"), cursor = "next")

                    override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage> {
                        pagingAttempts += 1
                        return if (pagingAttempts == 1) {
                            PutioResult.Failure(failure)
                        } else {
                            success(item(1L, "one.mkv"), item(2L, "two.mkv"), cursor = "next")
                        }
                    }
                }
            val controller = controller(repository)

            try {
                controller.updateQuery("movie")
                controller.awaitContent<SearchContent.Ready>()
                yield()
                assertTrue(controller.loadNextPage())
                controller.awaitPaging<SearchPaging.Failed>()
                yield()
                assertTrue(controller.retry())

                val ready = controller.awaitContent<SearchContent.Ready> { it.paging == SearchPaging.Complete }
                assertEquals(listOf(1L, 2L), ready.items.map { it.id.value })
                assertEquals(2, pagingAttempts)
                assertFalse(controller.loadNextPage())
            } finally {
                controller.close()
            }
        }

    @Test
    fun emitsOpenResultOnlyForVisibleResults() =
        runBlocking {
            val controller = controller(repository { success(item(9L, "movie.mkv")) })

            try {
                controller.updateQuery("movie")
                controller.awaitContent<SearchContent.Ready>()
                val output = async { withTimeout(TIMEOUT) { controller.outputs.first() } }

                assertFalse(controller.openResult(FilesItemId(8L)))
                assertTrue(controller.openResult(FilesItemId(9L)))
                controller.updateQuery("different query")
                assertEquals(SearchOutput.OpenResult(item(9L, "movie.mkv")), output.await())
            } finally {
                controller.close()
            }
        }

    @Test
    fun mirrorsRecentTermsAndDelegatesRemoveAndClear() =
        runBlocking {
            val store = FakeRecentSearchStore(listOf(SearchTerm("movie"), SearchTerm("shows")))
            val controller = controller(repository { success() }, store = store)

            try {
                assertEquals(store.terms.value, controller.state.value.recentTerms)
                assertTrue(controller.editRecentSearches(RecentSearchEdit.Remove(SearchTerm("movie"))))
                controller.awaitRecentTerms(listOf(SearchTerm("shows")))
                assertEquals(listOf(SearchTerm("movie")), store.removed)

                assertTrue(controller.editRecentSearches(RecentSearchEdit.Clear))
                controller.awaitRecentTerms(emptyList())
                assertEquals(1, store.clearCount)
                assertFalse(controller.editRecentSearches(RecentSearchEdit.Clear))
            } finally {
                controller.close()
            }
        }

    @Test
    fun mirrorsTheHistorySettingAndChangesItOnlyOnceLoaded() =
        runBlocking {
            val store = FakeRecentSearchStore(listOf(SearchTerm("movie")), enabled = null)
            val controller = controller(repository { success() }, store = store)

            try {
                assertEquals(null, controller.state.value.recentSearchesEnabled)
                assertFalse(controller.editRecentSearches(RecentSearchEdit.SetEnabled(false)))

                store.enabled.value = true
                withTimeout(TIMEOUT) { controller.state.first { it.recentSearchesEnabled == true } }
                assertFalse(controller.editRecentSearches(RecentSearchEdit.SetEnabled(true)))
                assertTrue(controller.editRecentSearches(RecentSearchEdit.SetEnabled(false)))
                withTimeout(TIMEOUT) { controller.state.first { it.recentSearchesEnabled == false } }

                assertEquals(listOf(false), store.enabledChanges)
            } finally {
                controller.close()
            }
        }

    private fun CoroutineScope.controller(
        repository: SearchRepository,
        store: RecentSearchStore = FakeRecentSearchStore(),
        delaySearch: suspend (Long) -> Unit = {},
    ): SearchController = SearchController(repository, store, this, delaySearch)

    private fun repository(search: suspend (SearchTerm) -> PutioResult<SearchPage>): SearchRepository =
        object : SearchRepository {
            override suspend fun search(term: SearchTerm): PutioResult<SearchPage> = search(term)

            override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage> =
                error("Unexpected continuation")
        }

    private suspend inline fun <reified T : SearchContent> SearchController.awaitContent(
        noinline predicate: (T) -> Boolean = { true },
    ): T =
        withTimeout(TIMEOUT) {
            state.first { it.content is T && predicate(it.content) }.content as T
        }

    private suspend inline fun <reified T : SearchPaging> SearchController.awaitPaging(): T =
        withTimeout(TIMEOUT) {
            state
                .first {
                    val paging =
                        when (val content = it.content) {
                            is SearchContent.Empty -> content.paging
                            is SearchContent.Ready -> content.paging
                            else -> null
                        }
                    paging is T
                }.let {
                    when (val content = it.content) {
                        is SearchContent.Empty -> content.paging as T
                        is SearchContent.Ready -> content.paging as T
                        else -> error("Expected paged content")
                    }
                }
        }

    private suspend fun SearchController.awaitRecentTerms(expected: List<SearchTerm>) {
        withTimeout(TIMEOUT) { state.first { it.recentTerms == expected } }
    }

    private fun success(
        vararg items: FilesItem,
        cursor: String? = null,
    ): PutioResult.Success<SearchPage> =
        PutioResult.Success(
            SearchPage(items.toList(), cursor?.let(::FilesCursor), total = items.size),
        )

    private fun item(
        id: Long,
        name: String,
    ): FilesItem =
        FilesItem(
            id = FilesItemId(id),
            parentId = FilesItemId(0L),
            name = name,
            type = PutioFileType.VIDEO,
            sizeBytes = 1L,
            createdAt = "2026-08-30T00:00:00Z",
        )

    private class FakeRecentSearchStore(
        initial: List<SearchTerm> = emptyList(),
        enabled: Boolean? = true,
        private val onRecord: (SearchTerm) -> Unit = {},
    ) : RecentSearchStore {
        override val terms = MutableStateFlow(initial)
        override val enabled = MutableStateFlow(enabled)
        val recorded = mutableListOf<SearchTerm>()
        val removed = mutableListOf<SearchTerm>()
        val enabledChanges = mutableListOf<Boolean>()
        var clearCount = 0

        override fun record(term: SearchTerm) {
            recorded += term
            onRecord(term)
            terms.value = listOf(term) + terms.value.filterNot { it == term }
        }

        override fun remove(term: SearchTerm) {
            removed += term
            terms.value = terms.value.filterNot { it == term }
        }

        override fun clear() {
            clearCount += 1
            terms.value = emptyList()
        }

        override fun setEnabled(enabled: Boolean) {
            enabledChanges += enabled
            this.enabled.value = enabled
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
