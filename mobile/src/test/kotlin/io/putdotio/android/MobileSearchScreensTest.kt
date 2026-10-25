package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
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
import io.putdotio.android.history.HistoryRequestId
import io.putdotio.android.history.HistoryState
import io.putdotio.android.history.historyState
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.search.searchState
import io.putdotio.sdk.files.PutioFileType
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.putdotio.android.search.MOBILE_HISTORY_LIST_TAG
import io.putdotio.android.search.MOBILE_SEARCH_RESULTS_TAG
import io.putdotio.android.search.MobileHistoryOverflow
import io.putdotio.android.search.MobileHistoryScreen
import io.putdotio.android.search.MobileSearchScreen

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileSearchScreensTest {
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
        val edits = mutableListOf<RecentSearchEdit>()
        setScreen(
            search = searchState(SearchContent.Idle, listOf(SearchTerm("documentary"))),
            actions = MobileSearchHistoryActions(onRecentSearch = searches::add, onRecentEdit = edits::add),
        )

        compose.onNodeWithText("documentary").performClick()
        compose.onNodeWithText("Clear").performClick()

        assertEquals(listOf(SearchTerm("documentary")), searches)
        assertEquals(listOf(RecentSearchEdit.Clear), edits)
    }

    @Test
    fun recentSearchRemovalIdentifiesTheTermToAccessibilityServices() {
        val edits = mutableListOf<RecentSearchEdit>()
        val first = SearchTerm("documentary")
        val second = SearchTerm("concert")
        setScreen(
            search = searchState(SearchContent.Idle, listOf(first, second)),
            actions = MobileSearchHistoryActions(onRecentEdit = edits::add),
        )

        compose.onAllNodesWithText("Remove").assertCountEquals(0)
        compose.onNodeWithContentDescription("Remove documentary from recent searches").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove concert from recent searches").performClick()
        assertEquals(listOf(RecentSearchEdit.Remove(second)), edits)
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
            HistoryItem(
                HistoryEventId(it),
                "2026-08-30T10:00:00Z",
                HistoryEventKind.File(HistoryFileId(it), "Sample $it"),
            )
        }
        setHistoryScreen(
            history = historyState(HistoryContent.Ready(items, HistoryPaging.Available(HistoryEventId(60L)))),
            actions = MobileSearchHistoryActions(onHistoryEvent = events::add),
        )

        compose.runOnIdle { assertEquals(emptyList<HistoryEvent>(), events) }
        compose.onNodeWithTag(MOBILE_HISTORY_LIST_TAG).performScrollToIndex(45)
        compose.runOnIdle { assertEquals(listOf<HistoryEvent>(HistoryEvent.LoadNextPage), events) }
    }

    @Test
    fun historyGroupsEventsAndConfirmsClear() {
        val events = mutableListOf<HistoryEvent>()
        val history =
            historyState(
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
        setHistoryScreen(history = history, actions = MobileSearchHistoryActions(onHistoryEvent = events::add))

        compose.onNodeWithText("movie.mkv").assertIsDisplayed()
        compose.onNodeWithText("Clear history?").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()

        assertEquals(listOf(HistoryEvent.ConfirmClear), events)
    }

    @Test
    fun clearHistorySitsInTheOverflowAndWaitsWhileAClearRuns() {
        val events = mutableListOf<HistoryEvent>()
        var clearing by mutableStateOf<HistoryClearing>(HistoryClearing.Idle)
        compose.setContent { PutioTheme { MobileHistoryOverflow(clearing, events::add) } }

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Clear history").performClick()
        assertEquals(listOf<HistoryEvent>(HistoryEvent.RequestClear), events)

        clearing = HistoryClearing.Clearing(HistoryRequestId(1L))
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Clear history").assertIsNotEnabled()
    }

    @Test
    fun zoneLessStampsFromOneDayShareADateHeaderAndShowTimes() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val history =
                historyState(
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
            setHistoryScreen(history = history)

            compose.onAllNodesWithText("Sep 9").assertCountEquals(1)
            compose.onAllNodesWithText("2026-09-09", substring = true).assertCountEquals(0)
            compose.onNodeWithText("3:25", substring = true).assertIsDisplayed()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun dateHeadersReadTodayYesterdayThenTheDate() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val stamps = listOf("2026-10-25", "2026-10-24", "2026-10-20", "2025-12-31").map { "${it}T09:00:00" }
            val items = stamps
                .mapIndexed { index, stamp ->
                    HistoryItem(
                        HistoryEventId(index + 1L),
                        stamp,
                        HistoryEventKind.File(HistoryFileId(index + 1L), "file-$index.mkv"),
                    )
                }
            setHistoryScreen(history = historyState(HistoryContent.Ready(items, HistoryPaging.Complete)))

            listOf("Today", "Yesterday", "Oct 20", "Dec 31, 2025").forEach { header ->
                compose.onNodeWithTag(MOBILE_HISTORY_LIST_TAG).performScrollToNode(hasText(header))
                compose.onNodeWithText(header).assertIsDisplayed()
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun eachEventNamesItsSubjectThenWhatHappenedAndWhen() {
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
            setHistoryScreen(history = historyState(HistoryContent.Ready(items, HistoryPaging.Complete)))

            listOf(
                "Harbor film.mp4" to "Uploaded · ",
                "Sample transfer" to "Transfer failed · ",
                "Old episode.mkv" to "Deleted to free space · ",
                "Sample feed" to "RSS filter paused · ",
                "Feed item" to "RSS transfer failed · ",
                "Callback transfer" to "Callback failed · ",
            ).forEach { (title, kind) ->
                compose.onNodeWithTag(MOBILE_HISTORY_LIST_TAG).performScrollToNode(hasText(title))
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText(kind, substring = true).assertIsDisplayed()
            }
            // A type with no copy names nothing and gives its time alone.
            compose.onNodeWithTag(MOBILE_HISTORY_LIST_TAG).performScrollToNode(hasText("No title"))
            compose.onNodeWithText("No title").assertIsDisplayed()
            compose.onAllNodesWithText(" · ", substring = true).assertCountEquals(6)
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
        setHistoryScreen(history = historyState(HistoryContent.Ready(items, HistoryPaging.Complete)))

        compose.onAllNodesWithText("No title").assertCountEquals(2)
        compose.onNodeWithText("Shared with you · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Transfer completed · ", substring = true).assertIsDisplayed()
    }

    @Test
    fun disabledHistoryPointsAtTheAccountToggle() {
        setHistoryScreen(history = historyState(HistoryContent.Disabled))

        compose.onNodeWithText("Turn on “Keep account history” in Account to see activity here.").assertIsDisplayed()
    }

    private fun setHistoryScreen(
        history: HistoryState,
        actions: MobileSearchHistoryActions = MobileSearchHistoryActions(),
    ) {
        compose.setContent {
            PutioTheme {
                MobileHistoryScreen(historyState = history, onHistoryEvent = actions.onHistoryEvent, today = TODAY)
            }
        }
    }

    private fun setScreen(
        search: SearchState = searchState(SearchContent.Idle),
        actions: MobileSearchHistoryActions = MobileSearchHistoryActions(),
    ) {
        compose.setContent {
            PutioTheme {
                MobileSearchScreen(
                    searchState = search,
                    recentSearchFailure = null,
                    onSearchQueryChanged = {},
                    onSearchSubmit = {},
                    onSearchResult = actions.onResult,
                    onSearchNextPage = actions.onNextPage,
                    onSearchRetry = {},
                    onRecentSearch = actions.onRecentSearch,
                    onRecentEdit = actions.onRecentEdit,
                    onRecentRetry = actions.onRecentRetry,
                )
            }
        }
    }

    private fun searchState(
        content: SearchContent,
        recentTerms: List<SearchTerm> = emptyList(),
    ) = searchState("", content, recentTerms, 1L)

    private fun fileItem() =
        FilesItem(
            id = FilesItemId(7L),
            parentId = FilesItemId(0L),
            name = "movie.mkv",
            type = PutioFileType.VIDEO,
            sizeBytes = 1_024L,
            createdAt = "2026-08-30T10:00:00Z",
        )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 25)
    }
}
