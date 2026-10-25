package io.putdotio.android

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.downloads.DownloadArtifact
import io.putdotio.android.downloads.DownloadEntry
import io.putdotio.android.downloads.DownloadFailureReason
import io.putdotio.android.downloads.DownloadNotificationAccess
import io.putdotio.android.downloads.DownloadPauseReason
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.DownloadsEvent
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_CONCURRENCY_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_NOTIFICATIONS_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_OVERFLOW_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_SELECTION_DELETE_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_SELECTION_PAUSE_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_SELECTION_RESUME_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_SELECT_TAG
import io.putdotio.android.downloads.MobileDownloadsScreen
import io.putdotio.android.downloads.MobileDownloadsTopBar
import io.putdotio.android.downloads.rememberDownloadsSelection
import io.putdotio.android.downloads.withEntries
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// Tall enough that the whole queue is composed at once.
@Config(sdk = [35], qualifiers = "en-rUS-w400dp-h1600dp")
class MobileDownloadsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun progressIsWatchedOnlyWhileTheScreenIsShown() {
        val events = mutableListOf<DownloadsEvent>()
        var shown by mutableStateOf(true)
        compose.setContent {
            PutioTheme {
                if (shown) {
                    MobileDownloadsScreen(DownloadsState(), onEvent = { events += it; true }, onPlay = {})
                }
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(DownloadsEvent.Shown), events)

        shown = false
        compose.waitForIdle()
        assertEquals(listOf(DownloadsEvent.Shown, DownloadsEvent.Hidden), events)
    }

    @Test
    fun downloadingListsRowsInStartOrderWithProgressPlaceAndTheLimit() {
        val running = FilesItemId(11L)
        val state = DownloadsState(concurrency = 2, speeds = mapOf(running to 12_000_000L)).withEntries(listOf(
            row(10L, "Running HLS", DownloadStatus.Downloading(4_096L, null), queuedAt = 1L),
            row(11L, "Running original", DownloadStatus.Downloading(1_400_000L, 42f, 3_300_000L), queuedAt = 2L),
            row(13L, "Second in line", DownloadStatus.Queued, queuedAt = 4L),
            row(12L, "First in line", DownloadStatus.Queued, queuedAt = 3L),
            row(14L, "Lost", DownloadStatus.Missing, queuedAt = 5L),
        ))
        val events = mutableListOf<DownloadsEvent>()
        compose.setContent { PutioTheme { DownloadsTab(state, { events += it; true }) } }

        compose.onNodeWithText("Downloading").assertIsDisplayed()
        // An original's size is known; HLS before its playlists are read has bytes alone.
        compose.onNodeWithText("1.4 MB of 3.3 MB · 12 MB/s").assertIsDisplayed()
        compose.onNodeWithText("4.1 kB").assertIsDisplayed()
        compose.onNodeWithText("Queued · #1 in line").assertIsDisplayed()
        compose.onNodeWithText("Queued · #2 in line").assertIsDisplayed()
        val places = compose.onAllNodesWithText("in line", substring = true).fetchSemanticsNodes()
            .map { it.boundsInRoot.top }
        assertTrue(places.zipWithNext().all { (first, second) -> first < second })
        compose.onNodeWithText("Needs attention").assertIsDisplayed()
        compose.onNodeWithText("No longer on this device").assertIsDisplayed()

        // A known percentage fills the ring; an HLS rendition before its playlists spins.
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.42f, 0f..1f)), useUnmergedTree = true)
            .assertExists()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true)
            .assertExists()

        compose.onNodeWithTag(MOBILE_DOWNLOADS_OVERFLOW_TAG).performClick()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_CONCURRENCY_TAG).assertTextContains("2").performClick()
        compose.onNodeWithText("2 at a time").assertIsSelected()
        compose.onNodeWithText("4 at a time").assertIsNotSelected().performClick()
        assertTrue(DownloadsEvent.SetConcurrency(4) in events)
    }

    @Test
    fun eachRingActsOnItsOwnRow() {
        val state = DownloadsState().withEntries(listOf(
            row(10L, "Running.mkv", DownloadStatus.Downloading(10L, 1f), queuedAt = 1L),
            row(11L, "Paused.mkv", DownloadStatus.Paused(DownloadPauseReason.USER, 10L, 26f), queuedAt = 2L),
            row(12L, "Waiting.mkv", DownloadStatus.Queued, queuedAt = 3L),
        ))
        val events = mutableListOf<DownloadsEvent>()
        compose.setContent { PutioTheme { DownloadsTab(state, { events += it; true }) } }

        compose.onNodeWithText("Paused · 10 B · 26%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause download").performClick()
        compose.onNodeWithContentDescription("Resume download").performClick()
        compose.onNodeWithContentDescription("Cancel download").performClick()

        assertEquals(
            listOf(
                DownloadsEvent.Pause(setOf(FilesItemId(10L))),
                DownloadsEvent.Resume(setOf(FilesItemId(11L))),
                DownloadsEvent.Cancel(FilesItemId(12L)),
            ),
            events.filterNot { it is DownloadsEvent.Shown },
        )
    }

    @Test
    fun aWaitForTheNetworkOffersPauseNotResume() {
        val state = DownloadsState().withEntries(listOf(
            row(10L, "Tears.mkv", DownloadStatus.Paused(DownloadPauseReason.NETWORK, 310L, null, 1_200L)),
        ))
        val events = mutableListOf<DownloadsEvent>()
        compose.setContent { PutioTheme { DownloadsTab(state, { events += it; true }) } }

        compose.onNodeWithText("Waiting for network · 310 B of 1.2 kB").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithContentDescription("Resume download").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Pause download").performClick()
        assertEquals(DownloadsEvent.Pause(setOf(FilesItemId(10L))), events.last())
    }

    @Test
    fun stuckRowsSayWhyAndOfferRetryOrDownloadAgain() {
        val state = DownloadsState().withEntries(listOf(
            row(10L, "Broken.mkv", DownloadStatus.Failed(DownloadFailureReason.STORAGE, 1L)),
            row(11L, "Gone.mkv", DownloadStatus.Missing),
        ))
        val events = mutableListOf<DownloadsEvent>()
        compose.setContent { PutioTheme { DownloadsTab(state, { events += it; true }) } }

        compose.onNodeWithText("Failed · Not enough storage").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry download").performClick()
        compose.onNodeWithContentDescription("Download again").performClick()
        assertEquals(
            listOf(DownloadsEvent.Retry(FilesItemId(10L)), DownloadsEvent.Retry(FilesItemId(11L))),
            events.filterIsInstance<DownloadsEvent.Retry>(),
        )
    }

    @Test
    fun notificationsOffShowsAWayToTurnThemOnAndOnShowsNothing() {
        var turnedOn = 0
        var enabled by mutableStateOf(false)
        compose.setContent {
            PutioTheme {
                MobileDownloadsScreen(
                    DownloadsState(),
                    { true },
                    onPlay = {},
                    notifications = DownloadNotificationAccess(enabled) { turnedOn += 1 },
                )
            }
        }
        compose.onNodeWithText("Download notifications are off").assertIsDisplayed()
        compose.onNodeWithText("Turn on").performClick()
        assertEquals(1, turnedOn)

        enabled = true
        compose.onNodeWithTag(MOBILE_DOWNLOADS_NOTIFICATIONS_TAG).assertDoesNotExist()
    }

    @Test
    fun longPressSelectsAndRemoveAsksAboutExactlyTheSelectedLocalCopies() {
        val state = DownloadsState().withEntries(listOf(
            row(10L, "Sintel.mkv", DownloadStatus.Completed(1L)),
            row(11L, "Tears.mkv", DownloadStatus.Completed(1L)),
            row(12L, "Leaving.mkv", DownloadStatus.Completed(1L)),
        ))
        val events = mutableListOf<DownloadsEvent>()
        compose.setContent { PutioTheme { DownloadsTab(state, { events += it; true }) } }

        compose.onNodeWithText("Sintel.mkv").performTouchInput { longClick() }
        compose.onNodeWithText("Tears.mkv").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        // The selection bar replaces the tab title.
        compose.onNodeWithTag(MOBILE_DOWNLOADS_OVERFLOW_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_DELETE_TAG).performClick()

        assertEquals(
            DownloadsEvent.RequestRemoval(setOf(FilesItemId(10L), FilesItemId(11L))),
            events.last(),
        )
    }

    @Test
    fun selectStartsEmptyAndRemoveWaitsForARow() {
        val state = DownloadsState().withEntries(listOf(
            row(10L, "Sintel.mkv", DownloadStatus.Completed(1L)),
            row(11L, "Tears.mkv", DownloadStatus.Completed(1L)),
        ))
        val events = mutableListOf<DownloadsEvent>()
        compose.setContent { PutioTheme { DownloadsTab(state, { events += it; true }) } }

        compose.onNodeWithTag(MOBILE_DOWNLOADS_OVERFLOW_TAG).performClick()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECT_TAG).performClick()
        compose.onNodeWithText("0 selected").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_DELETE_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Tears.mkv").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_DELETE_TAG).performClick()
        assertEquals(DownloadsEvent.RequestRemoval(setOf(FilesItemId(11L))), events.last())

        compose.onNodeWithContentDescription("Select all").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel selection").performClick()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_OVERFLOW_TAG).assertIsDisplayed()
    }

    @Test
    fun theToolbarOffersPauseAndResumeOnlyForTheRowsThatAllowThem() {
        val state = DownloadsState().withEntries(listOf(
            row(10L, "Running.mkv", DownloadStatus.Downloading(10L, 1f), queuedAt = 1L),
            row(11L, "Paused.mkv", DownloadStatus.Paused(DownloadPauseReason.USER, 10L), queuedAt = 2L),
            row(12L, "Done.mkv", DownloadStatus.Completed(1L), queuedAt = 3L),
        ))
        val events = mutableListOf<DownloadsEvent>()
        compose.setContent { PutioTheme { DownloadsTab(state, { events += it; true }) } }

        compose.onNodeWithText("Done.mkv").performTouchInput { longClick() }
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_PAUSE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_RESUME_TAG).assertDoesNotExist()

        compose.onNodeWithText("Running.mkv").performClick()
        compose.onNodeWithText("Paused.mkv").performClick()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_PAUSE_TAG).performClick()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_RESUME_TAG).performClick()
        assertEquals(
            listOf(DownloadsEvent.Pause(setOf(FilesItemId(10L))), DownloadsEvent.Resume(setOf(FilesItemId(11L)))),
            events.takeLast(2),
        )
    }

    /** The tab as the shell draws it: the top bar over the list, sharing one selection. */
    @Composable
    private fun DownloadsTab(state: DownloadsState, onEvent: (DownloadsEvent) -> Boolean) {
        val selection = rememberDownloadsSelection()
        Column {
            MobileDownloadsTopBar("Downloads", null, state, selection, onEvent)
            MobileDownloadsScreen(state, onEvent, onPlay = {}, notifications = NotificationsOn, selection = selection)
        }
    }

    private fun row(fileId: Long, name: String, status: DownloadStatus, queuedAt: Long = fileId) = DownloadEntry(
        FilesItemId(fileId), name, PutioFileType.VIDEO, DownloadArtifact.HLS, status,
        createdAt = fileId, queuedAt = queuedAt,
    )

    private companion object {
        val NotificationsOn = DownloadNotificationAccess(enabled = true) {}
    }
}
