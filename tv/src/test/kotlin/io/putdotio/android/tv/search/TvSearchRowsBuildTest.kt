package io.putdotio.android.tv.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.LocalBuildDispatcher
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.search.searchState
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
class TvSearchRowsBuildTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val log = mutableListOf<String>()
    private val actions = TvSearchActions(
        onQueryChanged = {},
        onSubmit = {},
        onResult = {},
        onNextPage = { log += "next" },
        onRetry = {},
        recent = TvRecentSearchActions(onSearch = {}, onEdit = {}, onRetry = {}),
    )
    private val results = tvSearchResults()

    @Test
    fun enteringTheResultsWhileTheirRowsBuildLandsOnTheFirstRowOnceTheyShow() {
        val build = StandardTestDispatcher()
        val ready = SearchContent.Ready(
            SearchTerm("tears"),
            listOf(item(1, "Tears of Steel.webm", PutioFileType.VIDEO)),
            SearchPaging.Complete,
        )
        showBuilding(build) { searchState(ready, query = "tears") }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Play Tears of Steel.webm").assertDoesNotExist()
        compose.onNode(hasContentDescription("Loading") and isFocused()).assertExists()

        compose.runOnIdle { build.scheduler.advanceUntilIdle() }
        compose.onNodeWithContentDescription("Play Tears of Steel.webm").assertIsFocused()
    }

    @Test
    fun aNewQueryPagesOnlyOnceItsRowsShow() {
        val build = StandardTestDispatcher()
        fun page(term: String, cursor: String) = SearchContent.Ready(
            SearchTerm(term),
            (1L..40L).map { item(if (term == "old") it else 100 + it, "$term $it.txt", PutioFileType.TEXT) },
            SearchPaging.Available(FilesCursor(cursor)),
        )
        var state by mutableStateOf(searchState(page("old", "c1"), query = "old"))
        showBuilding(build) { state }
        compose.runOnIdle { build.scheduler.advanceUntilIdle() }
        assertEquals(emptyList<String>(), log)

        compose.runOnIdle { state = searchState(page("new", "c2"), query = "new") }
        compose.onNodeWithTag(TV_SEARCH_RESULTS_TAG).performScrollToIndex(40)
        assertEquals(emptyList<String>(), log)

        compose.runOnIdle { build.scheduler.advanceUntilIdle() }
        compose.onNodeWithText("new 40.txt").assertIsDisplayed()
        assertEquals(listOf("next"), log)
    }

    private fun showBuilding(build: TestDispatcher, state: () -> SearchState) {
        compose.setTvContent {
            CompositionLocalProvider(LocalBuildDispatcher provides build) {
                TvSearchScreen(state = state(), actions = actions, detail = tvSearchDetail(), results = results)
            }
        }
    }

    private fun searchState(content: SearchContent, query: String) = searchState(
        query = query,
        content = content,
        recentTerms = emptyList(),
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
