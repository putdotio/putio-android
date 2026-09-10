package io.putdotio.android.tv.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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

        assertEquals(listOf("query:tears"), log)
    }

    @Test
    fun recentChipsReplayTheTermAsTypedAndLongPressRemovesIt() {
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
