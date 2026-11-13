package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.trash.MOBILE_TRASH_ACTION_CHECK_TAG
import io.putdotio.android.trash.MOBILE_TRASH_ACTION_CONFIRM_TAG
import io.putdotio.android.trash.MOBILE_TRASH_ACTION_OUTCOME_TAG
import io.putdotio.android.trash.MOBILE_TRASH_EMPTY_TAG
import io.putdotio.android.trash.MOBILE_TRASH_ITEM_DELETE_TAG
import io.putdotio.android.trash.MOBILE_TRASH_LIST_TAG
import io.putdotio.android.trash.MOBILE_TRASH_RESTORE_ALL_TAG
import io.putdotio.android.trash.MobileTrashScreen
import io.putdotio.android.trash.TrashAction
import io.putdotio.android.trash.TrashActionCheck
import io.putdotio.android.trash.TrashActionOutcome
import io.putdotio.android.trash.TrashActionSubmission
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashState
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import io.putdotio.android.trash.MOBILE_TRASH_MORE_TAG
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import io.putdotio.android.trash.MobileTrashOverflow
import io.putdotio.android.trash.MOBILE_TRASH_ROUTE
import io.putdotio.android.trash.MOBILE_TRASH_SELECT_TAG
import io.putdotio.android.trash.MOBILE_TRASH_SELECTION_BAR_TAG
import io.putdotio.android.trash.MOBILE_TRASH_SELECTION_DELETE_TAG
import io.putdotio.android.trash.MOBILE_TRASH_SELECTION_RESTORE_TAG
import io.putdotio.android.trash.rememberTrashSelection
import io.putdotio.android.trash.trashSelectionBar

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileTrashActionsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val item = TrashItem(FilesItemId(7), FilesItemId(8), "été 東京.txt", PutioFileType.TEXT, 12,
        "2026-09-06T00:00:00Z", "2026-09-20")
    private val loaded = TrashContent.Loaded(listOf(item), null, 1, 12)

    @Test
    fun rowSheetOffersRestoreAndPermanentDeleteAndDeleteNeedsItsOwnConfirmation() {
        val events = mutableListOf<TrashEvent>()
        var state by mutableStateOf(TrashState(content = loaded))
        compose.setContent { PutioTheme { TrashWithOverflow(state) { events += it; true } } }
        compose.onNodeWithContentDescription("Actions for ${item.name}").performClick()
        compose.onNodeWithTag(MOBILE_TRASH_ITEM_DELETE_TAG).performClick()
        compose.runOnIdle { assertEquals(listOf<TrashEvent>(TrashEvent.SelectDelete(item.id)), events) }
        compose.runOnIdle {
            state = state.copy(actionConfirmation = TrashAction.DeleteItem(item), actionConfirmationId = 3)
        }
        compose.onNodeWithText("Delete permanently?").assertIsDisplayed()
        compose.onNodeWithText("“${item.name}” will be removed from trash right away. This cannot be undone.")
            .assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag(MOBILE_TRASH_ACTION_CONFIRM_TAG).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(TrashEvent.SelectDelete(item.id), TrashEvent.CancelAction, TrashEvent.ConfirmAction(3)),
                events,
            )
        }
    }

    @Test
    fun restoreAllStartsAtOnceEmptyConfirmsAndBothStayDisabledOnKnownEmptyTrash() {
        val events = mutableListOf<TrashEvent>()
        var state by mutableStateOf(TrashState(content = loaded))
        compose.setContent { PutioTheme { TrashWithOverflow(state) { events += it; true } } }
        compose.trashMenuItem(MOBILE_TRASH_RESTORE_ALL_TAG).performClick()
        compose.trashMenuItem(MOBILE_TRASH_EMPTY_TAG).performClick()
        compose.runOnIdle { assertEquals(listOf(TrashEvent.RestoreAll, TrashEvent.SelectEmpty), events) }
        compose.runOnIdle { state = state.copy(actionConfirmation = TrashAction.Empty, actionConfirmationId = 5) }
        compose.onNodeWithText("Empty trash?").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_ACTION_CONFIRM_TAG).performClick()
        compose.runOnIdle {
            assertEquals(TrashEvent.ConfirmAction(5), events.last())
            state = TrashState(content = TrashContent.Loaded(emptyList(), null, 0, 0))
        }
        compose.trashMenuItem(MOBILE_TRASH_RESTORE_ALL_TAG).assertIsNotEnabled()
        compose.trashMenuItem(MOBILE_TRASH_EMPTY_TAG).assertIsNotEnabled()
    }

    @Test
    fun aDeleteStillListedAfterACompleteReadIsSettledAndLeavesEveryActionEnabled() {
        val events = mutableListOf<TrashEvent>()
        val state = TrashState(content = loaded, actionOutcome = TrashActionOutcome(
            TrashAction.DeleteItem(item), TrashActionSubmission.UNCERTAIN, TrashActionCheck.FAILED))
        compose.setContent { PutioTheme { TrashWithOverflow(state) { events += it; true } } }
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TRASH_ACTION_OUTCOME_TAG))
        compose.onNodeWithText("“${item.name}” is still in trash.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_ACTION_CHECK_TAG).assertDoesNotExist()
        compose.trashMenuItem(MOBILE_TRASH_RESTORE_ALL_TAG).assertIsEnabled()
        compose.trashMenuItem(MOBILE_TRASH_EMPTY_TAG).assertIsEnabled()
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText(item.name))
        compose.onNodeWithContentDescription("Actions for ${item.name}").assertIsEnabled()
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TRASH_ACTION_OUTCOME_TAG))
        compose.onNodeWithText("OK").performClick()
        compose.runOnIdle { assertEquals(listOf<TrashEvent>(TrashEvent.DismissActionOutcome), events) }
    }

    @Test
    fun pendingActionOffersOnlyCheckTrashAndDisablesEveryOtherMutation() {
        val events = mutableListOf<TrashEvent>()
        var state by mutableStateOf(TrashState(content = loaded, actionOutcome = TrashActionOutcome(
            TrashAction.DeleteItem(item), TrashActionSubmission.UNCERTAIN, TrashActionCheck.INCONCLUSIVE)))
        compose.setContent { PutioTheme { TrashWithOverflow(state) { events += it; true } } }
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TRASH_ACTION_OUTCOME_TAG))
        compose.onNodeWithText("“${item.name}” was not in the first page. Check trash again in a moment.")
            .assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_ACTION_CHECK_TAG).performClick()
        compose.runOnIdle {
            state = state.copy(actionOutcome = state.actionOutcome?.copy(check = TrashActionCheck.NOT_CHECKED))
        }
        compose.onNodeWithText("We could not confirm the request. Check trash before taking another action.")
            .assertIsDisplayed()
        compose.trashMenuItem(MOBILE_TRASH_RESTORE_ALL_TAG).assertIsNotEnabled()
        compose.trashMenuItem(MOBILE_TRASH_EMPTY_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText(item.name))
        compose.onNodeWithContentDescription("Actions for ${item.name}").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf<TrashEvent>(TrashEvent.CheckAction), events)
            state = state.copy(actionOutcome = state.actionOutcome?.copy(check = TrashActionCheck.VERIFIED),
                content = TrashContent.Loaded(emptyList(), null, 0, 0))
        }
        compose.onNodeWithText("“${item.name}” is no longer in trash.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_ACTION_CHECK_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TRASH_ACTION_OUTCOME_TAG))
        compose.onNodeWithText("OK").performClick()
        compose.runOnIdle { assertEquals(TrashEvent.DismissActionOutcome, events.last()) }
    }

    @Test
    fun selectTicksRowsRestoresThemAtOnceAndDeleteAsksNamingTheCount() {
        val events = mutableListOf<TrashEvent>()
        val second = item.copy(id = FilesItemId(9), name = "second.txt")
        val third = item.copy(id = FilesItemId(10), name = "third.txt")
        val rows = TrashContent.Loaded(listOf(item, second, third), null, 3, 36)
        var state by mutableStateOf(TrashState(content = rows))
        compose.setContent { PutioTheme { TrashWithOverflow(state) { events += it; true } } }
        compose.onNodeWithContentDescription("Restore ${second.name}").performTouchInput { longClick() }
        compose.onNodeWithTag(MOBILE_TRASH_SELECTION_BAR_TAG).assertDoesNotExist()
        compose.runOnIdle { events.clear() }
        compose.trashMenuItem(MOBILE_TRASH_SELECT_TAG).performClick()
        compose.onNodeWithTag(MOBILE_TRASH_SELECTION_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithText("0 selected").assertIsDisplayed()
        compose.onNodeWithText(item.name).performClick()
        compose.onNodeWithText(third.name).performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_SELECTION_RESTORE_TAG).performClick()
        compose.onNodeWithTag(MOBILE_TRASH_SELECTION_BAR_TAG).assertDoesNotExist()
        compose.onNodeWithText(item.name).performTouchInput { longClick() }
        compose.onNodeWithText(third.name).performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_SELECTION_DELETE_TAG).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    TrashEvent.RestoreSelected(setOf(item.id, third.id)),
                    TrashEvent.SelectDeleteSelected(setOf(item.id, third.id)),
                ),
                events,
            )
            state = state.copy(
                actionConfirmation = TrashAction.DeleteSelected(listOf(item, third)),
                actionConfirmationId = 4,
            )
        }
        compose.onNodeWithText("Delete 2 items permanently?").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_ACTION_CONFIRM_TAG).performClick()
        compose.runOnIdle {
            assertEquals(TrashEvent.ConfirmAction(4), events.last())
            state = state.copy(
                actionConfirmation = null,
                actionConfirmationId = null,
                actionOutcome = TrashActionOutcome(
                    TrashAction.DeleteSelected(listOf(item, third)),
                    TrashActionSubmission.UNCERTAIN,
                    check = TrashActionCheck.FAILED,
                    failedCount = 2,
                ),
            )
        }
        compose.onNodeWithTag(MOBILE_TRASH_SELECTION_BAR_TAG).assertDoesNotExist()
        compose.onNodeWithText("Couldn’t delete 2 items.").assertIsDisplayed()
    }

    /** A Trash overflow item, opening the overflow first when it is closed. */
    private fun SemanticsNodeInteractionsProvider.trashMenuItem(tag: String): SemanticsNodeInteraction {
        if (onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) {
            onNode(hasTestTag(MOBILE_TRASH_MORE_TAG)).performClick()
        }
        return onNode(hasTestTag(tag))
    }

    /** The screen under the overflow its top bar carries in the shell. */
    @Composable
    private fun TrashWithOverflow(state: TrashState, onEvent: (TrashEvent) -> Boolean) {
        val selection = rememberTrashSelection(Unit)
        Column {
            trashSelectionBar(MOBILE_TRASH_ROUTE, state, selection)?.invoke()
                ?: MobileTrashOverflow(state, onEvent, onSelect = selection::start)
            MobileTrashScreen(state, onEvent, selection = selection)
        }
    }
}
