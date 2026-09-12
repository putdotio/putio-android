package io.putdotio.android.tv.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.history.HistoryClearing
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryRequestId
import io.putdotio.android.history.HistoryState
import io.putdotio.android.history.HistoryTransferId
import io.putdotio.sdk.errors.PutioConfigurationException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
class TvHistoryScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val log = mutableListOf<HistoryEvent>()
    private val clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun eventsAreGroupedByRelativeDateAndCenterOpensAFile() {
        show(HistoryState(HistoryContent.Ready(items(), HistoryPaging.Complete)))

        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Last week").assertIsDisplayed()
        compose.onNode(hasText("2 hours ago · Completed transfer")).assertIsDisplayed()
        compose.onNode(hasText("2 days ago · Shared file")).assertIsDisplayed()
        compose.onNode(hasText("· Activity", substring = true)).assertIsDisplayed()

        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("resume-video.mp4").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Open sintel.mp4").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.OpenFile(HistoryFileId(3))), log)
    }

    @Test
    fun upFromTheFirstRowReachesClearAndCenterAsksForConfirmation() {
        show(HistoryState(HistoryContent.Ready(items(), HistoryPaging.Complete)))

        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNode(hasText("Clear") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused()
        assertEquals(listOf<HistoryEvent>(HistoryEvent.RequestClear), log)
    }

    @Test
    fun theClearDialogFocusesCancelAndConfirmsFromTheStackedButton() {
        var state by mutableStateOf(
            HistoryState(HistoryContent.Ready(items(), HistoryPaging.Complete), HistoryClearing.AwaitingConfirmation),
        )
        show { state }

        compose.onNodeWithText("Clear history?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Clear history").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.ConfirmClear), log)

        compose.runOnIdle {
            state = HistoryState(HistoryContent.Loading(HistoryRequestId(2)), HistoryClearing.Idle)
        }
        compose.onNodeWithText("Loading history").assertIsDisplayed()
        compose.onNode(hasText("Clear") and hasClickAction()).assertIsFocused()

        compose.runOnIdle { state = HistoryState(HistoryContent.Empty) }
        compose.onNodeWithText("No activity yet.").assertIsDisplayed()
        compose.onNode(hasText("Clear") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun aFailedClearExplainsItselfAndOkDismisses() {
        show(
            HistoryState(
                HistoryContent.Ready(items(), HistoryPaging.Complete),
                HistoryClearing.Failed(FilesFailure.NetworkUnavailable(PutioConfigurationException("offline"))),
            ),
        )

        compose.onNodeWithText("Couldn’t clear history").assertIsDisplayed()
        compose.onNodeWithText("Check the network and try again.").assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.DismissClear), log)
    }

    @Test
    fun aFailedListFocusesTryAgainAndRowsTakeOverWhenItLoads() {
        var state by mutableStateOf(
            HistoryState(HistoryContent.Failed(FilesFailure.NetworkUnavailable(PutioConfigurationException("offline")))),
        )
        show { state }

        compose.onNodeWithText("Couldn’t load history").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.Retry), log)

        compose.runOnIdle { state = HistoryState(HistoryContent.Ready(items(), HistoryPaging.Complete)) }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused()
    }

    @Test
    fun disabledExplainsTheAccountSettingAndKeepsClearAsTheEntryPoint() {
        show(HistoryState(HistoryContent.Disabled))

        compose.onNodeWithText("History is off").assertIsDisplayed()
        compose.onNode(hasText("Clear") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun pagingLoadsMoreAndHandsFocusToTheLastRowWhenThePagesEnd() {
        var state by mutableStateOf(
            HistoryState(HistoryContent.Ready(items(), HistoryPaging.Available(HistoryEventId(3)))),
        )
        show { state }

        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.LoadNextPage), log)

        compose.runOnIdle {
            state = HistoryState(
                HistoryContent.Ready(items(), HistoryPaging.Loading(HistoryEventId(3), HistoryRequestId(2))),
            )
        }
        compose.onNodeWithText("Loading more history").assertIsFocused()

        compose.runOnIdle {
            state = HistoryState(
                HistoryContent.Ready(
                    items(),
                    HistoryPaging.Failed(HistoryEventId(3), FilesFailure.NetworkUnavailable(PutioConfigurationException("x"))),
                ),
            )
        }
        compose.onNodeWithText("Couldn’t load more history.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.LoadNextPage, HistoryEvent.Retry), log)

        compose.runOnIdle {
            state = HistoryState(HistoryContent.Ready(items() + item(4, "older.mkv", 20), HistoryPaging.Complete))
        }
        compose.onNodeWithContentDescription("Open older.mkv").assertIsFocused()
    }

    @Test
    fun aFailedOpenNamesItsCause() {
        show(
            HistoryState(HistoryContent.Ready(items(), HistoryPaging.Complete)),
            notice = FilesFailure.NetworkUnavailable(PutioConfigurationException("offline")),
        )

        compose.onNodeWithText("Couldn’t open this file. Check the network and try again.").assertIsDisplayed()
    }

    @Test
    fun aBlockedOpenIsExplainedAboveTheList() {
        show(HistoryState(HistoryContent.Ready(items(), HistoryPaging.Complete)), notice = FilesFailure.NavigationBlocked)

        compose.onNodeWithText("This item can’t be opened right now. Check Files, then try again.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused()
    }

    private fun show(state: HistoryState, notice: FilesFailure? = null) = show(notice) { state }

    private fun show(notice: FilesFailure? = null, state: () -> HistoryState) {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvHistoryScreen(
                    state = state(),
                    onEvent = { log += it; true },
                    notice = notice,
                    clock = clock,
                )
            }
        }
        compose.onNodeWithText("History").assertIsDisplayed()
    }

    private fun items() = listOf(
        HistoryItem(
            HistoryEventId(1),
            "2026-09-12T10:00:00",
            HistoryEventKind.Transfer(HistoryTransferId(9), HistoryFileId(1), "Big Buck Bunny"),
        ),
        HistoryItem(HistoryEventId(2), "2026-09-11T10:00:00", HistoryEventKind.Other("upload", "resume-video.mp4")),
        item(3, "sintel.mp4", 2),
    )

    private fun item(id: Long, name: String, daysAgo: Long) = HistoryItem(
        HistoryEventId(id),
        clock.instant().minusSeconds(daysAgo * 24 * 60 * 60).toString().removeSuffix("Z"),
        HistoryEventKind.File(HistoryFileId(id), name),
    )
}
