package io.putdotio.android.tv.trash

import io.putdotio.android.tv.setTvContent
import io.putdotio.android.tv.pressCenter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.trash.TrashAction
import io.putdotio.android.trash.TrashActionCheck
import io.putdotio.android.trash.TrashActionOutcome
import io.putdotio.android.trash.TrashActionSubmission
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashRestoreCheck
import io.putdotio.android.trash.TrashRestoreOutcome
import io.putdotio.android.trash.TrashRestoreSubmission
import io.putdotio.android.trash.TrashState
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import java.time.LocalDate
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
class TvTrashScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val events = mutableListOf<TrashEvent>()
    private val item = TrashItem(
        FilesItemId(7), FilesItemId(8), "smoke-two.txt", PutioFileType.TEXT, 1_400_000_000,
        "2026-09-06T10:00:00", LocalDate.now().plusDays(12).toString(),
    )
    private val folder = TrashItem(
        FilesItemId(9), FilesItemId(8), "Old downloads", PutioFileType.FOLDER, 1_200_000_000,
        "2026-09-06T10:00:00", LocalDate.now().plusDays(1).toString(),
    )
    private val loaded = TrashContent.Loaded(listOf(item, folder), nextCursor = null, total = 2, trashSizeBytes = 10)
    private val row = hasText("smoke-two.txt") and hasClickAction()

    @Test
    fun rowsShowSizeAndDaysLeftAndCenterRestoresThroughItsConfirmation() {
        var state by mutableStateOf(TrashState(content = loaded))
        show { state }

        compose.onNodeWithText("Items stay in Trash for 14 days.").assertIsDisplayed()
        compose.onNodeWithText("12 days left").assertIsDisplayed()
        compose.onNodeWithText("1 day left").assertIsDisplayed()
        compose.onNodeWithText("1.2 GB").assertIsDisplayed()
        compose.onNodeWithText("1.4 GB · Deleted Sep 6", substring = true).assertIsDisplayed()
        compose.onNodeWithText("in 12 days").assertIsDisplayed()
        compose.onNode(row).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.SelectRestore(FilesItemId(7))), events)

        compose.runOnIdle { state = state.copy(confirmation = item, confirmationId = 3L) }
        compose.onNodeWithText("Restore item?").assertIsDisplayed()
        compose.onNode(hasText("Restore") and isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressCenter() }
        assertEquals(TrashEvent.ConfirmRestore(3L), events.last())
    }

    @Test
    fun theDetailFollowsTheFocusedRowAndDeleteConfirmsInItsOwnDialog() {
        var state by mutableStateOf(TrashState(content = loaded))
        show { state }

        compose.onNode(row).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("in 1 day").assertIsDisplayed()
        compose.onNode(hasText("Old downloads") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Restore") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Delete permanently") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
        }
        assertEquals(listOf<TrashEvent>(TrashEvent.SelectDelete(FilesItemId(9))), events)

        compose.runOnIdle {
            state = state.copy(actionConfirmation = TrashAction.DeleteItem(folder), actionConfirmationId = 4L)
        }
        compose.onNodeWithText("Delete permanently?").assertIsDisplayed()
        compose.onNode(hasText("Delete permanently") and isFocused() and hasAnyAncestor(isDialog())).performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(TrashEvent.CancelAction, events.last())
        compose.runOnIdle { state = state.copy(actionConfirmation = null, actionConfirmationId = null) }
        compose.onNode(hasText("Delete permanently") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun headerActionsAskForBulkConfirmationsAndEmptyTrashCountsTheItems() {
        var state by mutableStateOf(TrashState(content = loaded))
        show { state }

        compose.onNode(row).performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Restore all") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Empty trash") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.SelectRestoreAll, TrashEvent.SelectEmpty), events)

        compose.runOnIdle { state = state.copy(actionConfirmation = TrashAction.Empty, actionConfirmationId = 5L) }
        compose.onNodeWithText("Empty trash?").assertIsDisplayed()
        compose.onNodeWithText("2 items are deleted permanently. This can't be undone.").assertIsDisplayed()
        compose.onNode(hasText("Empty trash") and isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(TrashEvent.CancelAction, events.last())
        compose.runOnIdle { state = state.copy(actionConfirmation = null, actionConfirmationId = null) }
        // The button that opened the confirmation takes focus back, not the list.
        compose.onNode(hasText("Empty trash") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun aRestoredRowOffersOnlyDeleteAndAPendingConfirmationCannotConfirm() {
        var state by mutableStateOf(TrashState(content = loaded, restoredItemIds = setOf(FilesItemId(7))))
        show { state }

        compose.onNode(row).assertIsFocused().performKeyInput { pressCenter() }
        compose.onNode(hasText("Restore") and hasClickAction()).assertIsNotEnabled()
        assertEquals(emptyList<TrashEvent>(), events)

        compose.runOnIdle {
            state = TrashState(
                content = loaded,
                restoreOutcome = TrashRestoreOutcome(item, TrashRestoreSubmission.ACKNOWLEDGED),
                actionConfirmation = TrashAction.Empty,
                actionConfirmationId = 6L,
            )
        }
        compose.onNode(hasText("Empty trash") and isNotEnabled()).assertIsDisplayed()
    }

    @Test
    fun dateOnlyStampsFormatLikeTimestamps() {
        show {
            TrashState(content = loaded.copy(items = listOf(item.copy(deletedAt = "2026-09-06"))))
        }

        compose.onNodeWithText("1.4 GB · Deleted Sep 6", substring = true).assertIsDisplayed()
    }

    @Test
    fun anUnconfirmedRestoreOffersCheckStatusAndASettledOneOffersOk() {
        var state by mutableStateOf(
            TrashState(
                content = loaded,
                restoreOutcome = TrashRestoreOutcome(
                    item,
                    TrashRestoreSubmission.ACKNOWLEDGED,
                    TrashRestoreCheck.UNAVAILABLE,
                ),
            ),
        )
        show { state }

        compose.onNodeWithText("smoke-two.txt is not in Files yet. Check again in a moment.").assertIsDisplayed()
        compose.onNode(row).assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Check status").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.CheckRestore), events)

        compose.runOnIdle {
            state = state.copy(
                restoreOutcome = null,
                actionOutcome = TrashActionOutcome(
                    TrashAction.Empty,
                    TrashActionSubmission.ACKNOWLEDGED,
                    TrashActionCheck.VERIFIED,
                ),
                content = loaded.copy(items = emptyList()),
            )
        }
        compose.onNodeWithText("Trash is empty.").assertIsDisplayed()
        compose.onNodeWithText("Your trash is empty").assertIsDisplayed()
        compose.onNodeWithText("When you send files to trash, we keep them here for 14 days.").assertIsDisplayed()
        compose.onNode(hasText("Restore all") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithText("OK").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(TrashEvent.DismissActionOutcome, events.last())
    }

    @Test
    fun aFailedReadFocusesTryNowAndPagingLoadsMore() {
        var state by mutableStateOf(
            TrashState(content = TrashContent.Error(PutioFailure.NetworkUnavailable(PutioConfigurationException("x")))),
        )
        show { state }

        compose.onNodeWithText("Can’t reach put.io.").assertIsDisplayed()
        compose.onNodeWithText("Try now").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.Retry), events)

        compose.runOnIdle { state = TrashState(content = loaded.copy(nextCursor = FilesCursor("c"))) }
        compose.onNode(row).assertIsFocused().performKeyInput { repeat(2) { pressKey(Key.DirectionDown) } }
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(TrashEvent.LoadNextPage, events.last())

        compose.runOnIdle { state = TrashState(content = loaded) }
        compose.onNode(hasText("Old downloads") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun anEmptyPageWithACursorKeepsLoadMoreReachable() {
        show { TrashState(content = TrashContent.Loaded(emptyList(), FilesCursor("c"), total = 5, trashSizeBytes = 1)) }

        compose.onAllNodesWithText("Your trash is empty").assertCountEquals(0)
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionUp)
        }
        assertEquals(listOf<TrashEvent>(TrashEvent.LoadNextPage), events)
        compose.onNode(hasText("Restore all") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun aConfirmationThatGreetsTheRowsKeepsItsFocus() {
        show { TrashState(content = loaded, actionConfirmation = TrashAction.Empty, actionConfirmationId = 9L) }

        compose.onNodeWithText("Empty trash?").assertIsDisplayed()
        compose.onNode(hasText("Empty trash") and isFocused() and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test
    fun openingALoadedTrashRefreshesIt() {
        compose.setTvContent { TvTrashScreen(state = TrashState(content = loaded), onEvent = { events += it; true }) }

        compose.runOnIdle { assertEquals(listOf<TrashEvent>(TrashEvent.Refresh), events) }
    }

    private fun show(state: () -> TrashState) {
        compose.setTvContent {
            TvTrashScreen(state = state(), onEvent = { events += it; true })
        }
        compose.onAllNodesWithText("Trash", substring = false)[0].assertIsDisplayed()
        compose.runOnIdle { events.remove(TrashEvent.Refresh) }
    }
}
