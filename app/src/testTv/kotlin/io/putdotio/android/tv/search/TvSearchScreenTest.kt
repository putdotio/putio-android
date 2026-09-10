package io.putdotio.android.tv.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchRequestId
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvSearchScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val log = mutableListOf<String>()
    private val actions = TvSearchActions(
        onQueryChanged = { log += "query:$it" },
        onSubmit = { log += "submit" },
        onResult = { log += "open:${it.name}" },
        onNextPage = { log += "next" },
        onRetry = { log += "retry" },
        onRecentSearch = { log += "recent:${it.value}" },
        onRecentEdit = { log += "edit:$it" },
        onRecentRetry = { log += "recent-retry" },
    )

    @Test
    fun idleShowsThePlaceholderAndTypingReportsTheQuery() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSearchScreen(state = searchState(SearchContent.Idle), actions = actions)
            }
        }

        compose.onNodeWithText("Tap to start typing").assertIsDisplayed()
        compose.onNodeWithText("Search your files by name or keyword.").assertIsDisplayed()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused().performTextInput("tears")

        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assert(hasText("tears"))
        assertEquals(listOf("query:tears"), log)
    }

    @Test
    fun recentChipsReplayTheTermAsTyped() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSearchScreen(
                    state = searchState(SearchContent.Idle, recent = listOf(SearchTerm("Tears Of Steel"), SearchTerm("sintel"))),
                    actions = actions,
                )
            }
        }

        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Search again for Tears Of Steel").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription("Search again for sintel").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf("recent:sintel"), log)
    }

    @Test
    fun resultsAreStandardRowsReachedWithDownAndCenterOpensOne() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSearchScreen(
                    state = searchState(
                        SearchContent.Ready(
                            SearchTerm("tears"),
                            listOf(item(1, "Tears of Steel.webm", PutioFileType.VIDEO), item(2, "tears", PutioFileType.FOLDER)),
                            SearchPaging.Available(FilesCursor("c1")),
                        ),
                        recent = listOf(SearchTerm("tears")),
                        query = "tears",
                    ),
                    actions = actions,
                )
            }
        }

        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Open Tears of Steel.webm").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Load more") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithContentDescription("Open tears").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf("next", "open:tears"), log)
    }

    @Test
    fun loadingEmptyAndFailedStatesKeepTheFieldReachable() {
        var state by mutableStateOf(searchState(SearchContent.Loading(SearchTerm("x"), SearchRequestId(1)), query = "x"))
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSearchScreen(state = state, actions = actions)
            }
        }
        compose.onNodeWithText("Searching").assertIsDisplayed()

        state = searchState(SearchContent.Empty(SearchTerm("x"), SearchPaging.Complete), query = "x")
        compose.onNodeWithText("No results found.").assertIsDisplayed()

        state = searchState(
            SearchContent.Failed(SearchTerm("x"), FilesFailure.Misconfigured(PutioConfigurationException("boom"))),
            query = "x",
        )
        compose.onNodeWithText("Couldn’t search").assertIsDisplayed()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        state = searchState(SearchContent.Loading(SearchTerm("x"), SearchRequestId(2)), query = "x")
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()
        assertEquals(listOf("retry"), log)
    }

    @Test
    fun aRecentSearchFailureOffersRetryAndABlockedOpenExplainsItself() {
        var notice by mutableStateOf<FilesFailure?>(FilesFailure.Misconfigured(PutioConfigurationException("boom")))
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSearchScreen(state = searchState(SearchContent.Idle), actions = actions, notice = notice)
            }
        }
        compose.onNodeWithText("Couldn’t update recent searches.").assertIsDisplayed()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf("recent-retry"), log)

        notice = FilesFailure.NavigationBlocked
        compose.onNodeWithText("This item can’t be opened right now. Check Files, then try again.").assertIsDisplayed()
    }

    @Test
    fun loadMoreKeepsFocusThroughLoadingAndHandsOffToTheLastRowWhenComplete() {
        var state by mutableStateOf(
            searchState(
                SearchContent.Ready(SearchTerm("t"), listOf(item(1, "one.mkv", PutioFileType.VIDEO)), SearchPaging.Available(FilesCursor("c1"))),
                query = "t",
            ),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSearchScreen(state = state, actions = actions)
            }
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Load more") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        state = searchState(
            SearchContent.Ready(SearchTerm("t"), listOf(item(1, "one.mkv", PutioFileType.VIDEO)), SearchPaging.Loading(FilesCursor("c1"), SearchRequestId(2))),
            query = "t",
        )
        compose.onNode(hasText("Loading more results") and hasClickAction()).assertIsFocused()

        state = searchState(
            SearchContent.Ready(SearchTerm("t"), listOf(item(1, "one.mkv", PutioFileType.VIDEO)), SearchPaging.Failed(FilesCursor("c1"), FilesFailure.Misconfigured(PutioConfigurationException("boom")))),
            query = "t",
        )
        compose.onNodeWithText("Couldn’t load more results.").assertIsDisplayed()
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }

        state = searchState(
            SearchContent.Ready(SearchTerm("t"), listOf(item(1, "one.mkv", PutioFileType.VIDEO), item(2, "two.mkv", PutioFileType.VIDEO)), SearchPaging.Complete),
            query = "t",
        )
        compose.onNodeWithContentDescription("Open two.mkv").assertIsFocused()
        assertEquals(listOf("next", "retry"), log)
    }

    @Test
    fun aLastPageLandingAfterLeavingLoadMoreDoesNotStealFocus() {
        var state by mutableStateOf(
            searchState(
                SearchContent.Ready(SearchTerm("t"), listOf(item(1, "one.mkv", PutioFileType.VIDEO)), SearchPaging.Available(FilesCursor("c1"))),
                query = "t",
            ),
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSearchScreen(state = state, actions = actions)
            }
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Load more") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        state = searchState(
            SearchContent.Ready(SearchTerm("t"), listOf(item(1, "one.mkv", PutioFileType.VIDEO)), SearchPaging.Loading(FilesCursor("c1"), SearchRequestId(2))),
            query = "t",
        )
        compose.onNode(hasText("Loading more results") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()

        state = searchState(
            SearchContent.Ready(SearchTerm("t"), listOf(item(1, "one.mkv", PutioFileType.VIDEO), item(2, "two.mkv", PutioFileType.VIDEO)), SearchPaging.Complete),
            query = "t",
        )
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()
        assertEquals(listOf("next"), log)
    }

    @Test
    fun longPressOnAChipRemovesItAndFocusStaysOnTheChips() {
        var recent by mutableStateOf(listOf(SearchTerm("tears"), SearchTerm("sintel")))
        val removing = TvSearchActions(
            onQueryChanged = {}, onSubmit = {}, onResult = {}, onNextPage = {}, onRetry = {},
            onRecentSearch = { log += "recent:${it.value}" },
            onRecentEdit = { edit ->
                log += "edit:$edit"
                if (edit is RecentSearchEdit.Remove) recent = recent.filterNot { it == edit.term }
            },
            onRecentRetry = {},
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSearchScreen(state = searchState(SearchContent.Idle, recent = recent), actions = removing)
            }
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        // The D-pad hold-to-long-press timing belongs to the TV surface; the wiring is what
        // this proves, so the semantics action stands in for the held Center.
        compose.onNodeWithContentDescription("Search again for tears").assertIsFocused()
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onAllNodesWithContentDescription("Search again for tears").assertCountEquals(0)
        assertEquals(listOf("edit:Remove(term=SearchTerm(value=tears))"), log)

        // The remaining chip picks up focus; removing it too hands focus to the field.
        compose.onNodeWithContentDescription("Search again for sintel").assertIsFocused()
            .performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onAllNodesWithContentDescription("Search again for sintel").assertCountEquals(0)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()
    }

    @Test
    fun aTermDroppedByTheCapWhileFocusIsOnTheFieldDoesNotPullFocusBack() {
        var recent by mutableStateOf((1..5).map { SearchTerm("term-$it") })
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSearchScreen(state = searchState(SearchContent.Idle, recent = recent), actions = actions)
            }
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Search again for term-1").assertIsFocused().performKeyInput {
            repeat(4) { pressKey(Key.DirectionRight) }
        }
        compose.onNodeWithContentDescription("Search again for term-5").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()

        // A new search records term-6 and the cap drops term-5, the chip focused last.
        recent = listOf(SearchTerm("term-6")) + recent.dropLast(1)
        compose.onAllNodesWithContentDescription("Search again for term-5").assertCountEquals(0)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()
    }

    @Test
    fun aChipReplayRewritesTheFieldAndAStaleQueryDoesNotOverwriteTyping() {
        var state by mutableStateOf(searchState(SearchContent.Idle, recent = listOf(SearchTerm("Tears Of Steel"))))
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSearchScreen(state = state, actions = actions)
            }
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Search again for Tears Of Steel").assertIsFocused()
        state = searchState(SearchContent.Idle, recent = state.recentTerms, query = "Tears Of Steel")
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assert(hasText("Tears Of Steel"))

        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performTextInput("!")
        // The controller reporting the previous text back must not undo the edit.
        state = searchState(SearchContent.Idle, recent = state.recentTerms, query = "Tears Of Steel")
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assert(hasText("Tears Of Steel!"))
        assertEquals(listOf("query:Tears Of Steel!"), log)
    }

    @Test
    fun aSuccessfulRecentRetryHandsFocusToTheField() {
        var notice by mutableStateOf<FilesFailure?>(FilesFailure.Misconfigured(PutioConfigurationException("boom")))
        val retrying = TvSearchActions(
            onQueryChanged = {}, onSubmit = {}, onResult = {}, onNextPage = {}, onRetry = {},
            onRecentSearch = {}, onRecentEdit = {}, onRecentRetry = { notice = null },
        )
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSearchScreen(state = searchState(SearchContent.Idle), actions = retrying, notice = notice)
            }
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()
    }

    private fun searchState(
        content: SearchContent,
        recent: List<SearchTerm> = emptyList(),
        query: String = "",
    ) = SearchState(
        query = query,
        content = content,
        recentTerms = recent,
        consumedCursors = emptySet(),
        nextRequestValue = 5L,
    )

    private fun item(id: Long, name: String, type: PutioFileType) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesItemId(0L),
        name = name,
        type = type,
        sizeBytes = 1_000L,
        createdAt = "2026-04-20T10:00:00Z",
    )
}
