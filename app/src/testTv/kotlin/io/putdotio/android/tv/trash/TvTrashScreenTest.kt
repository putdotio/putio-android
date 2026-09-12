package io.putdotio.android.tv.trash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
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
        FilesItemId(7), FilesItemId(8), "smoke-two.txt", PutioFileType.TEXT, 10,
        "2026-09-06T10:00:00", "2026-09-20T10:00:00",
    )
    private val loaded = TrashContent.Loaded(listOf(item), nextCursor = null, total = 1, trashSizeBytes = 10)

    @Test
    fun rowsShowDatesAndCenterOffersRestoreThenTheConfirmationConfirms() {
        var state by mutableStateOf(TrashState(content = loaded))
        show { state }

        compose.onNodeWithText("10 B · Deleted Sep 6, 2026 · Expires Sep 20, 2026").assertIsDisplayed()
        compose.onNodeWithContentDescription("Actions for smoke-two.txt").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Restore").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<TrashEvent>(TrashEvent.SelectRestore(FilesItemId(7))), events)
        compose.onAllNodesWithText("Delete permanently").assertCountEquals(0)
        compose.onNodeWithContentDescription("Actions for smoke-two.txt").assertIsFocused()

        compose.runOnIdle { state = state.copy(confirmation = item, confirmationId = 3L) }
        compose.onNodeWithText("Restore item?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Restore").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(TrashEvent.ConfirmRestore(3L), events.last())
    }

    @Test
    fun deleteGoesThroughItsOwnConfirmationAndBackCancels() {
        var state by mutableStateOf(TrashState(content = loaded))
        show { state }

        compose.onNodeWithContentDescription("Actions for smoke-two.txt").performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Restore").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Delete permanently").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<TrashEvent>(TrashEvent.SelectDelete(FilesItemId(7))), events)

        compose.runOnIdle {
            state = state.copy(actionConfirmation = TrashAction.DeleteItem(item), actionConfirmationId = 4L)
        }
        compose.onNodeWithText("Delete permanently?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(TrashEvent.CancelAction, events.last())
    }

    @Test
    fun headerActionsAskForBulkConfirmations() {
        var state by mutableStateOf(TrashState(content = loaded))
        show { state }

        compose.onNodeWithContentDescription("Actions for smoke-two.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Restore all") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Empty trash") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<TrashEvent>(TrashEvent.SelectRestoreAll, TrashEvent.SelectEmpty), events)

        compose.runOnIdle { state = state.copy(actionConfirmation = TrashAction.Empty, actionConfirmationId = 5L) }
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(TrashEvent.CancelAction, events.last())
        compose.runOnIdle { state = state.copy(actionConfirmation = null, actionConfirmationId = null) }
        // The button that opened the confirmation takes focus back, not the list.
        compose.onNode(hasText("Empty trash") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun dateOnlyStampsFormatLikeTimestamps() {
        show { TrashState(content = loaded.copy(items = listOf(item.copy(deletedAt = "2026-09-06", expirationDate = "2026-09-20")))) }

        compose.onNodeWithText("10 B · Deleted Sep 6, 2026 · Expires Sep 20, 2026").assertIsDisplayed()
    }

    @Test
    fun anUnconfirmedRestoreOffersCheckStatusAndASettledOneOffersOk() {
        var state by mutableStateOf(
            TrashState(
                content = loaded,
                restoreOutcome = TrashRestoreOutcome(item, TrashRestoreSubmission.ACKNOWLEDGED, TrashRestoreCheck.UNAVAILABLE),
            ),
        )
        show { state }

        compose.onNodeWithText("smoke-two.txt is not in Files yet. Check again in a moment.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Actions for smoke-two.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithText("Check status").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<TrashEvent>(TrashEvent.CheckRestore), events)

        compose.runOnIdle {
            state = state.copy(
                restoreOutcome = null,
                actionOutcome = TrashActionOutcome(TrashAction.Empty, TrashActionSubmission.ACKNOWLEDGED, TrashActionCheck.VERIFIED),
                content = loaded.copy(items = emptyList()),
            )
        }
        compose.onNodeWithText("Trash is empty.").assertIsDisplayed()
        compose.onNodeWithText("Your trash is empty").assertIsDisplayed()
        compose.onNodeWithText("When you send files to trash, we keep them here for 14 days.").assertIsDisplayed()
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithText("OK").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(TrashEvent.DismissActionOutcome, events.last())
    }

    @Test
    fun aFailedReadFocusesTryAgainAndPagingLoadsMore() {
        var state by mutableStateOf(
            TrashState(content = TrashContent.Error(FilesFailure.NetworkUnavailable(PutioConfigurationException("x")))),
        )
        show { state }

        compose.onNodeWithText("Couldn’t load trash").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<TrashEvent>(TrashEvent.Retry), events)

        compose.runOnIdle { state = TrashState(content = loaded.copy(nextCursor = FilesCursor("c"))) }
        compose.onNodeWithContentDescription("Actions for smoke-two.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(TrashEvent.LoadNextPage, events.last())

        compose.runOnIdle { state = TrashState(content = loaded) }
        compose.onNodeWithContentDescription("Actions for smoke-two.txt").assertIsFocused()
    }

    @Test
    fun anEmptyPageWithACursorKeepsLoadMoreReachable() {
        show { TrashState(content = TrashContent.Loaded(emptyList(), FilesCursor("c"), total = 5, trashSizeBytes = 1)) }

        compose.onAllNodesWithText("Your trash is empty").assertCountEquals(0)
        // The list has only its paging control, which is where entry lands.
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
            pressKey(Key.DirectionUp)
        }
        assertEquals(listOf<TrashEvent>(TrashEvent.LoadNextPage), events)
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun aConfirmationThatGreetsTheRowsKeepsItsFocus() {
        show { TrashState(content = loaded, actionConfirmation = TrashAction.Empty, actionConfirmationId = 9L) }

        compose.onNodeWithText("Empty trash?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsFocused()
    }

    private fun show(state: () -> TrashState) {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvTrashScreen(state = state(), onEvent = { events += it; true })
            }
        }
        compose.onNodeWithText("Trash").assertIsDisplayed()
    }
}
