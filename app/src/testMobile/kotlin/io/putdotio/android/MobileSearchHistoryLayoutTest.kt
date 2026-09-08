package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-port")
class MobileSearchHistoryLayoutTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() { RuntimeEnvironment.setFontScale(2f) }
    }).around(compose)

    @Test
    fun recentTermUsesTwoReadableLinesAndCompactNamedRemoval() {
        val searches = mutableListOf<SearchTerm>()
        val edits = mutableListOf<RecentSearchEdit>()
        setScreen(actions = MobileSearchHistoryActions(onRecentSearch = searches::add, onRecentEdit = edits::add))

        val termLayout = textLayout(TERM)
        assertEquals(2, termLayout.lineCount)
        assertFalse(termLayout.isLineEllipsized(1))
        val term = compose.onNodeWithText(TERM, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val remove = compose.onNodeWithContentDescription("Remove $TERM from recent searches")
            .fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        assertTrue("Term keeps most of the row width", term.width >= viewport.width * 0.7f)
        assertTrue("Removal does not crowd the term", remove.width <= viewport.width * 0.18f)
        val minimumTarget = 48f * termLayout.layoutInput.density.density
        val touch = compose.onNodeWithContentDescription("Remove $TERM from recent searches")
            .fetchSemanticsNode().touchBoundsInRoot
        assertTrue("Removal keeps a full touch target", touch.width >= minimumTarget && touch.height >= minimumTarget)
        compose.onNodeWithContentDescription("Remove $TERM from recent searches").performClick()
        assertEquals(listOf(RecentSearchEdit.Remove(SearchTerm(TERM))), edits)
        assertTrue("Removing a term must not search for it", searches.isEmpty())
        compose.onNodeWithText(TERM).performClick()
        assertEquals(listOf(SearchTerm(TERM)), searches)
    }

    @Test
    fun historyMetadataUsesFullWidthAndTheRowHasOneNamedOpenAction() {
        val events = mutableListOf<HistoryEvent>()
        setScreen(
            history = HistoryEventKind.Transfer(null, HistoryFileId(7), HISTORY_NAME),
            actions = MobileSearchHistoryActions(onHistoryEvent = events::add),
        )
        compose.onNodeWithText("History").performClick()
        val name = compose.onNodeWithText(HISTORY_NAME, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val metadata = compose.onNodeWithText("Completed transfer", substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        assertTrue("Title keeps the row width", name.width >= viewport.width * 0.85f)
        assertTrue("Metadata keeps the row width", metadata.width >= viewport.width * 0.85f)
        assertTrue("Metadata follows the complete title", metadata.top >= name.bottom)
        assertFalse(textLayout(HISTORY_NAME).hasVisualOverflow)
        compose.onAllNodes(hasText(HISTORY_NAME) and hasClickAction()).assertCountEquals(1)
        val open = compose.onNodeWithText(HISTORY_NAME).fetchSemanticsNode().config[SemanticsActions.OnClick]
        assertEquals("Open file", open.label)
        compose.onAllNodesWithText("Open file").assertCountEquals(0)
        compose.onNodeWithText(HISTORY_NAME).assertIsDisplayed().performClick()
        assertEquals(listOf(HistoryEvent.OpenFile(HistoryFileId(7))), events)
    }

    @Test
    fun nonNavigableHistoryRetainsMetadataWithoutInventingAnOpenAction() {
        setScreen(history = HistoryEventKind.Other("activity", HISTORY_NAME))
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText(HISTORY_NAME).assertIsDisplayed().assertHasNoClickAction()
        compose.onAllNodesWithText("Open file").assertCountEquals(0)
    }

    @Test
    fun recentSearchErrorUsesFullWidthAndPlacesRetryAfterTheMessage() {
        var retries = 0
        setScreen(
            recentFailure = FilesFailure.Unexpected(IllegalStateException("offline")),
            actions = MobileSearchHistoryActions(onRecentRetry = { retries += 1 }),
        )
        val message = "put.io is temporarily unavailable. Try again."
        val copy = compose.onNodeWithText(message, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val retry = compose.onNodeWithText("Try again").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        assertTrue("Error copy keeps the card width", copy.width >= viewport.width * 0.8f)
        assertTrue("Retry follows the message", retry.top >= copy.bottom)
        assertFalse(textLayout(message).hasVisualOverflow)
        compose.onNodeWithText("Try again").assertIsDisplayed().performClick()
        assertEquals(1, retries)
        compose.onNodeWithText(TERM).assertIsDisplayed()
    }

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().also { assertEquals(2f, it.layoutInput.density.fontScale) }
    }

    private fun setScreen(
        history: HistoryEventKind? = null,
        recentFailure: FilesFailure? = null,
        actions: MobileSearchHistoryActions = MobileSearchHistoryActions(),
    ) {
        val historyState = if (history == null) HistoryState(HistoryContent.Disabled) else HistoryState(
            HistoryContent.Ready(
                listOf(HistoryItem(HistoryEventId(1), "2026-09-08T10:00:00Z", history)),
                HistoryPaging.Complete,
            ),
        )
        compose.setContent {
            PutioTheme {
                Box(Modifier.fillMaxSize().testTag(VIEWPORT)) {
                    MobileSearchHistoryScreen(
                        searchState = SearchState("", SearchContent.Idle, listOf(SearchTerm(TERM)), emptySet(), 1),
                        historyState = historyState,
                        recentSearchFailure = recentFailure,
                        onSearchQueryChanged = {}, onSearchSubmit = {}, onSearchResult = actions.onResult,
                        onSearchNextPage = {}, onSearchRetry = {}, onRecentSearch = actions.onRecentSearch,
                        onRecentEdit = actions.onRecentEdit, onRecentRetry = actions.onRecentRetry,
                        onHistoryEvent = actions.onHistoryEvent,
                    )
                }
            }
        }
    }

    private companion object {
        const val TERM = "Bodrum film collection"
        const val HISTORY_NAME = "Bodrum documentary collection.mp4"
        const val VIEWPORT = "search-history-viewport"
    }
}
