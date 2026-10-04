package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.downloads.DownloadArtifact
import io.putdotio.android.downloads.DownloadEntry
import io.putdotio.android.downloads.DownloadNotificationAccess
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.DownloadsEvent
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_CONCURRENCY_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_NOTIFICATIONS_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_SELECTION_DELETE_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_SELECT_TAG
import io.putdotio.android.downloads.MobileDownloadsScreen
import io.putdotio.android.downloads.withEntries
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
    fun theQueueListsRowsInStartOrderWithTheirPlaceAndTheLimit() {
        val state = DownloadsState(concurrency = 2).withEntries(listOf(
            row(10L, "Running HLS", DownloadStatus.Downloading(4_096L, null), queuedAt = 1L),
            row(11L, "Running original", DownloadStatus.Downloading(512L, 40f), queuedAt = 2L),
            row(13L, "Second in line", DownloadStatus.Queued, queuedAt = 4L),
            row(12L, "First in line", DownloadStatus.Queued, queuedAt = 3L),
            row(14L, "Lost", DownloadStatus.Missing, queuedAt = 5L),
        ))
        val events = mutableListOf<DownloadsEvent>()
        compose.setContent {
            PutioTheme {
                MobileDownloadsScreen(state, { events += it; true }, onPlay = {}, notifications = NotificationsOn)
            }
        }

        compose.onNodeWithText("2 at a time").assertIsDisplayed()
        compose.onNodeWithText("Downloading: 2 · Waiting: 2").assertIsDisplayed()
        compose.onNodeWithText("Downloading · 40%").assertIsDisplayed()
        // HLS before its playlists are read has bytes but no denominator.
        compose.onNodeWithText("Downloading · 4.1 kB").assertIsDisplayed()
        compose.onNodeWithText("Queued · #1 in line").assertIsDisplayed()
        compose.onNodeWithText("Queued · #2 in line").assertIsDisplayed()
        val names = compose.onAllNodesWithText("in line", substring = true).fetchSemanticsNodes()
            .map { it.boundsInRoot.top }
        assertTrue(names.zipWithNext().all { (first, second) -> first < second })
        compose.onNodeWithText("No longer on this device. Download again to play it offline.").assertIsDisplayed()

        compose.onNodeWithTag(MOBILE_DOWNLOADS_CONCURRENCY_TAG).performClick()
        compose.onNodeWithText("4 at a time").performClick()
        assertTrue(DownloadsEvent.SetConcurrency(4) in events)
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
    fun longPressSelectsAndDeleteAsksAboutExactlyTheSelectedLocalCopies() {
        val state = DownloadsState().withEntries(listOf(
            row(10L, "Sintel.mkv", DownloadStatus.Completed(1L)),
            row(11L, "Tears.mkv", DownloadStatus.Completed(1L)),
            row(12L, "Leaving.mkv", DownloadStatus.Completed(1L)),
        ))
        val events = mutableListOf<DownloadsEvent>()
        compose.setContent {
            PutioTheme {
                MobileDownloadsScreen(state, { events += it; true }, onPlay = {}, notifications = NotificationsOn)
            }
        }

        compose.onNodeWithText("Sintel.mkv").performTouchInput { longClick() }
        compose.onNodeWithText("Tears.mkv").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_DELETE_TAG).performClick()

        assertEquals(
            DownloadsEvent.RequestRemoval(setOf(FilesItemId(10L), FilesItemId(11L))),
            events.last(),
        )
    }

    @Test
    fun selectStartsEmptyAndDeleteWaitsForARow() {
        val state = DownloadsState().withEntries(listOf(
            row(10L, "Sintel.mkv", DownloadStatus.Completed(1L)),
            row(11L, "Tears.mkv", DownloadStatus.Completed(1L)),
        ))
        val events = mutableListOf<DownloadsEvent>()
        compose.setContent {
            PutioTheme {
                MobileDownloadsScreen(state, { events += it; true }, onPlay = {}, notifications = NotificationsOn)
            }
        }

        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECT_TAG).performClick()
        compose.onNodeWithText("0 selected").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_DELETE_TAG).assertIsNotEnabled()
        compose.onNodeWithText("Tears.mkv").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_DELETE_TAG).performClick()
        assertEquals(DownloadsEvent.RequestRemoval(setOf(FilesItemId(11L))), events.last())

        compose.onNodeWithContentDescription("Cancel selection").performClick()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECT_TAG).assertIsDisplayed()
    }

    private fun row(fileId: Long, name: String, status: DownloadStatus, queuedAt: Long = fileId) = DownloadEntry(
        FilesItemId(fileId), name, PutioFileType.VIDEO, DownloadArtifact.HLS, status,
        createdAt = fileId, queuedAt = queuedAt,
    )

    private companion object {
        val NotificationsOn = DownloadNotificationAccess(enabled = true) {}
    }
}
