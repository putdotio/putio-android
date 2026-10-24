package io.putdotio.android

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.MOBILE_ACCOUNT_LIST_TAG
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.MOBILE_FILES_LIST_TAG
import io.putdotio.android.search.MOBILE_SEARCH_FIELD_TAG
import io.putdotio.android.sharing.MOBILE_PUBLIC_LINKS_ROUTE
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.transfersState
import io.putdotio.android.trash.MOBILE_MANAGE_TRASH_TAG
import io.putdotio.android.trash.MOBILE_TRASH_ROUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** DESIGN.md Layout: five tabs, each with its own back stack; Back pops the tab, then returns to Files. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-port")
class MobileTabNavigationTest : NavigationShellFixture(1f) {
    @Test
    fun theBarListsTheFiveTabsInOrder() {
        mount()
        val lefts = listOf("Files", "History", "Transfers", "Downloads", "Account").map {
            tab(it).fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals("Tabs must sit left to right in contract order", lefts.sorted(), lefts)
    }

    @Test
    fun eachTabKeepsItsBackStackAndBackPopsItThenReturnsToFilesThenLeaves() {
        mount()
        tab("Account").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_MANAGE_TRASH_TAG))
        compose.onNodeWithTag(MOBILE_MANAGE_TRASH_TAG).performClick()
        compose.onNodeWithText("Trash").assertExists()

        tab("Transfers").performClick()
        compose.onNodeWithTag(MOBILE_TAB_TITLE_TAG).assertTextEquals("Transfers")
        tab("Account").performClick()
        // Account comes back on Trash, where it was left.
        tab("Account").assertIsSelected()
        compose.onNode(hasText("Trash") and !hasAnyAncestor(hasTestTag(MOBILE_ACCOUNT_LIST_TAG))).assertExists()

        back()
        compose.onNodeWithTag(MOBILE_TAB_TITLE_TAG).assertTextEquals("Account")
        back()
        tab("Files").assertIsSelected()
        compose.onNodeWithTag(MOBILE_FILES_LIST_TAG).assertExists()
        compose.runOnIdle {
            val leaves = !backOwner.onBackPressedDispatcher.hasEnabledCallbacks()
            assertTrue("Back at the Files root leaves the app", leaves)
        }
    }

    @Test
    fun searchOpensOverFilesStaysOpenAcrossTabsAndBackClosesIt() {
        mount(nested = true)
        compose.onNodeWithTag(MOBILE_FILES_SEARCH_TAG).performClick()
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertExists()
        tab("Files").assertIsSelected()

        tab("History").performClick()
        tab("Files").performClick()
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertExists()

        back()
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertDoesNotExist()
        compose.onNodeWithText("Shows").assertExists()
    }

    @Test
    fun aCancelledBackGestureKeepsTheFolderAndACommittedOneLeavesIt() {
        val events = mutableListOf<FilesBrowserEvent>()
        mount(nested = true, onFilesEvent = { events += it; true })
        // The opened folder is still loading; its skeleton stands for the folder's content.
        val list = compose.onNodeWithContentDescription("Loading files")
        val resting = list.fetchSemanticsNode().boundsInRoot.width
        val dispatcher = backOwner.onBackPressedDispatcher
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        // Mid-gesture the folder shrinks toward the edge it is dragged from.
        assertTrue(list.fetchSemanticsNode().boundsInRoot.width < resting * 0.97f)
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(resting, list.fetchSemanticsNode().boundsInRoot.width, 0.5f)
        gesture(commit = false)
        compose.runOnIdle { assertTrue(events.none { it == FilesBrowserEvent.NavigateBack }) }
        gesture(commit = true)
        compose.runOnIdle { assertEquals(1, events.count { it == FilesBrowserEvent.NavigateBack }) }
    }

    @Test
    fun aCancelledBackGestureKeepsTheSubpageAndACommittedOnePopsIt() {
        mount()
        tab("Account").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_MANAGE_TRASH_TAG))
        compose.onNodeWithTag(MOBILE_MANAGE_TRASH_TAG).performClick()
        gesture(commit = false)
        compose.onNodeWithTag(MOBILE_TAB_TITLE_TAG).assertDoesNotExist()
        gesture(commit = true)
        compose.onNodeWithTag(MOBILE_TAB_TITLE_TAG).assertTextEquals("Account")
    }

    private fun gesture(commit: Boolean) {
        val dispatcher = backOwner.onBackPressedDispatcher
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
            if (commit) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG)))

    private fun back() {
        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileMotionTest {
    @Test
    fun tabsFadeThroughRoutesWithinATabMoveOnTheSharedAxisAndThePlayerTransformsItsRow() {
        assertEquals(MobileTransition.FadeThrough, transition("files", "transfers"))
        assertEquals(MobileTransition.FadeThrough, transition(MOBILE_TRASH_ROUTE, "files", pop = true))
        assertEquals(MobileTransition.Push, transition("account", MOBILE_TRASH_ROUTE))
        assertEquals(MobileTransition.Pop, transition(MOBILE_PUBLIC_LINKS_ROUTE, "account", pop = true))
        assertEquals(MobileTransition.Container, transition("files", MOBILE_PLAYBACK_ROUTE))
        assertEquals(MobileTransition.Container, transition(MOBILE_PLAYBACK_ROUTE, "downloads", pop = true))
    }

    private fun transition(from: String, to: String, pop: Boolean = false) =
        mobileTransition(from, to, pop, reducedMotion = false)

    @Test
    fun reducedMotionTurnsEveryTransitionIntoAFade() {
        listOf("transfers", MOBILE_TRASH_ROUTE, MOBILE_PLAYBACK_ROUTE).forEach { to ->
            assertEquals(MobileTransition.Fade, mobileTransition("account", to, pop = false, reducedMotion = true))
        }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileTabSummaryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val mono = SpanStyle(fontFamily = FontFamily.Monospace)

    @Test
    fun transfersCountRunningAndFailedTransfersAndAddTheirSpeed() {
        val state = transfersState(
            TransfersContent.Ready(
                listOf(
                    transfer(1, AppTransferStatus.Downloading, speed = 1_000_000.0),
                    transfer(2, AppTransferStatus.Queued, speed = null),
                    transfer(3, AppTransferStatus.Seeding, speed = 9_000_000.0),
                    transfer(4, AppTransferStatus.Failed, speed = null),
                    transfer(5, AppTransferStatus.Completed, speed = null),
                ),
                TransfersPaging.Complete,
            ),
        )
        val summary = summaryOf(transfersSummaryParts(context, state), mono)!!
        // Queued transfers wait; only the downloading one counts as active.
        assertEquals("1 active · 1 failed · 1.0 MB/s", summary.text)
        val monoRuns = summary.spanStyles.filter { it.item == mono }.map { summary.text.substring(it.start, it.end) }
        assertEquals(listOf("1", "1", "1.0 MB/s"), monoRuns)
    }

    @Test
    fun idleTransfersHaveNoSummary() {
        val state = transfersState(
            TransfersContent.Ready(listOf(transfer(1, AppTransferStatus.Completed, null)), TransfersPaging.Complete),
        )
        assertNull(summaryOf(transfersSummaryParts(context, state), mono))
        assertNull(summaryOf(transfersSummaryParts(context, transfersState(TransfersContent.Empty)), mono))
    }

    @Test
    fun downloadsGiveTheSpaceTheyUseAndWhatIsFree() {
        val summary = summaryOf(downloadsSummaryParts(context, 4_600_000_000L, 21_000_000_000L), mono)!!
        assertEquals("4.6 GB on this device · 21 GB free", summary.text)
        assertTrue(summary.spanStyles.all { it.item == mono })
        val unknownFree = summaryOf(downloadsSummaryParts(context, 0L, null), mono)!!
        assertEquals("0 B on this device", unknownFree.text)
    }

    private fun transfer(id: Long, status: AppTransferStatus, speed: Double?) = TransferItem(
        id = TransferId(id),
        name = "transfer-$id",
        status = status,
        fileId = null,
        sizeBytes = null,
        percentDone = null,
        downloadSpeedBytesPerSecond = speed,
        uploadSpeedBytesPerSecond = null,
        estimatedSecondsRemaining = null,
        availability = null,
        errorMessage = null,
        createdAt = "2026-10-24T10:00:00",
        userFileExists = null,
    )
}
