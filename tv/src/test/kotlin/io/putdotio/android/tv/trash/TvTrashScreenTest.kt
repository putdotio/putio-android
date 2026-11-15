package io.putdotio.android.tv.trash

import io.putdotio.android.tv.assertHeldDownWaitsForScroll
import io.putdotio.android.tv.awaitText
import io.putdotio.android.tv.setTvContent
import io.putdotio.android.tv.pressCenter
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.design.LocalBuildDispatcher
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
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun rowsShowSizeAndDaysLeftAndCenterRestoresAtOnceWithFocusKeptOnTheRow() {
        var state by mutableStateOf(TrashState(content = loaded))
        show { state }

        compose.onNodeWithText("Items stay in Trash for 14 days.").assertIsDisplayed()
        compose.awaitText("1 day left")
        compose.onNodeWithText("12 days left").assertIsDisplayed()
        compose.onNodeWithText("1 day left").assertIsDisplayed()
        compose.onNodeWithText("1.2 GB").assertIsDisplayed()
        compose.onNodeWithText("1.4 GB · Deleted Sep 6", substring = true).assertIsDisplayed()
        compose.onNodeWithText("in 12 days").assertIsDisplayed()
        compose.onNode(row).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.Restore(FilesItemId(7))), events)

        compose.runOnIdle {
            state = state.copy(restoreOutcome = TrashRestoreOutcome(item, TrashRestoreSubmission.ACKNOWLEDGED))
        }
        compose.onNodeWithText("Restore started for smoke-two.txt.").assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNode(row).assertIsFocused()
    }

    @Test
    fun aHeldDownWaitsForTheListToScrollTheFocusedRowIntoView() {
        val names = (1..40L).map { "trashed-$it.txt" }
        val items = names.mapIndexed { index, name -> item.copy(id = FilesItemId(100L + index), name = name) }
        show { TrashState(content = loaded.copy(items = items, total = items.size)) }
        compose.assertHeldDownWaitsForScroll(TV_TRASH_LIST_TAG, names.map { hasText(it) and hasClickAction() })
    }

    @Test
    fun aRestoredItemLeavingTheListHandsFocusToTheNextRow() {
        val third = item.copy(id = FilesItemId(11), name = "third.txt")
        var state by mutableStateOf(TrashState(content = loaded.copy(items = listOf(item, folder, third), total = 3)))
        show { state }
        val oldDownloads = hasText("Old downloads") and hasClickAction()

        compose.onNode(row).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(oldDownloads).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.Restore(FilesItemId(9))), events)
        compose.runOnIdle { state = TrashState(content = loaded.copy(items = listOf(item, third), total = 2)) }
        compose.onNode(hasText("third.txt") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun theSidePanelRestoreStartsAtOnceAndFocusMovesToTheNextRowOnceTheItemLeaves() {
        val third = item.copy(id = FilesItemId(11), name = "third.txt")
        var state by mutableStateOf(TrashState(content = loaded.copy(items = listOf(item, folder, third), total = 3)))
        show { state }
        val restore = hasText("Restore") and hasClickAction()

        compose.onNode(row).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(restore).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.Restore(FilesItemId(7))), events)
        compose.runOnIdle {
            state = state.copy(restoreOutcome = TrashRestoreOutcome(item, TrashRestoreSubmission.SUBMITTING))
        }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNode(restore).assertIsFocused()
        compose.runOnIdle { state = TrashState(content = loaded.copy(items = listOf(folder, third), total = 2)) }
        compose.onNode(hasText("Old downloads") and hasClickAction()).assertIsFocused()
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
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Delete permanently") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
        }
        assertEquals(listOf<TrashEvent>(TrashEvent.SelectDelete(FilesItemId(9))), events)

        compose.runOnIdle {
            state = state.copy(actionConfirmation = TrashAction.DeleteItem(folder), actionConfirmationId = 4L)
        }
        compose.onNodeWithText("Delete permanently?").assertIsDisplayed()
        compose.onNode(focusedInDialog("Cancel")).performKeyInput { pressCenter() }
        assertEquals(TrashEvent.CancelAction, events.last())
        compose.runOnIdle { state = state.copy(actionConfirmation = null, actionConfirmationId = null) }
        compose.onNode(hasText("Delete permanently") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun restoreAllStartsAtOnceAndEmptyTrashConfirmsWithTheItemCount() {
        var state by mutableStateOf(TrashState(content = loaded))
        show { state }

        compose.onNode(row).performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNode(hasText("Empty trash") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("Restore all") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Empty trash") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.RestoreAll, TrashEvent.SelectEmpty), events)

        compose.runOnIdle { state = state.copy(actionConfirmation = TrashAction.Empty, actionConfirmationId = 5L) }
        compose.onNodeWithText("Empty trash?").assertIsDisplayed()
        compose.onNodeWithText("2 items are deleted permanently. This can't be undone.").assertIsDisplayed()
        compose.onNode(focusedInDialog("Cancel")).performKeyInput { pressCenter() }
        assertEquals(TrashEvent.CancelAction, events.last())
        compose.runOnIdle { state = state.copy(actionConfirmation = null, actionConfirmationId = null) }
        // The button that opened the confirmation takes focus back, not the list.
        compose.onNode(hasText("Empty trash") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun refreshInTheListHeaderReloadsTrashAndIsIgnoredWhileItRuns() {
        var state by mutableStateOf(TrashState(content = loaded))
        show { state }

        compose.onNode(row).assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.Refresh), events)

        compose.runOnIdle { state = state.copy(content = loaded.copy(isRefreshing = true)) }
        compose.onNode(row).assertIsDisplayed()
        compose.onNode(hasText("Refresh") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.Refresh), events)
    }

    @Test
    fun aRefreshKeepsTheFocusedRowOrMovesToTheRowThatFollowedIt() {
        val third = item.copy(id = FilesItemId(11), name = "third.txt")
        var state by mutableStateOf(TrashState(content = loaded.copy(items = listOf(item, folder, third), total = 3)))
        show { state }
        val oldDownloads = hasText("Old downloads") and hasClickAction()

        compose.onNode(row).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(oldDownloads).assertIsFocused()
        compose.runOnIdle { state = TrashState(content = loaded.copy(items = listOf(folder, item, third), total = 3)) }
        compose.onNode(oldDownloads).assertIsFocused()

        compose.runOnIdle { state = TrashState(content = loaded.copy(items = listOf(third, item), total = 2)) }
        compose.onNode(row).assertIsFocused()
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
    fun aPendingRestoreLeavesRowsAndDetailActionsInert() {
        show {
            TrashState(
                content = loaded,
                restoreOutcome = TrashRestoreOutcome(item, TrashRestoreSubmission.ACKNOWLEDGED),
            )
        }

        compose.onNode(row).assertIsFocused().performKeyInput { pressCenter() }
        compose.onNode(hasText("Restore") and hasClickAction()).assertIsNotEnabled()
        compose.onNode(hasText("Delete permanently") and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<TrashEvent>(), events) }
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
        compose.onAllNodes(hasText("Restore all") and hasClickAction()).assertCountEquals(0)
        compose.onAllNodes(hasText("Empty trash") and hasClickAction()).assertCountEquals(0)
        compose.onNodeWithText("Your trash is empty").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithText("OK").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(TrashEvent.DismissActionOutcome, events.last())
    }

    @Test
    fun okOnASettledOutcomeHandsFocusBackToTheRows() {
        var state by mutableStateOf(
            TrashState(
                content = loaded,
                restoreOutcome = TrashRestoreOutcome(
                    item,
                    TrashRestoreSubmission.ACKNOWLEDGED,
                    TrashRestoreCheck.AVAILABLE,
                ),
            ),
        )
        show { state }

        compose.onNode(row).assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("OK").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.DismissRestoreOutcome), events)

        compose.runOnIdle { state = state.copy(restoreOutcome = null) }
        compose.onNode(row).assertIsFocused()
    }

    @Test
    fun tryAgainOnAFailedRefreshHandsFocusBackToTheRows() {
        var state by mutableStateOf(
            TrashState(
                content = loaded.copy(
                    refreshFailure = PutioFailure.NetworkUnavailable(PutioConfigurationException("x")),
                ),
            ),
        )
        show { state }

        compose.onNode(row).assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.Refresh), events)

        compose.runOnIdle { state = TrashState(content = loaded) }
        compose.onNode(row).assertIsFocused()
    }

    @Test
    fun aFailedReadFocusesTryNowAndRowsNearTheEndLoadTheNextPage() {
        var state by mutableStateOf(
            TrashState(content = TrashContent.Error(PutioFailure.NetworkUnavailable(PutioConfigurationException("x")))),
        )
        show { state }

        compose.onNodeWithText("Can’t connect to put.io").assertIsDisplayed()
        compose.onNodeWithText("Try now").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<TrashEvent>(TrashEvent.Retry), events)

        compose.runOnIdle { state = TrashState(content = loaded.copy(nextCursor = FilesCursor("c"))) }
        compose.onNode(row).assertIsFocused().performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        compose.onNode(hasText("Old downloads") and hasClickAction()).assertIsFocused()
        assertEquals(1, events.count { it == TrashEvent.LoadNextPage })

        compose.runOnIdle {
            state = TrashState(content = loaded.copy(nextCursor = FilesCursor("c"), isLoadingMore = true))
        }
        compose.onNodeWithContentDescription("Loading more items").assertIsDisplayed()
        compose.runOnIdle {
            state = TrashState(
                content = loaded.copy(
                    nextCursor = FilesCursor("c"),
                    pageFailure = PutioFailure.NetworkUnavailable(PutioConfigurationException("x")),
                ),
            )
        }
        compose.onNodeWithText("Couldn’t load more items.").assertIsDisplayed()
        compose.onNode(hasText("Old downloads") and hasClickAction()).assertIsFocused()
        assertEquals(1, events.count { it == TrashEvent.LoadNextPage })

        compose.runOnIdle { state = TrashState(content = loaded) }
        compose.onNode(hasText("Old downloads") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun anEmptyPageWithACursorLoadsTheNextOnItsOwn() {
        show { TrashState(content = TrashContent.Loaded(emptyList(), FilesCursor("c"), total = 5, trashSizeBytes = 1)) }

        compose.onAllNodesWithText("Your trash is empty").assertCountEquals(0)
        compose.onNodeWithContentDescription("Loading more items").assertIsDisplayed()
        assertEquals(listOf<TrashEvent>(TrashEvent.LoadNextPage), events)
    }

    @Test
    fun aConfirmationThatGreetsTheRowsKeepsItsFocus() {
        show { TrashState(content = loaded, actionConfirmation = TrashAction.Empty, actionConfirmationId = 9L) }

        compose.onNodeWithText("Empty trash?").assertIsDisplayed()
        compose.onNode(focusedInDialog("Cancel")).assertIsDisplayed()
    }

    @Test
    fun openingALoadedTrashRefreshesIt() {
        compose.setTvContent { TvTrashScreen(state = TrashState(content = loaded), onEvent = { events += it; true }) }

        compose.runOnIdle { assertEquals(listOf<TrashEvent>(TrashEvent.Refresh), events) }
    }

    @Test
    fun aPaneReturningFromAnotherTabKeepsItsLoadedPages() {
        var shown by mutableStateOf(true)
        compose.setTvContent {
            val panes = rememberSaveableStateHolder()
            if (shown) {
                panes.SaveableStateProvider("trash") {
                    TvTrashScreen(state = TrashState(content = loaded), onEvent = { events += it; true })
                }
            }
        }

        compose.runOnIdle { shown = false }
        compose.runOnIdle { shown = true }
        compose.runOnIdle { assertEquals(listOf<TrashEvent>(TrashEvent.Refresh), events) }
    }

    @Test
    fun aPaneOpenedBehindAConfirmationKeepsItOpen() {
        compose.setTvContent {
            TvTrashScreen(
                state = TrashState(content = loaded, actionConfirmation = TrashAction.Empty, actionConfirmationId = 9L),
                onEvent = { events += it; true },
            )
        }

        compose.onNodeWithText("Empty trash?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<TrashEvent>(), events) }
    }

    @Test
    fun theDetailKeepsItsItemWhenThePaneReturnsWithFocusOnRestore() {
        var shown by mutableStateOf(true)
        val paneFocus = FocusRequester()
        compose.setTvContent {
            val panes = rememberSaveableStateHolder()
            if (shown) {
                panes.SaveableStateProvider("trash") {
                    TvTrashScreen(
                        state = TrashState(content = loaded),
                        onEvent = { events += it; true },
                        modifier = Modifier.focusRequester(paneFocus),
                    )
                }
            }
        }
        compose.onNode(row).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Old downloads") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Restore") and hasClickAction()).assertIsFocused()

        compose.runOnIdle { shown = false }
        compose.runOnIdle { shown = true }
        compose.runOnIdle { paneFocus.requestFocus() }
        compose.onNodeWithText("in 1 day").assertIsDisplayed()
        compose.onNode(hasText("Restore") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(TrashEvent.Restore(FilesItemId(9)), events.last())
    }

    @Test
    fun deletingTheLastRowFromThePanelHandsFocusBackToTheRows() {
        var state by mutableStateOf(TrashState(content = loaded))
        show { state }
        compose.onNode(row).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Old downloads") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Delete permanently") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
        }
        compose.runOnIdle {
            state = state.copy(actionConfirmation = TrashAction.DeleteItem(folder), actionConfirmationId = 4L)
        }
        compose.onNode(focusedInDialog("Cancel")).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(focusedInDialog("Delete permanently")).performKeyInput { pressCenter() }
        compose.runOnIdle { state = state.copy(actionConfirmation = null, actionConfirmationId = null) }
        compose.onNode(hasText("Delete permanently") and hasClickAction()).assertIsFocused()

        compose.runOnIdle { state = TrashState(content = loaded.copy(items = listOf(item), total = 1)) }

        compose.onNode(row).assertIsFocused()
    }

    private fun focusedInDialog(label: String) = hasText(label) and isFocused() and hasAnyAncestor(isDialog())

    @Test
    fun enteringThePaneWhileTheFirstRowsBuildLandsOnTheSkeleton() {
        val build = StandardTestDispatcher()
        val pane = FocusRequester()
        compose.setTvContent {
            CompositionLocalProvider(LocalBuildDispatcher provides build) {
                TvTrashScreen(
                    state = TrashState(content = loaded),
                    onEvent = { true },
                    modifier = Modifier.focusRequester(pane),
                )
            }
        }
        compose.runOnIdle { pane.requestFocus() }
        compose.onNode(row).assertDoesNotExist()
        compose.onNode(isFocused()).assertExists()

        compose.runOnIdle { build.scheduler.advanceUntilIdle() }
        compose.onNode(row).assertIsFocused()
    }

    @Test
    fun aRefreshThatDropsTheFocusedRowKeepsItsDetailUntilTheNewRowsShow() {
        val build = StandardTestDispatcher()
        var state by mutableStateOf(TrashState(content = loaded))
        compose.setTvContent {
            CompositionLocalProvider(LocalBuildDispatcher provides build) {
                TvTrashScreen(state = state, onEvent = { true })
            }
        }
        compose.runOnIdle { build.scheduler.advanceUntilIdle() }
        val detailTitle = hasText("smoke-two.txt") and !hasClickAction()
        compose.onNode(row).assertIsFocused()
        compose.onAllNodes(detailTitle).assertCountEquals(1)

        compose.runOnIdle { state = TrashState(content = loaded.copy(items = listOf(folder), total = 1)) }
        compose.onNode(row).assertIsDisplayed()
        compose.onAllNodes(detailTitle).assertCountEquals(1)

        compose.runOnIdle { build.scheduler.advanceUntilIdle() }
        compose.onNode(row).assertDoesNotExist()
        compose.onNode(hasText("Old downloads") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun aRefreshPagesOnlyOnceItsRowsShow() {
        val build = StandardTestDispatcher()
        fun page(name: String, first: Long, cursor: String) = TrashContent.Loaded(
            (first until first + 40).map { item.copy(id = FilesItemId(it), name = "$name $it.txt") },
            nextCursor = FilesCursor(cursor), total = 200, trashSizeBytes = 10,
        )
        var state by mutableStateOf(TrashState(content = page("old", 100, "c1")))
        compose.setTvContent {
            CompositionLocalProvider(LocalBuildDispatcher provides build) {
                TvTrashScreen(state = state, onEvent = { events += it; true })
            }
        }
        compose.runOnIdle { build.scheduler.advanceUntilIdle() }
        compose.runOnIdle { events.clear() }

        compose.runOnIdle { state = TrashState(content = page("new", 300, "c2")) }
        compose.onNodeWithTag(TV_TRASH_LIST_TAG).performScrollToIndex(39)
        assertEquals(emptyList<TrashEvent>(), compose.runOnIdle { events.filter { it == TrashEvent.LoadNextPage } })

        compose.runOnIdle { build.scheduler.advanceUntilIdle() }
        compose.onNodeWithText("new 339.txt").assertIsDisplayed()
        assertEquals(listOf<TrashEvent>(TrashEvent.LoadNextPage), compose.runOnIdle { events.filter { it == TrashEvent.LoadNextPage } })
    }

    private fun show(state: () -> TrashState) {
        compose.setTvContent {
            TvTrashScreen(state = state(), onEvent = { events += it; true })
        }
        compose.onAllNodesWithText("Trash", substring = false)[0].assertIsDisplayed()
        compose.runOnIdle { events.remove(TrashEvent.Refresh) }
    }
}
