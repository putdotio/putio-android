package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
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
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
                    onSearchNextPage = {},
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
