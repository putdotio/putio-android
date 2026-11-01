package io.putdotio.android.tv.search

import androidx.compose.runtime.getValue
import io.putdotio.android.tv.setTvContent
import io.putdotio.android.tv.pressCenter
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
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.FilesFailure
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.search.SearchFilter
import io.putdotio.android.tv.files.TV_FILES_DETAIL_PRIMARY_TAG
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isSelected
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchPaging
import io.putdotio.android.search.SearchRequestId
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.search.searchState
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
        recent = TvRecentSearchActions(
            onSearch = { log += "recent:${it.value}" },
            onEdit = { log += "edit:$it" },
            onRetry = { log += "recent-retry" },
        ),
    )
    private val results = tvSearchResults(
        onFilter = { log += "filter:$it" },
        onPlay = { item, start -> log += "play:${item.name}:$start" },
    )

    @Test
    fun idleShowsThePlaceholderAndTypingReportsTheQuery() {
        show(state = { searchState(SearchContent.Idle) })

        compose.onNodeWithText("Tap to start typing").assertIsDisplayed()
        compose.onNodeWithText("Search your files by name or keyword.").assertIsDisplayed()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused().performTextInput("tears")

        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assert(hasText("tears"))
        assertEquals(listOf("query:tears"), log)
    }

    @Test
    fun resultsAreStandardRowsReachedWithDownAndCenterOpensOne() {
        show(
            state = {
                searchState(
                    SearchContent.Ready(
                        SearchTerm("tears"),
                        listOf(
                            item(1, "Tears of Steel.webm", PutioFileType.VIDEO),
                            item(2, "tears", PutioFileType.FOLDER),
                        ),
                        SearchPaging.Available(FilesCursor("c1")),
                    ),
                    recent = listOf(SearchTerm("tears")),
                    query = "tears",
                )
            },
        )

        compose.onAllNodesWithText("Folders").assertCountEquals(2)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Open tears").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Play Tears of Steel.webm").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Load more") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithContentDescription("Play Tears of Steel.webm").assertIsFocused().performKeyInput {
            pressCenter()
        }
        assertEquals(listOf("next", "open:Tears of Steel.webm"), log)
    }

    @Test
    fun loadingEmptyAndFailedStatesKeepTheFieldReachable() {
        var state by mutableStateOf(
            searchState(SearchContent.Loading(SearchTerm("x"), SearchRequestId(1)), query = "x"),
        )
        show(state = { state })
        compose.onNodeWithContentDescription("Loading").assertIsDisplayed()

        state = searchState(SearchContent.Empty(SearchTerm("x"), SearchPaging.Complete), query = "x")
        compose.onNodeWithText("Nothing matches.").assertIsDisplayed()
        compose.onNodeWithText("No files or folders named “x”. Try fewer words.").assertIsDisplayed()

        state = searchState(
            SearchContent.Failed(SearchTerm("x"), PutioFailure.Misconfigured(PutioConfigurationException("boom"))),
            query = "x",
        )
        compose.onNodeWithText("Couldn’t search.").assertIsDisplayed()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        state = searchState(SearchContent.Loading(SearchTerm("x"), SearchRequestId(2)), query = "x")
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()
        assertEquals(listOf("retry"), log)
    }

    @Test
    fun anEmptyPageWhoseLoadMoreFindsNothingHandsFocusToTheField() {
        var state by mutableStateOf(
            searchState(SearchContent.Empty(SearchTerm("x"), SearchPaging.Available(FilesCursor("c1"))), query = "x"),
        )
        show(state = { state })
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Load more") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }

        state = searchState(SearchContent.Empty(SearchTerm("x"), SearchPaging.Complete), query = "x")
        compose.onAllNodesWithText("Load more").assertCountEquals(0)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()
        assertEquals(listOf("next"), log)
    }

    @Test
    fun aRecentSearchFailureOffersRetryAndABlockedOpenExplainsItself() {
        var notice by mutableStateOf<FilesFailure?>(PutioFailure.Misconfigured(PutioConfigurationException("boom")))
        show(state = { searchState(SearchContent.Idle) }, notice = { notice })
        compose.onNodeWithText("Couldn’t update recent searches.").assertIsDisplayed()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf("recent-retry"), log)

        notice = FilesFailure.NavigationBlocked
        compose.onNodeWithText("This item can’t be opened right now. Check Files, then try again.").assertIsDisplayed()
    }

    @Test
    fun loadMoreKeepsFocusThroughLoadingAndHandsOffToTheLastRowWhenComplete() {
        var state by mutableStateOf(oneRowSearch(SearchPaging.Available(FilesCursor("c1"))))
        show(state = { state })
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            repeat(3) { pressKey(Key.DirectionDown) }
        }
        compose.onNode(hasText("Load more") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        state = oneRowSearch(SearchPaging.Loading(FilesCursor("c1"), SearchRequestId(2)))
        compose.onNode(hasText("Loading more results") and hasClickAction()).assertIsFocused()

        state = oneRowSearch(
            SearchPaging.Failed(FilesCursor("c1"), PutioFailure.Misconfigured(PutioConfigurationException("boom"))),
        )
        compose.onNodeWithText("Couldn’t load more results.").assertIsDisplayed()
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }

        state = oneRowSearch(SearchPaging.Complete, item(2, "two.mkv", PutioFileType.VIDEO))
        compose.onNodeWithContentDescription("Play two.mkv").assertIsFocused()
        assertEquals(listOf("next", "retry"), log)
    }

    @Test
    fun aLastPageLandingAfterLeavingLoadMoreDoesNotStealFocus() {
        var state by mutableStateOf(oneRowSearch(SearchPaging.Available(FilesCursor("c1"))))
        show(state = { state })
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            repeat(3) { pressKey(Key.DirectionDown) }
        }
        compose.onNode(hasText("Load more") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        state = oneRowSearch(SearchPaging.Loading(FilesCursor("c1"), SearchRequestId(2)))
        compose.onNode(hasText("Loading more results") and hasClickAction()).assertIsFocused().performKeyInput {
            repeat(3) { pressKey(Key.DirectionUp) }
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()

        state = oneRowSearch(SearchPaging.Complete, item(2, "two.mkv", PutioFileType.VIDEO))
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()
        assertEquals(listOf("next"), log)
    }

    @Test
    fun longPressOnAChipRemovesItAndFocusStaysOnTheChips() {
        var recent by mutableStateOf(listOf(SearchTerm("tears"), SearchTerm("sintel")))
        val removing = TvSearchActions(
            onQueryChanged = {}, onSubmit = {}, onResult = {}, onNextPage = {}, onRetry = {},
            recent = TvRecentSearchActions(
                onSearch = { log += "recent:${it.value}" },
                onEdit = { edit ->
                    log += "edit:$edit"
                    if (edit is RecentSearchEdit.Remove) recent = recent.filterNot { it == edit.term }
                },
                onRetry = {},
            ),
        )
        show(state = { searchState(SearchContent.Idle, recent = recent) }, actions = removing)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
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
        show(state = { searchState(SearchContent.Idle, recent = recent) })
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Search again for term-1").assertIsFocused().performKeyInput {
            repeat(4) { pressKey(Key.DirectionRight) }
        }
        compose.onNodeWithContentDescription("Search again for term-5").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
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
        var state by mutableStateOf(
            searchState(SearchContent.Idle, recent = listOf(SearchTerm("sintel"), SearchTerm("Tears Of Steel"))),
        )
        show(state = { state })
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Search again for sintel").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription("Search again for Tears Of Steel").assertIsFocused().performKeyInput {
            pressCenter()
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assert(hasText("Tears Of Steel"))
        assertEquals(listOf("recent:Tears Of Steel"), log)
        state = searchState(SearchContent.Idle, recent = state.recentTerms, query = "Tears Of Steel")

        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performTextInput("!")
        // The controller reporting the previous text back, while the user is on the chips,
        // must not undo the edit.
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Search again for sintel").assertIsFocused()
        state = searchState(SearchContent.Idle, recent = state.recentTerms, query = "Tears Of Steel")
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assert(hasText("Tears Of Steel!"))
        assertEquals(listOf("recent:Tears Of Steel", "query:Tears Of Steel!"), log)

        // The controller catching up leaves the field as typed.
        state = searchState(SearchContent.Idle, recent = state.recentTerms, query = "Tears Of Steel!")
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assert(hasText("Tears Of Steel!"))
    }

    @Test
    fun aSuccessfulRecentRetryHandsFocusToTheField() {
        var notice by mutableStateOf<PutioFailure?>(PutioFailure.Misconfigured(PutioConfigurationException("boom")))
        val retrying = TvSearchActions(
            onQueryChanged = {}, onSubmit = {}, onResult = {}, onNextPage = {}, onRetry = {},
            recent = TvRecentSearchActions(onSearch = {}, onEdit = {}, onRetry = { notice = null }),
        )
        show(state = { searchState(SearchContent.Idle) }, actions = retrying, notice = { notice })
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused()
    }

    @Test
    fun aNewSessionStartsWithAnEmptyField() {
        var session by mutableStateOf(1)
        show(state = { searchState(SearchContent.Idle) }, sessionKey = { session })
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performTextInput("private")
        session = 2
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assert(hasText("Tap to start typing"))
        assertEquals(listOf("query:private"), log)
    }

    @Test
    fun theLastPageLandingOffScreenStillHandsFocusToTheLastRow() {
        val page = (1..12).map { item(it.toLong(), "row-$it.mkv", PutioFileType.VIDEO) }
        var state by mutableStateOf(
            searchState(
                SearchContent.Ready(SearchTerm("r"), page, SearchPaging.Available(FilesCursor("c1"))),
                query = "r",
            ),
        )
        show(state = { state })
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            repeat(page.size + 2) { pressKey(Key.DirectionDown) }
        }
        compose.onNode(hasText("Load more") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        val more = (13..30).map { item(it.toLong(), "row-$it.mkv", PutioFileType.VIDEO) }
        state = searchState(SearchContent.Ready(SearchTerm("r"), page + more, SearchPaging.Complete), query = "r")
        compose.onNodeWithContentDescription("Play row-30.mkv").assertIsFocused()
        assertEquals(listOf("next"), log)
    }

    @Test
    fun replayingTheTermAlreadyInTheFieldDoesNotSwallowTheNextEdit() {
        var state by mutableStateOf(searchState(SearchContent.Idle, recent = listOf(SearchTerm("ab")), query = "ab"))
        show(state = { state })
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Search again for ab").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performTextInput("c")
        state = searchState(SearchContent.Idle, recent = state.recentTerms, query = "abc")
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.Backspace) }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assert(hasText("ab"))
        assertEquals(listOf("recent:ab", "query:abc", "query:ab"), log)
    }

    @Test
    fun menuOnTheFieldAndTheSettingsPillOpenTheSearchSettingsSheet() {
        var state by mutableStateOf(searchState(SearchContent.Idle, recent = listOf(SearchTerm("tears"))))
        show(state = { state })
        compose.onAllNodesWithText("Settings").assertCountEquals(0)

        state = searchState(SearchContent.Idle, recent = listOf(SearchTerm("tears")), historyEnabled = true)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Search settings").assertIsDisplayed()
        compose.onNode(hasText("Show search history") and hasClickAction()).assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Clear search history") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
        }
        compose.onAllNodesWithText("Search settings").assertCountEquals(0)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            repeat(SearchFilter.entries.size) { pressKey(Key.DirectionRight) }
        }
        compose.onNode(hasText("Settings") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        compose.onNode(hasText("Show search history") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
        }

        state = searchState(SearchContent.Idle, historyEnabled = false)
        compose.onAllNodesWithText("Clear search history").assertCountEquals(0)
        compose.onNode(hasText("Show search history") and hasClickAction()).assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performKeyInput { pressCenter() }
        assertEquals(
            listOf("edit:Clear", "edit:SetEnabled(enabled=false)", "edit:SetEnabled(enabled=true)"),
            log,
        )
    }

    @Test
    fun typeChipsFilterTheSearchAndResultsNameTheirFolder() {
        val folder = FilesItemId(40L)
        show(
            state = {
                searchState(
                    SearchContent.Ready(
                        SearchTerm("bunny"),
                        listOf(
                            item(1, "Big Buck Bunny.mp4", PutioFileType.VIDEO).copy(parentId = folder),
                            item(2, "Big Buck Bunny", PutioFileType.FOLDER),
                            item(3, "Big Buck Bunny.en.srt", PutioFileType.TEXT).copy(parentId = folder),
                        ),
                        SearchPaging.Complete,
                    ),
                    query = "bunny",
                )
            },
            folderNames = mapOf(folder to "Big Buck Bunny"),
        )
        compose.onNode(hasText("All") and isSelected()).assertIsDisplayed()
        compose.onNodeWithText("Big Buck Bunny.en.srt").assertIsDisplayed()
        compose.onAllNodesWithText("Big Buck Bunny", useUnmergedTree = true).assertCountEquals(4)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("All") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            pressCenter()
        }
        assertEquals(listOf("filter:Videos"), log)
    }

    @Test
    fun theDetailResumesTheFocusedVideoFromItsPosition() {
        show(
            state = {
                searchState(
                    SearchContent.Ready(
                        SearchTerm("bunny"),
                        listOf(
                            item(1, "Big Buck Bunny.mp4", PutioFileType.VIDEO)
                                .copy(playback = FilesPlaybackProgress(190.0, 634.0)),
                        ),
                        SearchPaging.Complete,
                    ),
                    query = "bunny",
                )
            },
        )
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).requestFocus()
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Play Big Buck Bunny.mp4").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithTag(TV_FILES_DETAIL_PRIMARY_TAG).assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("10:34").assertIsDisplayed()
        assertEquals(listOf("play:Big Buck Bunny.mp4:Resume"), log)
    }

    private fun show(
        state: () -> SearchState,
        actions: TvSearchActions = this.actions,
        notice: () -> FilesFailure? = { null },
        sessionKey: () -> Any? = { null },
        folderNames: Map<FilesItemId, String> = emptyMap(),
    ) {
        compose.setTvContent {
            TvSearchScreen(
                state = state(),
                actions = actions,
                notice = notice(),
                sessionKey = sessionKey(),
                detail = tvSearchDetail(folderNames),
                results = results,
            )
        }
    }

    private fun searchState(
        content: SearchContent,
        recent: List<SearchTerm> = emptyList(),
        query: String = "",
        historyEnabled: Boolean? = null,
    ) = searchState(
        query = query,
        content = content,
        recentTerms = recent,
        nextRequestValue = 5L,
        recentSearchesEnabled = historyEnabled,
    )

    private fun oneRowSearch(paging: SearchPaging, vararg more: FilesItem) = searchState(
        SearchContent.Ready(SearchTerm("t"), listOf(item(1, "one.mkv", PutioFileType.VIDEO)) + more, paging),
        query = "t",
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
