package io.putdotio.android.search

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPlaybackProgress
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
        runBlocking<Unit> {
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

            controller.use {
                assertTrue(controller.updateQuery("  movie  "))
                assertEquals(SEARCH_DEBOUNCE_MILLIS, withTimeout(TIMEOUT) { delayStarted.await() })
                val debouncing = controller.state.value.content as SearchContent.Debouncing
                assertEquals(SearchTerm("movie"), debouncing.term)

                assertTrue(controller.updateQuery("   "))
                withTimeout(TIMEOUT) { delayCancelled.await() }

                assertTrue(controller.state.value.content is SearchContent.Idle)
                assertEquals(0, searches)
            }
        }

    @Test
    fun ignoresAStaleSearchThatReturnsAfterCancellation() =
        runBlocking<Unit> {
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

            controller.use {
                controller.updateQuery("old")
                withTimeout(TIMEOUT) { oldStarted.await() }
                controller.updateQuery("new")

                val ready = controller.awaitContent<SearchContent.Ready>()
                assertEquals(SearchTerm("new"), ready.term)
                assertEquals(listOf("new.mkv"), ready.items.map(FilesItem::name))
            }
        }

    @Test
    fun submitTrimsAndRecordsHistoryBeforeSearching() =
        runBlocking<Unit> {
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

            controller.use {
                controller.updateQuery("  documentary  ")
                assertTrue(controller.submit())
                controller.awaitContent<SearchContent.Empty>()

                assertEquals("documentary", controller.state.value.query)
                assertEquals(listOf("record:documentary", "search:documentary"), effects)
            }
        }

    @Test
    fun searchesSettledWhileTypingSlowlyAreNotRecorded() =
        runBlocking<Unit> {
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

            controller.use {
                // Each keystroke lands after the debounce has settled, as D-pad typing on TV does.
                listOf("m", "ma", "map").forEach { query ->
                    controller.updateQuery(query)
                    controller.awaitContent<SearchContent.Ready> { it.term == SearchTerm(query) }
                }

                assertEquals(listOf("m", "ma", "map"), searched)
                assertEquals(emptyList<SearchTerm>(), store.recorded)
            }
        }

    @Test
    fun retriesPagingAndStopsARepeatedCursor() =
        runBlocking<Unit> {
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

            controller.use {
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
            }
        }

    @Test
    fun openingAVisibleResultEmitsItAndRecordsTheSearchItCameFrom() =
        runBlocking<Unit> {
            val store = FakeRecentSearchStore()
            val controller = controller(repository { success(item(9L, "movie.mkv")) }, store = store)

            controller.use {
                controller.updateQuery("  movie ")
                controller.awaitContent<SearchContent.Ready>()
                val output = async { withTimeout(TIMEOUT) { controller.outputs.first() } }
                assertEquals(emptyList<SearchTerm>(), store.recorded)

                assertFalse(controller.openResult(FilesItemId(8L)))
                assertEquals(emptyList<SearchTerm>(), store.recorded)
                assertTrue(controller.openResult(FilesItemId(9L)))
                controller.updateQuery("different query")

                assertEquals(SearchOutput.OpenResult(item(9L, "movie.mkv")), output.await())
                assertEquals(listOf(SearchTerm("movie")), store.recorded)
            }
        }

    @Test
    fun aSavedPositionReachesTheResultShowingThatMedia() =
        runBlocking<Unit> {
            val watched = item(9L, "movie.mkv").copy(playback = FilesPlaybackProgress(5_400.0, null))
            val controller = controller(repository { success(watched, folder(8L, "movie")) })

            controller.use {
                controller.updateQuery("movie")
                controller.awaitContent<SearchContent.Ready>()

                // Marked unwatched from its actions sheet: the result reads unwatched without a new search.
                assertTrue(controller.positionSaved(FilesItemId(9L), 0.0))
                val items = (controller.state.value.content as SearchContent.Ready).items
                assertEquals(FilesPlaybackProgress(0.0, null), items.single { it.id.value == 9L }.playback)
                assertEquals(null, items.single { it.id.value == 8L }.playback)
                assertFalse(controller.positionSaved(FilesItemId(8L), 0.0))
                assertFalse(controller.positionSaved(FilesItemId(7L), 0.0))
            }
            assertFalse(controller.positionSaved(FilesItemId(9L), 60.0))
        }

    @Test
    fun mirrorsRecentTermsAndDelegatesRemoveAndClear() =
        runBlocking<Unit> {
            val store = FakeRecentSearchStore(listOf(SearchTerm("movie"), SearchTerm("shows")))
            val controller = controller(repository { success() }, store = store)

            controller.use {
                assertEquals(store.terms.value, controller.state.value.recentTerms)
                assertTrue(controller.editRecentSearches(RecentSearchEdit.Remove(SearchTerm("movie"))))
                controller.awaitRecentTerms(listOf(SearchTerm("shows")))
                assertEquals(listOf(SearchTerm("movie")), store.removed)

                assertTrue(controller.editRecentSearches(RecentSearchEdit.Clear))
                controller.awaitRecentTerms(emptyList())
                assertEquals(1, store.clearCount)
                assertFalse(controller.editRecentSearches(RecentSearchEdit.Clear))
            }
        }

    @Test
    fun mirrorsTheHistorySettingAndChangesItOnlyOnceLoaded() =
        runBlocking<Unit> {
            val store = FakeRecentSearchStore(listOf(SearchTerm("movie")), enabled = null)
            val controller = controller(repository { success() }, store = store)

            controller.use {
                assertEquals(null, controller.state.value.recentSearchesEnabled)
                assertFalse(controller.editRecentSearches(RecentSearchEdit.SetEnabled(false)))

                store.enabled.value = true
                withTimeout(TIMEOUT) { controller.state.first { it.recentSearchesEnabled == true } }
                assertFalse(controller.editRecentSearches(RecentSearchEdit.SetEnabled(true)))
                assertTrue(controller.editRecentSearches(RecentSearchEdit.SetEnabled(false)))
                withTimeout(TIMEOUT) { controller.state.first { it.recentSearchesEnabled == false } }

                assertEquals(listOf(false), store.enabledChanges)
            }
        }

    @Test
    fun folderScopeTravelsInTheQueryButRecentSearchesKeepTheBareTerm() =
        runBlocking<Unit> {
            val queried = mutableListOf<SearchTerm>()
            val store = FakeRecentSearchStore()
            val repository = repository { term ->
                queried += term
                success(item(1L, "S04E01.mkv"))
            }
            val controller = controller(repository, store)

            controller.use {
                controller.scope = SearchScope(FilesItemId(42L), "Blue Lights")
                controller.updateQuery("S04")
                assertTrue(controller.submit())
                controller.awaitContent<SearchContent.Ready>()

                assertEquals(SearchTerm("S04 in:42"), queried.last())
                assertEquals(listOf(SearchTerm("S04")), store.recorded)
                assertEquals(SearchTerm("S04"), (controller.state.value.content as SearchContent.Ready).term)

                // Leaving the folder's scope starts over, so results never mix scopes.
                controller.scope = null
                assertEquals("", controller.state.value.query)
                assertTrue(controller.state.value.content is SearchContent.Idle)
                controller.updateQuery("S04")
                assertTrue(controller.submit())
                controller.awaitContent<SearchContent.Ready>()
                assertEquals(SearchTerm("S04"), queried.last())
            }
        }

    @Test
    fun aTypeChipSearchesAgainAtOnceUnderItsTypeWithoutKeepingTheTerm() =
        runBlocking<Unit> {
            val searched = mutableListOf<Pair<SearchTerm, SearchFilter>>()
            val store = FakeRecentSearchStore()
            val repository = filteringRepository { term, filter ->
                searched += term to filter
                success(item(1L, "movie.mkv"))
            }
            val controller = controller(repository, store)

            controller.use {
                // With an empty field a chip only waits for the query.
                controller.filter = SearchFilter.Folders
                assertEquals(SearchFilter.Folders, controller.state.value.filter)
                assertTrue(controller.state.value.content is SearchContent.Idle)
                assertEquals(emptyList<Pair<SearchTerm, SearchFilter>>(), searched)

                controller.updateQuery("movie")
                assertTrue(controller.submit())
                // The only result is a video, so Folders finds nothing.
                controller.awaitContent<SearchContent.Empty>()
                controller.filter = SearchFilter.Videos
                // The chip in force is no change, so it searches no third time.
                controller.filter = SearchFilter.Videos
                controller.awaitContent<SearchContent.Ready>()

                assertEquals(
                    listOf(SearchTerm("movie") to SearchFilter.Folders, SearchTerm("movie") to SearchFilter.Videos),
                    searched,
                )
                assertEquals(listOf(SearchTerm("movie")), store.recorded)
            }
        }

    @Test
    fun subtitlesKeepOnlySubtitleExtensionsAndAnEmptyPageAsksForTheNext() =
        runBlocking<Unit> {
            val repository =
                object : SearchRepository {
                    override suspend fun search(term: SearchTerm): PutioResult<SearchPage> =
                        error("A filtered search names its filter")

                    override suspend fun search(term: SearchTerm, filter: SearchFilter): PutioResult<SearchPage> =
                        success(item(1L, "Sintel.mkv"), folder(2L, "Sintel.srt"), cursor = "next")

                    override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage> =
                        success(item(3L, "Sintel.en.SRT"), item(4L, "Sintel.vtt"), item(5L, "Sintel.nfo"))
                }
            val controller = controller(repository)

            controller.use {
                controller.filter = SearchFilter.Subtitles
                controller.updateQuery("sintel")
                controller.submit()
                // Nothing on the first page is a subtitle, so it reads as empty with a next page.
                assertTrue(controller.awaitContent<SearchContent.Empty>().paging is SearchPaging.Available)
                yield()
                assertTrue(controller.loadNextPage())

                val ready = controller.awaitContent<SearchContent.Ready>()
                assertEquals(listOf("Sintel.en.SRT", "Sintel.vtt"), ready.items.map(FilesItem::name))
            }
        }

    @Test
    fun anotherScopeStartsOverOnEveryType() =
        runBlocking<Unit> {
            val controller = controller(repository { success(item(1L, "movie.mkv")) })

            controller.use {
                controller.filter = SearchFilter.Audio
                controller.scope = SearchScope(FilesItemId(42L), "Music")
                assertEquals(SearchFilter.All, controller.state.value.filter)
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

    private fun filteringRepository(
        search: suspend (SearchTerm, SearchFilter) -> PutioResult<SearchPage>,
    ): SearchRepository =
        object : SearchRepository {
            override suspend fun search(term: SearchTerm): PutioResult<SearchPage> =
                error("A filtered search names its filter")

            override suspend fun search(term: SearchTerm, filter: SearchFilter): PutioResult<SearchPage> =
                search(term, filter)

            override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage> =
                error("Unexpected continuation")
        }

    private fun folder(id: Long, name: String): FilesItem = item(id, name).copy(type = PutioFileType.FOLDER)

    private suspend inline fun <reified T : SearchContent> SearchController.awaitContent(
        noinline predicate: (T) -> Boolean = { true },
    ): T =
        withTimeout(TIMEOUT) {
            state.first { it.content is T && predicate(it.content) }.content as T
        }

    private suspend inline fun <reified T : SearchPaging> SearchController.awaitPaging(): T =
        withTimeout(TIMEOUT) { state.first { it.content.pagingOrNull() is T }.content.pagingOrNull() as T }

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
