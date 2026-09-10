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
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
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
        compose.setContent { PutioTheme { MobileTrashScreen(state, { events += it; true }) } }
        compose.onNodeWithContentDescription("Actions for ${item.name}").performClick()
        compose.onNodeWithTag(MOBILE_TRASH_ITEM_DELETE_TAG).performClick()
        compose.runOnIdle { assertEquals(listOf<TrashEvent>(TrashEvent.SelectDelete(item.id)), events) }
        compose.runOnIdle {
            state = state.copy(actionConfirmation = TrashAction.DeleteItem(item), actionConfirmationId = 3)
        }
        compose.onNodeWithText("Delete permanently?").assertIsDisplayed()
        compose.onNodeWithText("“${item.name}” will be removed from Trash right away. This cannot be undone.")
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
    fun bulkButtonsConfirmSeparatelyAndStayDisabledOnKnownEmptyTrash() {
        val events = mutableListOf<TrashEvent>()
        var state by mutableStateOf(TrashState(content = loaded))
        compose.setContent { PutioTheme { MobileTrashScreen(state, { events += it; true }) } }
        compose.onNodeWithTag(MOBILE_TRASH_RESTORE_ALL_TAG).performClick()
        compose.onNodeWithTag(MOBILE_TRASH_EMPTY_TAG).performClick()
        compose.runOnIdle { assertEquals(listOf(TrashEvent.SelectRestoreAll, TrashEvent.SelectEmpty), events) }
        compose.runOnIdle { state = state.copy(actionConfirmation = TrashAction.Empty, actionConfirmationId = 5) }
        compose.onNodeWithText("Empty Trash?").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_ACTION_CONFIRM_TAG).performClick()
        compose.runOnIdle {
            assertEquals(TrashEvent.ConfirmAction(5), events.last())
            state = TrashState(content = TrashContent.Loaded(emptyList(), null, 0, 0))
        }
        compose.onNodeWithTag(MOBILE_TRASH_RESTORE_ALL_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_TRASH_EMPTY_TAG).assertIsNotEnabled()
    }

    @Test
    fun aDeleteStillListedAfterACompleteReadIsSettledAndLeavesEveryActionEnabled() {
        val events = mutableListOf<TrashEvent>()
        val state = TrashState(content = loaded, actionOutcome = TrashActionOutcome(
            TrashAction.DeleteItem(item), TrashActionSubmission.UNCERTAIN, TrashActionCheck.FAILED))
        compose.setContent { PutioTheme { MobileTrashScreen(state, { events += it; true }) } }
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TRASH_ACTION_OUTCOME_TAG))
        compose.onNodeWithText("“${item.name}” is still in Trash.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_ACTION_CHECK_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_TRASH_RESTORE_ALL_TAG).assertIsEnabled()
        compose.onNodeWithTag(MOBILE_TRASH_EMPTY_TAG).assertIsEnabled()
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
        compose.setContent { PutioTheme { MobileTrashScreen(state, { events += it; true }) } }
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TRASH_ACTION_OUTCOME_TAG))
        compose.onNodeWithText("“${item.name}” was not in the first page. Check Trash again in a moment.")
            .assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_ACTION_CHECK_TAG).performClick()
        compose.runOnIdle {
            state = state.copy(actionOutcome = state.actionOutcome?.copy(check = TrashActionCheck.NOT_CHECKED))
        }
        compose.onNodeWithText("We could not confirm the request. Check Trash before taking another action.")
            .assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_RESTORE_ALL_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_TRASH_EMPTY_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText(item.name))
        compose.onNodeWithContentDescription("Actions for ${item.name}").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf<TrashEvent>(TrashEvent.CheckAction), events)
            state = state.copy(actionOutcome = state.actionOutcome?.copy(check = TrashActionCheck.VERIFIED),
                content = TrashContent.Loaded(emptyList(), null, 0, 0))
        }
        compose.onNodeWithText("“${item.name}” is no longer in Trash.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRASH_ACTION_CHECK_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TRASH_ACTION_OUTCOME_TAG))
        compose.onNodeWithText("OK").performClick()
        compose.runOnIdle { assertEquals(TrashEvent.DismissActionOutcome, events.last()) }
    }
}
