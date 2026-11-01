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
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.FilesFailure
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.history.HistoryClearing
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryNoticeType
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryRequestId
import io.putdotio.android.history.HistoryState
import io.putdotio.android.history.HistoryTransferId
import io.putdotio.android.history.historyState
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
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
    fun eventsGroupUnderTheirDayWithThePhonesRowsAndCenterOpensTheFolder() {
        show(historyState(HistoryContent.Ready(items(), HistoryPaging.Complete)))

        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        compose.onNodeWithText("Sep 10").assertIsDisplayed()
        compose.onAllNodesWithText("Transfer completed")[0].assertIsDisplayed()
        compose.onAllNodesWithText("Uploaded")[0].assertIsDisplayed()

        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("clip.mp4").assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Open sintel.mp4").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.OpenFile(HistoryFileId(3))), log)
    }

    @Test
    fun theDetailShowsTheEventsFileAndOpenFolderOpensIt() {
        show(
            files = TvHistoryFiles(resolver(FilesItemId(1) to file(1, "Big Buck Bunny", FilesFolder.Root.id))),
        ) { historyState(HistoryContent.Ready(items(), HistoryPaging.Complete)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Your Files").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Size") and hasText("3.1 GB")).assertIsDisplayed()
        compose.onNodeWithText("Transfer completed · Today, 05:22").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Open folder") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionLeft)
        }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused()
        assertEquals(listOf<HistoryEvent>(HistoryEvent.OpenFile(HistoryFileId(1))), log)
    }

    @Test
    fun anEventWithoutAFileHasNoOpenFolder() {
        show(historyState(HistoryContent.Ready(items(), HistoryPaging.Complete)))

        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("clip.mp4").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription("clip.mp4").assertIsFocused()
        compose.onNode(hasText("Open folder") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun upFromTheFirstRowReachesClearHistoryAndCenterAsksForConfirmation() {
        show(historyState(HistoryContent.Ready(items(), HistoryPaging.Complete)))

        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNode(hasText("Clear history") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused()
        assertEquals(listOf<HistoryEvent>(HistoryEvent.RequestClear), log)
    }

    @Test
    fun theClearConfirmFocusesTheRedButtonAndCancelSitsBesideIt() {
        var state by mutableStateOf(
            historyState(HistoryContent.Ready(items(), HistoryPaging.Complete), HistoryClearing.AwaitingConfirmation),
        )
        show { state }

        compose.onNodeWithText("Clear history?").assertIsDisplayed()
        compose.onNode(hasText("Clear history") and isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Cancel") and isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Clear history") and isFocused()).performKeyInput { pressCenter() }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.ConfirmClear), log)

        compose.runOnIdle { state = historyState(HistoryContent.Loading(HistoryRequestId(2))) }
        compose.onNodeWithContentDescription("Loading").assertIsFocused()

        compose.runOnIdle { state = historyState(HistoryContent.Empty) }
        compose.onNodeWithText("No activity yet.").assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun aFailedClearExplainsItselfAndOkDismisses() {
        show(
            historyState(
                HistoryContent.Ready(items(), HistoryPaging.Complete),
                HistoryClearing.Failed(PutioFailure.NetworkUnavailable(PutioConfigurationException("offline"))),
            ),
        )

        compose.onNodeWithText("Couldn’t clear history").assertIsDisplayed()
        compose.onNodeWithText("Check the network and try again.").assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.DismissClear), log)
    }

    @Test
    fun anOfflineListFocusesTryNowAndRowsTakeOverWhenItLoads() {
        var state by mutableStateOf(
            historyState(
                HistoryContent.Failed(PutioFailure.NetworkUnavailable(PutioConfigurationException("offline"))),
            ),
        )
        show { state }

        compose.onNodeWithText("Can’t reach put.io.").assertIsDisplayed()
        compose.onNodeWithText("Try now").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.Retry), log)

        compose.runOnIdle { state = historyState(HistoryContent.Ready(items(), HistoryPaging.Complete)) }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused()
    }

    @Test
    fun loadingDrawsSkeletonsAndRowsTakeFocusWhenTheListLoads() {
        var state by mutableStateOf(historyState(HistoryContent.Loading(HistoryRequestId(1))))
        show { state }

        compose.onNodeWithContentDescription("Loading").assertIsFocused()

        compose.runOnIdle { state = historyState(HistoryContent.Ready(items(), HistoryPaging.Complete)) }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused()
    }

    @Test
    fun historyOffNamesTheSettingAndOpenAccountGoesThere() {
        var opened = 0
        show(onOpenAccount = { opened++ }) { historyState(HistoryContent.Disabled) }

        compose.onNodeWithText("History is off.").assertIsDisplayed()
        compose.onNodeWithText("Turn on Keep account history in Account to see what happens to your files.")
            .assertIsDisplayed()
        compose.onNode(hasText("Open Account") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(1, opened)
        compose.onNode(hasText("Clear history") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun pagingLoadsMoreAndHandsFocusToTheLastRowWhenThePagesEnd() {
        var state by mutableStateOf(
            historyState(HistoryContent.Ready(items(), HistoryPaging.Available(HistoryEventId(3)))),
        )
        show { state }

        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithText("Load more").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.LoadNextPage), log)

        compose.runOnIdle {
            state = historyState(
                HistoryContent.Ready(items(), HistoryPaging.Loading(HistoryEventId(3), HistoryRequestId(2))),
            )
        }
        compose.onNodeWithText("Loading more history").assertIsFocused()

        compose.runOnIdle {
            state = historyState(
                HistoryContent.Ready(
                    items(),
                    HistoryPaging.Failed(
                        HistoryEventId(3),
                        PutioFailure.NetworkUnavailable(PutioConfigurationException("x")),
                    ),
                ),
            )
        }
        compose.onNodeWithText("Couldn’t load more history.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.LoadNextPage, HistoryEvent.Retry), log)

        compose.runOnIdle {
            state = historyState(HistoryContent.Ready(items() + item(4, "older.mkv", 20), HistoryPaging.Complete))
        }
        compose.onNodeWithContentDescription("Open older.mkv").assertIsFocused()
    }

    @Test
    fun aBlockedOpenIsExplainedAboveTheList() {
        val notices = listOf<Pair<FilesFailure, String>>(
            PutioFailure.NetworkUnavailable(PutioConfigurationException("offline")) to
                "Couldn’t open this file. Check the network and try again.",
            FilesFailure.NavigationBlocked to "This item can’t be opened right now. Check Files, then try again.",
        )
        var notice by mutableStateOf<FilesFailure?>(null)
        show(notice = { notice }) { historyState(HistoryContent.Ready(items(), HistoryPaging.Complete)) }

        notices.forEach { (failure, message) ->
            compose.runOnIdle { notice = failure }
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused()
        }
    }

    private fun show(state: HistoryState) = show { state }

    private fun show(
        notice: () -> FilesFailure? = { null },
        files: TvHistoryFiles = TvHistoryFiles.None,
        onOpenAccount: () -> Unit = {},
        state: () -> HistoryState,
    ) {
        compose.setTvContent {
            TvHistoryScreen(
                state = state(),
                onEvent = { log += it; true },
                notice = notice(),
                clock = clock,
                files = files,
                onOpenAccount = onOpenAccount,
            )
        }
        compose.onNodeWithText("History").assertDoesNotExist()
    }

    private fun resolver(vararg known: Pair<FilesItemId, FilesItem>) = object : FilesItemResolver {
        override suspend fun resolveItem(itemId: FilesItemId) =
            known.toMap()[itemId]?.let { PutioResult.Success(it) }
                ?: PutioResult.Failure(PutioFailure.Unexpected(IllegalStateException("No item $itemId")))
    }

    private fun file(id: Long, name: String, parent: FilesItemId) = FilesItem(
        id = FilesItemId(id),
        parentId = parent,
        name = name,
        type = PutioFileType.VIDEO,
        sizeBytes = 3_100_000_000L,
        createdAt = "2026-09-12T10:00:00",
    )

    private fun items() = listOf(
        HistoryItem(
            HistoryEventId(1),
            "2026-09-12T05:22:00",
            HistoryEventKind.Transfer(HistoryTransferId(9), HistoryFileId(1), "Big Buck Bunny"),
        ),
        HistoryItem(
            HistoryEventId(2),
            "2026-09-11T10:00:00",
            HistoryEventKind.Notice(HistoryNoticeType.Upload, "clip.mp4"),
        ),
        item(3, "sintel.mp4", 2),
    )

    private fun item(id: Long, name: String, daysAgo: Long) = HistoryItem(
        HistoryEventId(id),
        clock.instant().minusSeconds(daysAgo * 24 * 60 * 60).toString().removeSuffix("Z"),
        HistoryEventKind.File(HistoryFileId(id), name),
    )
}
