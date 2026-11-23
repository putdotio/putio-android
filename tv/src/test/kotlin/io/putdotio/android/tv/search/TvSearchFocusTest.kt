package io.putdotio.android.tv.search

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchRequestId
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.search.searchState
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvSearchFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun removingScrolledOffLastChipScrollsBackAndFocusesFirstChip() {
        var recent by mutableStateOf((1..25).map { SearchTerm("recent-search-term-$it") })
        val removing = TvSearchActions(
            onQueryChanged = {},
            onSubmit = {},
            onResult = {},
            onNextPage = {},
            onRetry = {},
            recent = TvRecentSearchActions(
                onSearch = {},
                onEdit = { edit ->
                    if (edit is RecentSearchEdit.Remove) recent = recent.filterNot { it == edit.term }
                },
                onRetry = {},
            ),
        )
        show(
            state = {
                searchState(
                    query = "",
                    content = SearchContent.Idle,
                    recentTerms = recent,
                    nextRequestValue = 5L,
                )
            },
            actions = removing,
        )
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Search again for recent-search-term-1")
            .assertIsFocused()
            .performKeyInput { repeat(24) { pressKey(Key.DirectionRight) } }
        compose.onAllNodesWithContentDescription("Search again for recent-search-term-1").assertCountEquals(0)
        compose.onNodeWithContentDescription("Search again for recent-search-term-25")
            .assertIsFocused()
            .performSemanticsAction(SemanticsActions.OnLongClick)

        compose.onNodeWithContentDescription("Search again for recent-search-term-1").assertIsFocused()
    }

    @Test
    fun pageTwoArrivingMidHandoffFocusesNewLastRow() {
        val page1 = (1..12).map { resultItem(it.toLong(), "row-$it.mkv") }
        val page2 = (13..35).map { resultItem(it.toLong(), "row-$it.mkv") }
        val failed = SearchPaging.Failed(
            FilesCursor("c1"),
            PutioFailure.Misconfigured(PutioConfigurationException("boom")),
        )
        var state by mutableStateOf(
            searchState(
                query = "r",
                content = SearchContent.Ready(SearchTerm("r"), page1, failed),
                recentTerms = emptyList(),
                nextRequestValue = 5L,
            ),
        )
        show(state = { state })
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            repeat(page1.size + 2) { pressKey(Key.DirectionDown) }
        }
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }

        compose.mainClock.autoAdvance = false
        val loading = SearchPaging.Loading(FilesCursor("c1"), SearchRequestId(2))
        state = searchState(
            query = "r",
            content = SearchContent.Ready(SearchTerm("r"), page1, loading),
            recentTerms = emptyList(),
            nextRequestValue = 5L,
        )
        compose.mainClock.advanceTimeByFrame()

        state = searchState(
            query = "r",
            content = SearchContent.Ready(SearchTerm("r"), page1 + page2, SearchPaging.Complete),
            recentTerms = emptyList(),
            nextRequestValue = 5L,
        )
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Play row-35.mkv").assertIsFocused()
    }

    private fun show(
        state: () -> SearchState,
        actions: TvSearchActions = TvSearchActions(
            onQueryChanged = {},
            onSubmit = {},
            onResult = {},
            onNextPage = {},
            onRetry = {},
            recent = TvRecentSearchActions(onSearch = {}, onEdit = {}, onRetry = {}),
        ),
    ) {
        compose.setTvContent {
            TvSearchScreen(
                state = state(),
                actions = actions,
                notice = null,
                sessionKey = null,
                detail = tvSearchDetail(emptyMap()),
                results = tvSearchResults(onFilter = {}, onPlay = { _, _ -> }),
            )
        }
    }

    private fun resultItem(id: Long, name: String) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesItemId(0L),
        name = name,
        type = PutioFileType.VIDEO,
        sizeBytes = 1_000L,
        createdAt = "2026-04-20T10:00:00Z",
    )
}
