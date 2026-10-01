package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.history.HistoryClearing
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryNoticeType
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.sdk.files.PutioFileType
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.putdotio.android.search.MOBILE_HISTORY_LIST_TAG
import io.putdotio.android.search.MOBILE_SEARCH_RESULTS_TAG
import io.putdotio.android.search.MobileSearchHistoryScreen

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileSearchHistoryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun searchResultUsesTheSharedFileRowAndOpens() {
        val item = fileItem()
        val opened = mutableListOf<FilesItem>()
        setScreen(
            search = searchState(SearchContent.Ready(SearchTerm("movie"), listOf(item), SearchPaging.Complete)),
            actions = MobileSearchHistoryActions(onResult = opened::add),
        )

        compose.onNodeWithText(item.name).assertIsDisplayed().performClick()

        assertEquals(listOf(item), opened)
    }

    @Test
    fun recentSearchCanRunAndBeCleared() {
        val searches = mutableListOf<SearchTerm>()
        val edits = mutableListOf<io.putdotio.android.search.RecentSearchEdit>()
        setScreen(
            search = searchState(SearchContent.Idle, listOf(SearchTerm("documentary"))),
            actions = MobileSearchHistoryActions(onRecentSearch = searches::add, onRecentEdit = edits::add),
        )

        compose.onNodeWithText("documentary").performClick()
        compose.onNodeWithText("Clear").performClick()

        assertEquals(listOf(SearchTerm("documentary")), searches)
        assertEquals(listOf(io.putdotio.android.search.RecentSearchEdit.Clear), edits)
    }

    @Test
    fun recentSearchRemovalIdentifiesTheTermToAccessibilityServices() {
        val edits = mutableListOf<io.putdotio.android.search.RecentSearchEdit>()
        val first = SearchTerm("documentary")
        val second = SearchTerm("concert")
        setScreen(
            search = searchState(SearchContent.Idle, listOf(first, second)),
            actions = MobileSearchHistoryActions(onRecentEdit = edits::add),
        )

        compose.onAllNodesWithText("Remove").assertCountEquals(0)
        compose.onNodeWithContentDescription("Remove documentary from recent searches").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove concert from recent searches").performClick()
        assertEquals(listOf(io.putdotio.android.search.RecentSearchEdit.Remove(second)), edits)
    }

    @Test
    fun recentSearchFailureIsVisibleAndCanBeRetriedWithoutBlockingSearch() {
        var retries = 0
        setScreen(
            search = searchState(SearchContent.Idle, listOf(SearchTerm("documentary"))),
            recentSearchFailure = FilesFailure.Unexpected(IllegalStateException("offline")),
            actions = MobileSearchHistoryActions(onRecentRetry = { retries += 1 }),
        )

        compose.onNodeWithText("Couldn’t update recent searches").assertIsDisplayed()
        compose.onNodeWithText("documentary").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun searchResultsLoadTheNextPageNearTheEndWithoutATap() {
        val items = (1L..60L).map { fileItem().copy(id = FilesItemId(it), name = "Sample $it.mp4") }
        var loads = 0
        setScreen(
            search = searchState(
                SearchContent.Ready(SearchTerm("sample"), items, SearchPaging.Available(FilesCursor("next"))),
            ),
            actions = MobileSearchHistoryActions(onNextPage = { loads++ }),
        )

        compose.runOnIdle { assertEquals("the top of a long page asks for nothing", 0, loads) }
        compose.onNodeWithTag(MOBILE_SEARCH_RESULTS_TAG).performScrollToIndex(40)
        compose.runOnIdle { assertEquals(1, loads) }
    }

    @Test
    fun historyLoadsTheNextPageNearTheEndWithoutATap() {
        val events = mutableListOf<HistoryEvent>()
        val items = (1L..60L).map {
            HistoryItem(HistoryEventId(it), "2026-08-30T10:00:00Z", HistoryEventKind.File(HistoryFileId(it), "Sample $it"))
        }
        setScreen(
            history = HistoryState(HistoryContent.Ready(items, HistoryPaging.Available(HistoryEventId(60L)))),
            actions = MobileSearchHistoryActions(onHistoryEvent = events::add),
        )

        compose.onNodeWithText("History").performClick()
        compose.runOnIdle { assertEquals(emptyList<HistoryEvent>(), events) }
        compose.onNodeWithTag(MOBILE_HISTORY_LIST_TAG).performScrollToIndex(45)
        compose.runOnIdle { assertEquals(listOf<HistoryEvent>(HistoryEvent.LoadNextPage), events) }
    }

    @Test
    fun historyGroupsEventsAndConfirmsClear() {
        val events = mutableListOf<HistoryEvent>()
        val history =
            HistoryState(
                content =
                    HistoryContent.Ready(
                        listOf(
                            HistoryItem(
                                HistoryEventId(1L),
                                "2026-08-30T10:00:00Z",
                                HistoryEventKind.File(HistoryFileId(7L), "movie.mkv"),
                            ),
                        ),
                        HistoryPaging.Complete,
                    ),
                clearing = HistoryClearing.AwaitingConfirmation,
            )
        setScreen(history = history, actions = MobileSearchHistoryActions(onHistoryEvent = events::add))

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("movie.mkv").assertIsDisplayed()
        compose.onNodeWithText("Clear history?").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()

        assertEquals(listOf(HistoryEvent.ConfirmClear), events)
    }

    @Test
    fun zoneLessStampsFromOneDayShareADateHeaderAndShowTimes() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val history =
                HistoryState(
                    content =
                        HistoryContent.Ready(
                            listOf(
                                HistoryItem(
                                    HistoryEventId(1L),
                                    "2026-09-09T15:25:32",
                                    HistoryEventKind.File(HistoryFileId(7L), "movie.mkv"),
                                ),
                                HistoryItem(
                                    HistoryEventId(2L),
                                    "2026-09-09T09:05:00",
                                    HistoryEventKind.File(HistoryFileId(8L), "show.mkv"),
                                ),
                            ),
                            HistoryPaging.Complete,
                        ),
                )
            setScreen(history = history)

            compose.onNodeWithText("History").performClick()
            compose.onAllNodesWithText("2026-09-09").assertCountEquals(1)
            compose.onAllNodesWithText("2026-09-09T", substring = true).assertCountEquals(0)
            compose.onNodeWithText("3:25", substring = true).assertIsDisplayed()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun completedTransferWithAFileOpensThroughHistoryNavigation() {
        val events = mutableListOf<HistoryEvent>()
        val history =
            HistoryState(
                content =
                    HistoryContent.Ready(
                        listOf(
                            HistoryItem(
                                HistoryEventId(2L),
                                "2026-08-30T10:00:00Z",
                                HistoryEventKind.Transfer(
                                    transferId = null,
                                    fileId = HistoryFileId(32L),
                                    name = "movie.mkv",
                                ),
                            ),
                        ),
                        HistoryPaging.Complete,
                    ),
            )
        setScreen(history = history, actions = MobileSearchHistoryActions(onHistoryEvent = events::add))

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("movie.mkv").performClick()

        assertEquals(listOf(HistoryEvent.OpenFile(HistoryFileId(32L))), events)
    }

    @Test
    fun eachEventTypeReadsAsIosStatesItWithOnlyItsTime() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val kinds = listOf(
                HistoryEventKind.Notice(HistoryNoticeType.Upload, "Harbor film.mp4"),
                HistoryEventKind.Notice(HistoryNoticeType.TransferError, "Sample transfer"),
                HistoryEventKind.Notice(HistoryNoticeType.RssFileDeleted, "Old episode.mkv"),
                HistoryEventKind.Notice(HistoryNoticeType.RssFilterPaused, "Sample feed"),
                HistoryEventKind.Notice(HistoryNoticeType.RssTransferError, "Feed item"),
                HistoryEventKind.Notice(HistoryNoticeType.TransferCallbackError, "Callback transfer"),
                HistoryEventKind.Other("zip_created"),
            )
            val items = kinds.mapIndexed { index, kind ->
                HistoryItem(HistoryEventId(index + 1L), "2026-09-09T15:25:32", kind)
            }
            setScreen(history = HistoryState(HistoryContent.Ready(items, HistoryPaging.Complete)))

            compose.onNodeWithText("History").performClick()
            // iOS details these rows with their time alone; only shared files and transfers name a kind.
            compose.onAllNodesWithText(" · ", substring = true).assertCountEquals(0)
            listOf(
                "Harbor film.mp4",
                "Error in transfer Sample transfer",
                "We had to delete Old episode.mkv per your instructions, since there wasn’t enough free space.",
                "Sample feed is paused because we couldn’t reach the source",
                "Error in transfer from RSS for Feed item",
                "Error in transfer callback for Callback transfer",
                "No title",
            ).forEach { title ->
                compose.onNodeWithTag(MOBILE_HISTORY_LIST_TAG).performScrollToNode(hasText(title))
                compose.onNodeWithText(title).assertIsDisplayed()
            }
            compose.onAllNodesWithText("zip_created", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("Activity", substring = true).assertCountEquals(0)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun namelessSharedFilesAndTransfersReadNoTitle() {
        val items = listOf(
            HistoryEventKind.File(id = null, name = null),
            HistoryEventKind.Transfer(transferId = null, fileId = null, name = null),
        ).mapIndexed { index, kind -> HistoryItem(HistoryEventId(index + 1L), "2026-09-09T15:25:32", kind) }
        setScreen(history = HistoryState(HistoryContent.Ready(items, HistoryPaging.Complete)))

        compose.onNodeWithText("History").performClick()
        compose.onAllNodesWithText("No title").assertCountEquals(2)
        compose.onNodeWithText("Shared file · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Completed transfer · ", substring = true).assertIsDisplayed()
    }

    @Test
    fun disabledHistoryPointsAtTheAccountToggle() {
        setScreen(history = HistoryState(HistoryContent.Disabled))

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Turn on “Keep account history” in Account to see activity here.").assertIsDisplayed()
    }

    private fun setScreen(
        search: SearchState = searchState(SearchContent.Idle),
        history: HistoryState = HistoryState(HistoryContent.Disabled),
        recentSearchFailure: FilesFailure? = null,
        actions: MobileSearchHistoryActions = MobileSearchHistoryActions(),
    ) {
        compose.setContent {
            PutioTheme {
                MobileSearchHistoryScreen(
                    searchState = search,
                    historyState = history,
                    recentSearchFailure = recentSearchFailure,
                    onSearchQueryChanged = {},
                    onSearchSubmit = {},
                    onSearchResult = actions.onResult,
                    onSearchNextPage = actions.onNextPage,
                    onSearchRetry = {},
                    onRecentSearch = actions.onRecentSearch,
                    onRecentEdit = actions.onRecentEdit,
                    onRecentRetry = actions.onRecentRetry,
                    onHistoryEvent = actions.onHistoryEvent,
                )
            }
        }
    }

    private fun searchState(
        content: SearchContent,
        recentTerms: List<SearchTerm> = emptyList(),
    ) = SearchState("", content, recentTerms, emptySet(), 1L)

    private fun fileItem() =
        FilesItem(
            id = FilesItemId(7L),
            parentId = FilesItemId(0L),
            name = "movie.mkv",
            type = PutioFileType.VIDEO,
            sizeBytes = 1_024L,
            createdAt = "2026-08-30T10:00:00Z",
        )
}
