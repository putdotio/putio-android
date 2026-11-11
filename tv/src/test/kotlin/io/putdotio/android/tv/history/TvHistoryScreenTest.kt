package io.putdotio.android.tv.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.StubFilesRepository
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
import io.putdotio.android.tv.files.TvFilesActionContext
import io.putdotio.android.tv.files.TvFilesActionHandlers
import io.putdotio.android.tv.files.TvFilesEdits
import io.putdotio.android.tv.files.TvItemChanges
import io.putdotio.android.tv.files.TvItemThumbnails
import io.putdotio.android.tv.files.tvActions
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.PutioFileType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    fun theDetailPlaysTheEventsVideoAndGoToFolderOpensIt() {
        val played = mutableListOf<FilesItemId>()
        show(
            files = TvHistoryFiles(resolver(FilesItemId(1) to file(1, "Big Buck Bunny", FilesFolder.Root.id))),
            itemActions = historyActions(onPlay = { played += it.id }),
        ) { historyState(HistoryContent.Ready(items(), HistoryPaging.Complete)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Your files").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Size") and hasText("3.1 GB")).assertIsDisplayed()
        compose.onNodeWithText("Transfer completed · Today, 05:22").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Play") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionDown)
        }
        compose.runOnIdle { assertEquals(listOf(FilesItemId(1)), played) }
        compose.onNode(hasText("Go to folder") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
            pressKey(Key.DirectionLeft)
        }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused()
        assertEquals(listOf<HistoryEvent>(HistoryEvent.OpenFile(HistoryFileId(1))), log)
    }

    @Test
    fun anUploadedFileOffersThePanelActions() {
        val played = mutableListOf<FilesItemId>()
        val upload = HistoryItem(
            HistoryEventId(1),
            "2026-09-12T05:22:00",
            HistoryEventKind.Notice(HistoryNoticeType.Upload, "clip.mp4", HistoryFileId(2)),
        )
        show(
            files = TvHistoryFiles(resolver(FilesItemId(2) to file(2, "clip.mp4", FilesFolder.Root.id))),
            itemActions = historyActions(onPlay = { played += it.id }),
        ) { historyState(HistoryContent.Ready(listOf(upload), HistoryPaging.Complete)) }

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Play") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Open clip.mp4").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Play") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        compose.runOnIdle { assertEquals(listOf(FilesItemId(2)), played) }
    }

    @Test
    fun anEventWhoseFileIsGoneShowsNoDeadActions() {
        show(
            files = TvHistoryFiles(goneResolver()),
            itemActions = historyActions(),
        ) { historyState(HistoryContent.Ready(items(), HistoryPaging.Complete)) }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No longer in your files").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused()
        compose.onNode(hasText("Play") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Go to folder") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Move to trash") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun moveToTrashFromTheHistoryPanelTrashesTheFileOnce() {
        val repository = TrashingRepository()
        val files = TvHistoryFiles(repository)
        val edits = TvFilesEdits(
            repository,
            CoroutineScope(Dispatchers.Unconfined),
            TvItemChanges(onRemoved = { item, _ -> files.forget(item.id) }),
        )
        show(
            files = files,
            itemActions = historyActions(edits = edits),
        ) { historyState(HistoryContent.Ready(items(), HistoryPaging.Complete)) }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Your files").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Play") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Move to trash") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
        }

        compose.waitUntil(5_000) { repository.deletes.isNotEmpty() }
        assertEquals(listOf(listOf(FilesItemId(1)) to FilesDeleteMode.TRASH), repository.deletes)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No longer in your files").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused()
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
    fun theClearConfirmFocusesCancelAndTheRedButtonSitsBesideIt() {
        var state by mutableStateOf(
            historyState(HistoryContent.Ready(items(), HistoryPaging.Complete), HistoryClearing.AwaitingConfirmation),
        )
        show { state }

        compose.onNodeWithText("Clear history?").assertIsDisplayed()
        compose.onNode(hasText("Cancel") and isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Clear history") and isFocused()).performKeyInput { pressCenter() }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.ConfirmClear), log)

        compose.runOnIdle { state = historyState(HistoryContent.Loading(HistoryRequestId(2))) }
        compose.onNodeWithContentDescription("Loading").assertIsFocused()

        compose.runOnIdle { state = historyState(HistoryContent.Empty) }
        compose.onNodeWithText("No activity yet.").assertIsDisplayed()
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
        compose.onNodeWithText("Check your internet connection.").assertIsDisplayed()
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

        compose.onNodeWithText("Can’t connect to put.io").assertIsDisplayed()
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
    fun theNextPageLoadsNearTheEndAndAFailedPageRetriesOnlyFromTryAgain() {
        var state by mutableStateOf(
            historyState(HistoryContent.Ready(items(), HistoryPaging.Available(HistoryEventId(3)))),
        )
        show { state }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.LoadNextPage), log)

        compose.onNodeWithContentDescription("Open Big Buck Bunny").assertIsFocused().performKeyInput {
            repeat(4) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithContentDescription("Open sintel.mp4").assertIsFocused()

        compose.runOnIdle {
            state = historyState(
                HistoryContent.Ready(items(), HistoryPaging.Loading(HistoryEventId(3), HistoryRequestId(2))),
            )
        }
        compose.onNodeWithContentDescription("Loading more history").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open sintel.mp4").assertIsFocused()

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
        compose.onNodeWithContentDescription("Open sintel.mp4").assertIsDisplayed()
        assertEquals(listOf<HistoryEvent>(HistoryEvent.LoadNextPage), log)
        compose.onNodeWithContentDescription("Open sintel.mp4").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.LoadNextPage, HistoryEvent.Retry), log)

        compose.runOnIdle {
            state = historyState(
                HistoryContent.Ready(items(), HistoryPaging.Loading(HistoryEventId(3), HistoryRequestId(3))),
            )
        }
        compose.onNodeWithContentDescription("Open sintel.mp4").assertIsFocused()

        compose.runOnIdle {
            state = historyState(HistoryContent.Ready(items() + item(4, "older.mkv", 20), HistoryPaging.Complete))
        }
        compose.onNodeWithContentDescription("Open sintel.mp4").assertIsFocused()
        compose.onNodeWithContentDescription("Open older.mkv").assertIsDisplayed()
    }

    @Test
    fun aBlockedOpenIsExplainedAboveTheList() {
        val notices = listOf<Pair<FilesFailure, String>>(
            PutioFailure.NetworkUnavailable(PutioConfigurationException("offline")) to
                "Couldn’t open this file. Check your internet connection.",
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

    private fun historyActions(
        onPlay: (FilesItem) -> Unit = {},
        edits: TvFilesEdits? = null,
    ) = TvHistoryItemActions(
        resumePlayback = true,
        thumbnails = TvItemThumbnails.None,
        onPlay = onPlay,
        actionsOf = { item ->
            item.tvActions(TvFilesActionContext(watchedToggleEnabled = false, trashEnabled = true, canDelete = true))
        },
        handlers = TvFilesActionHandlers(
            onPlay = { _, _ -> },
            onPlayWith = { _, _ -> },
            onOpenInVlc = {},
            onSetWatched = { _, _ -> },
            onSetPinned = { _, _ -> },
        ),
        editing = edits?.let { TvHistoryEditing(it, trashEnabled = true) },
    )

    private fun show(
        notice: () -> FilesFailure? = { null },
        files: TvHistoryFiles = TvHistoryFiles.None,
        onOpenAccount: () -> Unit = {},
        itemActions: TvHistoryItemActions? = null,
        state: () -> HistoryState,
    ) {
        compose.setTvContent {
            TvHistoryScreen(
                state = state(),
                onEvent = {
                    if (it != HistoryEvent.Refresh) log += it
                    true
                },
                notice = notice(),
                clock = clock,
                files = files,
                itemActions = itemActions,
                onOpenAccount = onOpenAccount,
            )
        }
        compose.onNodeWithText("History").assertDoesNotExist()
    }

    private fun goneResolver() = object : FilesItemResolver {
        override suspend fun resolveItem(itemId: FilesItemId) = PutioResult.Failure(notFound())
    }

    private fun notFound() = PutioFailure.ApiRejected(404, null, PutioConfigurationException("gone"), 404)

    private inner class TrashingRepository : StubFilesRepository(), FilesItemResolver {
        val deletes = mutableListOf<Pair<List<FilesItemId>, FilesDeleteMode>>()

        override suspend fun loadFolder(folderId: FilesItemId) = error("not used")

        override suspend fun delete(itemIds: List<FilesItemId>, mode: FilesDeleteMode) =
            PutioResult.Success(FileDeleteResult(cursor = null, skipped = 0, status = "OK"))
                .also { deletes += itemIds to mode }

        override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> =
            if (deletes.isEmpty()) {
                PutioResult.Success(file(itemId.value, "Big Buck Bunny", FilesFolder.Root.id))
            } else {
                PutioResult.Failure(notFound())
            }
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
