package io.putdotio.android

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.MOBILE_TRANSFERS_ADD_FAB_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFERS_LIST_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFERS_OVERFLOW_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_ACTIONS_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_ADD_FIELD_TAG
import io.putdotio.android.transfers.MobileTransfersOverflow
import io.putdotio.android.transfers.MobileTransfersScreen
import io.putdotio.android.transfers.TransferAction
import io.putdotio.android.transfers.TransferAddReceipt
import io.putdotio.android.transfers.TransferCursor
import io.putdotio.android.transfers.TransferFileId
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferMutation
import io.putdotio.android.transfers.TransferNavigation
import io.putdotio.android.transfers.TransferRetryOutcome
import io.putdotio.android.transfers.TransferSource
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersRefresh
import io.putdotio.android.transfers.TransfersRequestId
import io.putdotio.android.transfers.TransfersState
import io.putdotio.android.transfers.copyForTest
import io.putdotio.android.transfers.mobileTransferCardTag
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileTransfersScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cardsSitInSectionsWithLongNamesCutInTheMiddle() {
        val long = "The.Wire.S03E04.Back.Burners.1080p.BluRay.x264-DEMAND.mkv"
        val rows = listOf(
            transferRow(1L, AppTransferStatus.Completed, fileId = 11L),
            transferRow(2L, AppTransferStatus.Failed),
            transferRow(3L, AppTransferStatus.Downloading).copy(name = long),
            transferRow(4L, AppTransferStatus.Seeding, fileId = 14L),
        )
        setScreen(transfersTestState(TransfersContent.Ready(rows, TransfersPaging.Complete)), onEvent = {})

        val order = listOf("Active", "Needs attention", "Completed")
            .map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(order.sorted(), order)
        compose.onNodeWithText("The.Wire.S03E04.…-DEMAND.mkv").assertIsDisplayed()
        compose.onNodeWithContentDescription(long).assertIsDisplayed()
        val failed = compose.onNodeWithTag(mobileTransferCardTag(TransferId(2L))).fetchSemanticsNode().boundsInRoot
        val seeding = compose.onNodeWithTag(mobileTransferCardTag(TransferId(4L))).fetchSemanticsNode().boundsInRoot
        assertTrue("Seeding sits under Completed, after the failure", seeding.top > failed.bottom)
    }

    @Test
    fun runningCardShowsItsWordsAndNumbersAndStopsFromTheActionsSheetAfterAConfirm() {
        val events = mutableListOf<TransfersEvent>()
        val item = transferRow(1L, AppTransferStatus.Downloading).copy(
            percentDone = 42.0,
            downloadedBytes = 524_288.0,
            downloadSpeedBytesPerSecond = 1_024.0,
            estimatedSecondsRemaining = 90.0,
        )
        setScreen(transfersTestState(TransfersContent.Ready(listOf(item), TransfersPaging.Complete)), events::add)

        compose.onNodeWithText("Downloading · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText(" of ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1m 30s").assertIsDisplayed()
        compose.onAllNodesWithText("/s", substring = true).assertCountEquals(1)
        compose.onNodeWithTag(mobileTransferCardTag(item.id))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .performClick()

        compose.onNodeWithTag(MOBILE_TRANSFER_ACTIONS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Downloading · 42%").assertIsDisplayed()
        compose.onNodeWithText("Time left").assertIsDisplayed()
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
        compose.onAllNodesWithText("Remove from list").assertCountEquals(0)
        compose.onNodeWithText("Stop transfer").performClick()
        compose.onNodeWithText("Stop transfer?").assertIsDisplayed()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        compose.onNodeWithText("Stop transfer").performClick()

        assertEquals(listOf<TransfersEvent>(TransfersEvent.Cancel(item.id)), events)
    }

    @Test
    fun failedCardsStayNeutralWithTheServerReasonAndRetryAtOnce() {
        val events = mutableListOf<TransfersEvent>()
        val rows = listOf(
            transferRow(1L, AppTransferStatus.Failed).copy(errorMessage = "Downloading text/html is not allowed."),
            transferRow(2L, AppTransferStatus.Failed),
            transferRow(3L, AppTransferStatus.Waiting).copy(errorMessage = "Tracker did not respond."),
        )
        setScreen(transfersTestState(TransfersContent.Ready(rows, TransfersPaging.Complete)), events::add)

        compose.onNodeWithText("Failed · Downloading text/html is not allowed.").assertIsDisplayed()
        compose.onNodeWithText("Failed · This transfer needs attention.").assertIsDisplayed()
        compose.onNodeWithText(" · Tracker did not respond.", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry transfer transfer-2").performClick()
        assertEquals(listOf<TransfersEvent>(TransfersEvent.RetryTransfer(TransferId(2L))), events)
        compose.onAllNodesWithText("Retry transfer?").assertCountEquals(0)

        compose.onNodeWithTag(mobileTransferCardTag(TransferId(1L))).performClick()
        compose.onAllNodesWithText("Stop transfer").assertCountEquals(0)
        compose.onNodeWithText("Retry").performClick()
        assertEquals(TransfersEvent.RetryTransfer(TransferId(1L)), events.last())
    }

    @Test
    fun theActionsSheetShowsAReasonTheCardCuts() {
        val reason = "Downloading text/html is not allowed. " +
            "The link points to a web page, not a file, so put.io stopped before it saved anything. " +
            "Copy the direct link to the file and add it again."
        val row = transferRow(1L, AppTransferStatus.Failed).copy(errorMessage = reason)
        setScreen(transfersTestState(TransfersContent.Ready(listOf(row), TransfersPaging.Complete)), onEvent = {})
        compose.onAllNodesWithText(reason).assertCountEquals(0)

        compose.onNodeWithTag(mobileTransferCardTag(row.id)).performClick()

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(reason).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
    }

    @Test
    fun finishedCardsOpenOnTapAndTheirSheetRemovesThemWithoutAConfirm() {
        val events = mutableListOf<TransfersEvent>()
        val available = transferRow(1L, AppTransferStatus.Completed, fileId = 11L).copy(saveParentId = 8L)
        val preparing = transferRow(2L, AppTransferStatus.Completed)
        val unavailable = transferRow(3L, AppTransferStatus.Completed, fileId = 13L, userFileExists = false)
        val unknown = transferRow(4L, AppTransferStatus.Unknown("SERVER_INTERNAL_STATE"))
        setScreen(
            transfersTestState(
                TransfersContent.Ready(listOf(available, preparing, unavailable, unknown), TransfersPaging.Complete),
            ),
            events::add,
        )

        compose.onNodeWithText("Preparing file…", substring = true).assertIsDisplayed()
        compose.onNodeWithText("File is no longer available", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Updating…", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("SERVER_INTERNAL_STATE", substring = true).assertCountEquals(0)
        compose.onNodeWithTag(mobileTransferCardTag(available.id)).performClick()
        assertEquals(TransfersEvent.Open(available.id), events.last())

        compose.onNodeWithTag(mobileTransferCardTag(available.id)).performTouchInput { longClick() }
        compose.onAllNodesWithText("Copy source link").assertCountEquals(0)
        compose.onNodeWithText("Open folder").performClick()
        assertEquals(TransfersEvent.OpenFolder(available.id), events.last())
        compose.onNodeWithTag(mobileTransferCardTag(available.id)).performTouchInput { longClick() }
        compose.onNodeWithText("Remove from list").performClick()
        assertEquals(TransfersEvent.Remove(available.id), events.last())
        compose.onNodeWithTag(MOBILE_TRANSFER_ACTIONS_TAG).assertDoesNotExist()

        compose.onNodeWithTag(mobileTransferCardTag(unknown.id)).performClick()
        compose.onAllNodesWithText("Stop transfer").assertCountEquals(0)
        compose.onAllNodesWithText("Remove from list").assertCountEquals(0)
    }

    @Test
    fun copySourceLinkPutsOnlyTheLinkOnTheClipboardAndSaysSo() {
        val link = "magnet:?xt=urn:btih:source"
        val item = transferRow(1L, AppTransferStatus.Seeding, fileId = 11L).copy(source = TransferSource(link))
        setScreen(transfersTestState(TransfersContent.Ready(listOf(item), TransfersPaging.Complete)), onEvent = {})

        compose.onNodeWithTag(mobileTransferCardTag(item.id)).performTouchInput { longClick() }
        compose.onNodeWithText("Open file").assertIsDisplayed()
        compose.onNodeWithText("Stop transfer").assertIsDisplayed()
        compose.onNodeWithText("Copy source link").performClick()

        compose.onNodeWithText("Link copied").assertIsDisplayed()
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(ClipboardManager::class.java)
        assertEquals(link, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(clipboard.primaryClip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true)
    }

    @Test
    fun retryOutcomesAreReportedInASnackbarAndDismissed() {
        val events = mutableListOf<TransfersEvent>()
        val content = TransfersContent.Ready(
            listOf(transferRow(2L, AppTransferStatus.Queued)),
            TransfersPaging.Complete,
        )
        var current by mutableStateOf(transfersTestState(content))
        setMutableScreen({ current }, events::add)

        compose.runOnIdle {
            current = current.copyForTest(retryOutcome = TransferRetryOutcome.Accepted(TransfersRequestId(3L)))
        }
        compose.onNodeWithText("Retrying transfer").assertIsDisplayed()

        compose.runOnIdle {
            current =
                current.copyForTest(
                    retryOutcome =
                        TransferRetryOutcome.Failed(
                            TransfersRequestId(4L),
                            PutioFailure.AccessDenied(PutioConfigurationException("forbidden")),
                        ),
                )
        }
        compose.onNodeWithText("Couldn’t retry transfer. It has no error to retry.").assertIsDisplayed()
        compose.onAllNodesWithText("Couldn’t update transfer").assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(TransfersEvent.DismissRetryOutcome(TransfersRequestId(3L)), events.single())
        }

        compose.mainClock.advanceTimeBy(SNACKBAR_TIMEOUT_MS)
        compose.onAllNodesWithText("Couldn’t retry transfer. It has no error to retry.").assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(
                listOf<TransfersEvent>(
                    TransfersEvent.DismissRetryOutcome(TransfersRequestId(3L)),
                    TransfersEvent.DismissRetryOutcome(TransfersRequestId(4L)),
                ),
                events,
            )
        }
    }

    @Test
    fun newTransferNewsReplacesAVisibleSnackbarInsteadOfQueueing() {
        val content = TransfersContent.Ready(
            listOf(transferRow(2L, AppTransferStatus.Queued)),
            TransfersPaging.Complete,
        )
        var current by mutableStateOf(transfersTestState(content))
        setMutableScreen({ current }, onEvent = {})

        compose.runOnIdle {
            current = current.copyForTest(lastAddReceipt = TransferAddReceipt(TransfersRequestId(1L), 1, emptyList()))
        }
        compose.onNodeWithText("1 transfer added").assertIsDisplayed()

        compose.runOnIdle {
            current = current.copyForTest(retryOutcome = TransferRetryOutcome.Accepted(TransfersRequestId(3L)))
        }
        compose.onNodeWithText("Retrying transfer").assertIsDisplayed()
        compose.onAllNodesWithText("1 transfer added").assertCountEquals(0)

        compose.runOnIdle {
            current = current.copyForTest(lastAddReceipt = TransferAddReceipt(TransfersRequestId(5L), 1, emptyList()))
        }
        compose.onNodeWithText("1 transfer added").assertIsDisplayed()
        compose.onAllNodesWithText("Retrying transfer").assertCountEquals(0)
    }

    @Test
    fun changingSessionDismissesAStaleConfirmation() {
        var sessionId by mutableStateOf(MobileAuthSessionId(1L))
        val current = transfersTestState(
            TransfersContent.Ready(listOf(transferRow(1L, AppTransferStatus.Downloading)), TransfersPaging.Complete),
        )
        compose.setContent {
            PutioTheme { MobileTransfersScreen(state = current, onEvent = {}, sessionId = sessionId) }
        }

        compose.onNodeWithTag(mobileTransferCardTag(TransferId(1L))).performClick()
        compose.onNodeWithText("Stop transfer").performClick()
        compose.onNodeWithText("Stop transfer?").assertIsDisplayed()

        compose.runOnIdle { sessionId = MobileAuthSessionId(2L) }

        compose.onAllNodesWithText("Stop transfer?").assertCountEquals(0)
    }

    @Test
    fun theOverflowRefreshesAndClearsCompletedAfterAConfirmOnceTransfersAreListed() {
        val events = mutableListOf<TransfersEvent>()
        var current by mutableStateOf(transfersTestState(TransfersContent.InitialLoading(TransfersRequestId(1L))))
        compose.setContent {
            PutioTheme { MobileTransfersOverflow(current, events::add, MobileAuthSessionId(1L)) }
        }
        compose.onNodeWithTag(MOBILE_TRANSFERS_OVERFLOW_TAG).assertDoesNotExist()

        compose.runOnIdle { current = transfersTestState(TransfersContent.Empty) }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Refresh").assertIsEnabled()
        compose.onAllNodesWithText("Clear completed").assertCountEquals(0)
        compose.onNodeWithText("Refresh").performClick()
        compose.runOnIdle { events.clear() }

        compose.runOnIdle {
            current = transfersTestState(
                TransfersContent.Ready(listOf(transferRow(1L, AppTransferStatus.Completed)), TransfersPaging.Complete),
            )
        }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Refresh").performClick()
        assertEquals(listOf<TransfersEvent>(TransfersEvent.Refresh), events)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Clear completed").performClick()
        compose.onNodeWithText("Clear completed transfers?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, events.size) }
        compose.onNodeWithText("Clear").performClick()
        assertEquals(TransfersEvent.CleanCompleted, events.last())

        // The menu stays open while the state moves through what blocks each action.
        val listed = current
        val busy = TransfersRequestId(9L)
        compose.onNodeWithContentDescription("More options").performClick()
        fun given(next: TransfersState, refresh: Boolean, clear: Boolean) {
            compose.runOnIdle { current = next }
            compose.onNodeWithText("Refresh").assert(if (refresh) isEnabled() else isNotEnabled())
            compose.onNodeWithText("Clear completed").assert(if (clear) isEnabled() else isNotEnabled())
        }
        given(listed.copyForTest(mutation = TransferMutation.Running(TransferAction.Clean, busy)), false, false)
        given(listed.copyForTest(navigation = TransferNavigation.Resolving(TransferFileId(1L), busy)), false, false)
        given(listed.copyForTest(refresh = TransfersRefresh.Refreshing(busy)), refresh = false, clear = true)
        given(listed.copyForTest(refresh = TransfersRefresh.Polling(busy)), refresh = false, clear = true)
        given(listed, refresh = true, clear = true)
    }

    @Test
    fun loadingEmptyFailureRefreshAndPagingStayRecoverable() {
        var current by mutableStateOf(transfersTestState(TransfersContent.InitialLoading(TransfersRequestId(1L))))
        val events = mutableListOf<TransfersEvent>()
        setMutableScreen({ current }, events::add)

        compose.onNodeWithText("Loading transfers").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRANSFERS_ADD_FAB_TAG).assertIsDisplayed()
        compose.runOnIdle { current = transfersTestState(TransfersContent.Empty) }
        compose.onNodeWithText("Nothing in the queue.").assertIsDisplayed()
        compose.onNodeWithText("Paste a magnet link or a URL, and put.io handles the rest.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRANSFERS_ADD_FAB_TAG).assertDoesNotExist()
        compose.onNode(AddTransferAction).performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        compose.runOnIdle {
            current = transfersTestState(TransfersContent.Failed(PutioFailure.Unexpected(IllegalStateException("x"))))
        }
        compose.onNodeWithText("Try again").performClick()
        assertEquals(TransfersEvent.RetryLoad, events.last())

        compose.runOnIdle {
            current =
                transfersTestState(
                    TransfersContent.Ready(
                        listOf(transferRow(9L, AppTransferStatus.Waiting)),
                        TransfersPaging.Failed(
                            TransferCursor("next"),
                            PutioFailure.Unexpected(IllegalStateException("paging")),
                        ),
                    ),
                    refresh = TransfersRefresh.Failed(PutioFailure.Unexpected(IllegalStateException("refresh"))),
                )
        }
        compose.onNodeWithText("Couldn’t refresh transfers.").assertIsDisplayed()
        compose.onNodeWithText("Couldn’t load more transfers.").assertIsDisplayed()
    }

    @Test
    fun busyStatesDisableTheCardsAndActionsTheyBlock() {
        val active = listOf(
            transferRow(1L, AppTransferStatus.Downloading),
            transferRow(2L, AppTransferStatus.Failed),
            transferRow(3L, AppTransferStatus.Completed, fileId = 13L),
        )
        var current by mutableStateOf(transfersTestState(TransfersContent.Empty))
        setMutableScreen({ current }, onEvent = {})
        fun given(next: TransfersState, assertions: () -> Unit) {
            compose.runOnIdle { current = next }
            assertions()
        }

        given(
            transfersTestState(TransfersContent.Ready(active, TransfersPaging.Complete)).copyForTest(
                mutation = TransferMutation.Running(TransferAction.Cancel(active.first().id), TransfersRequestId(9L)),
            ),
        ) { assertCardsEnabled(false) }
        given(
            transfersTestState(TransfersContent.Ready(active, TransfersPaging.Complete)).copyForTest(
                navigation = TransferNavigation.Resolving(TransferFileId(13L), TransfersRequestId(9L)),
            ),
        ) { assertCardsEnabled(false) }
        given(
            transfersTestState(
                TransfersContent.Ready(active, TransfersPaging.Complete),
                refresh = TransfersRefresh.Refreshing(TransfersRequestId(9L)),
            ),
        ) { assertCardsEnabled(false) }
        given(
            transfersTestState(
                TransfersContent.Ready(active, TransfersPaging.Complete),
                refresh = TransfersRefresh.Polling(TransfersRequestId(9L)),
            ),
        ) { assertCardsEnabled(true) }
    }

    private fun assertCardsEnabled(enabled: Boolean) {
        val retry = compose.onNodeWithContentDescription("Retry transfer", substring = true)
        val open = compose.onNodeWithTag(mobileTransferCardTag(TransferId(3L)))
        if (enabled) {
            retry.assertIsEnabled()
            open.assertIsEnabled()
        } else {
            retry.assertIsNotEnabled()
            open.assertIsNotEnabled()
        }
    }

    @Test
    fun pollingAndARunningAddDisableRecoveryAndPagingControls() {
        val seeding = listOf(transferRow(1L, AppTransferStatus.Seeding, fileId = 12L))
        val downloading = listOf(transferRow(1L, AppTransferStatus.Downloading))
        val more = TransfersPaging.Available(TransferCursor("next"))
        val pagingFailed = TransfersPaging.Failed(
            TransferCursor("next"),
            PutioFailure.Unexpected(IllegalStateException("paging")),
        )
        val polling = TransfersRefresh.Polling(TransfersRequestId(9L))
        var current by mutableStateOf(transfersTestState(TransfersContent.Empty))
        setMutableScreen({ current }, onEvent = {})
        fun given(next: TransfersState, assertions: () -> Unit) {
            compose.runOnIdle { current = next }
            assertions()
        }
        val card = { compose.onNodeWithTag(mobileTransferCardTag(TransferId(1L))) }

        given(transfersTestState(TransfersContent.Ready(seeding, more), refresh = polling)) {
            card().assertIsEnabled()
            compose.onNodeWithText("Load more").assertIsNotEnabled()
        }
        given(transfersTestState(TransfersContent.Ready(seeding, pagingFailed), refresh = polling)) {
            card().assertIsEnabled()
            compose.onNodeWithText("Try again").assertIsNotEnabled()
        }
        given(
            transfersTestState(TransfersContent.Failed(PutioFailure.Unexpected(IllegalStateException("load"))))
                .withRunningLinkAdd(),
        ) {
            compose.onNodeWithText("Try again").assertIsNotEnabled()
        }
        given(transfersTestState(TransfersContent.Ready(downloading, more)).withRunningLinkAdd()) {
            compose.onNodeWithText("Load more").assertIsNotEnabled()
        }
        given(transfersTestState(TransfersContent.Ready(downloading, pagingFailed)).withRunningLinkAdd()) {
            compose.onNodeWithText("Try again").assertIsNotEnabled()
        }
        given(
            transfersTestState(
                TransfersContent.Ready(downloading, TransfersPaging.Complete),
                refresh = TransfersRefresh.Failed(PutioFailure.Unexpected(IllegalStateException("refresh"))),
            ).withRunningLinkAdd(),
        ) {
            compose.onNodeWithText("Try again").assertIsNotEnabled()
        }
    }

    @Test
    fun changingControlsDoesNotResetTheTransfersViewport() {
        val rows = (1L..30L).map { transferRow(it, AppTransferStatus.Waiting) }
        var current by mutableStateOf(transfersTestState(TransfersContent.Ready(rows, TransfersPaging.Complete)))
        setMutableScreen({ current }, onEvent = {})

        compose.onNodeWithTag(MOBILE_TRANSFERS_LIST_TAG).performScrollToIndex(20)
        compose.onNodeWithText("transfer-20").assertIsDisplayed()

        compose.runOnIdle {
            current = current.copyForTest(
                mutation = TransferMutation.Running(TransferAction.Cancel(rows.first().id), TransfersRequestId(9L)),
            )
        }

        compose.onNodeWithText("transfer-20").assertIsDisplayed()
    }

    private fun setScreen(state: TransfersState, onEvent: (TransfersEvent) -> Unit) {
        compose.setContent { PutioTheme { MobileTransfersScreen(state, onEvent) } }
    }

    private fun setMutableScreen(state: () -> TransfersState, onEvent: (TransfersEvent) -> Unit) {
        compose.setContent { PutioTheme { MobileTransfersScreen(state(), onEvent) } }
    }
}

// Longer than SnackbarDuration.Short.
private const val SNACKBAR_TIMEOUT_MS = 10_000L
