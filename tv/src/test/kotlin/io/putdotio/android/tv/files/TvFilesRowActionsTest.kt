package io.putdotio.android.tv.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.setTvContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesDeleteStatus
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesRequestId
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.putdotio.android.files.copyForTest
import io.putdotio.android.tv.SNACKBAR_MILLIS
import io.putdotio.android.tv.TvPlayStart
import io.putdotio.android.tv.TvTrashUndoOffer

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesRowActionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun theMenuKeyOpensTheActionsSheetAndMoveToTrashRunsWithoutAConfirmation() {
        val events = mutableListOf<FilesBrowserEvent>()
        val toggled = mutableListOf<Pair<Long, Boolean>>()
        val opened = mutableListOf<Long>()
        compose.setTvContent {
            TvFilesScreen(
                state = ready(
                    item(2, "clip.mp4", PutioFileType.VIDEO, playback = FilesPlaybackProgress(0.0, 120.0)),
                    item(3, "notes.txt", PutioFileType.TEXT),
                ),
                onEvent = { events += it; true },
                onPlayMedia = {},
                confirmedTrashEnabled = true,
                watchedToggleEnabled = true,
                onOpenInVlc = { opened += it.id.value },
                onSetWatched = { item, watched -> toggled += item.id.value to watched },
            )
        }

        compose.onNodeWithContentDescription("Play clip.mp4").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNode(hasText("Play") and isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Open in VLC").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Mark as watched").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf(2L to true), toggled)
        compose.onNodeWithContentDescription("Play clip.mp4").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNode(hasText("Play") and isFocused()).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithText("Move to trash").assertIsFocused().performKeyInput { pressCenter() }
        compose.onAllNodesWithText("Cancel").assertCountEquals(0)
        assertEquals(
            listOf<FilesBrowserEvent>(
                FilesBrowserEvent.Delete(FilesFolder.Root.id, FilesItemId(2), FilesDeleteMode.TRASH),
            ),
            events,
        )
        compose.onNodeWithContentDescription("Play clip.mp4").assertIsFocused()
        assertEquals(emptyList<Long>(), opened)
    }

    @Test
    fun theTilesResumeStartOverOrPickSubtitlesFirst() {
        val played = mutableListOf<Pair<Long, TvPlayStart>>()
        val subtitles = mutableListOf<Pair<Long, TvPlayStart>>()
        compose.setTvContent {
            TvFilesScreen(
                state = ready(
                    item(2, "clip.mp4", PutioFileType.VIDEO, playback = FilesPlaybackProgress(30.0, 120.0)),
                ),
                onEvent = { true },
                onPlayMedia = {},
                onPlay = { item, start -> played += item.id.value to start },
                onPickSubtitles = { item, start -> subtitles += item.id.value to start },
            )
        }

        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.Menu) }
        compose.onNode(hasText("Resume") and isFocused()).performKeyInput { pressCenter() }
        compose.onNodeWithContentDescription("Play clip.mp4").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNode(hasText("Resume") and isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNode(hasText("Start over") and isFocused()).performKeyInput { pressCenter() }
        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.Menu) }
        compose.onNode(hasText("Resume") and isFocused()).performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
        }
        compose.onNode(hasText("Subtitles") and isFocused()).performKeyInput { pressCenter() }
        compose.runOnIdle {
            assertEquals(listOf(2L to TvPlayStart.Resume, 2L to TvPlayStart.StartOver), played)
            assertEquals(listOf(2L to TvPlayStart.Resume), subtitles)
        }
    }

    @Test
    fun noSubtitlesTileWhenTheAccountHidesSubtitles() {
        compose.setTvContent {
            TvFilesScreen(
                state = ready(item(2, "clip.mp4", PutioFileType.VIDEO)),
                onEvent = { true },
                onPlayMedia = {},
                showSubtitles = false,
            )
        }

        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.Menu) }
        compose.onNode(hasText("Play") and isFocused()).assertIsDisplayed()
        compose.onAllNodesWithText("Subtitles").assertCountEquals(0)
    }

    @Test
    fun pinToHomeFollowsTheHomePins() {
        val pinned = mutableListOf<Pair<Long, Boolean>>()
        var pins by mutableStateOf<List<FilesItemId>?>(null)
        compose.setTvContent {
            TvFilesScreen(
                state = ready(item(3, "notes.txt", PutioFileType.TEXT)),
                onEvent = { true },
                onPlayMedia = {},
                pinnedIds = pins,
                onSetPinned = { item, pin -> pinned += item.id.value to pin },
            )
        }

        compose.onNodeWithContentDescription("notes.txt").performKeyInput { pressKey(Key.Menu) }
        compose.onAllNodesWithText("Pin to Home").assertCountEquals(0)
        compose.runOnIdle { pins = emptyList() }
        compose.onNodeWithContentDescription("notes.txt").performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Pin to Home").assertIsFocused().performKeyInput { pressCenter() }
        compose.runOnIdle { pins = listOf(FilesItemId(3)) }
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Unpin").assertIsFocused().performKeyInput { pressCenter() }
        compose.runOnIdle { assertEquals(listOf(3L to true, 3L to false), pinned) }
    }

    @Test
    fun permanentDeletionConfirmsWithTheRedDeleteBeforeDispatching() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = { ready(item(3, "notes.txt", PutioFileType.TEXT)) },
            onEvent = { events += it; true },
            confirmedTrashEnabled = { false },
        )

        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Delete permanently").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Delete permanently?").assertIsDisplayed()
        compose.onNodeWithText("Trash is off for this account, so notes.txt can't be restored.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }
        compose.onNodeWithText("Delete").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressCenter() }
        compose.onAllNodesWithText("Delete permanently?").assertCountEquals(0)
        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }

        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Delete permanently").performKeyInput { pressCenter() }
        compose.onNodeWithText("Delete").assertIsFocused().performKeyInput { pressCenter() }
        compose.runOnIdle {
            assertEquals(
                listOf<FilesBrowserEvent>(
                    FilesBrowserEvent.Delete(FilesFolder.Root.id, FilesItemId(3), FilesDeleteMode.PERMANENT),
                ),
                events,
            )
        }
        compose.onAllNodesWithText("Delete permanently?").assertCountEquals(0)
    }

    @Test
    fun aConfirmedTrashMoveShowsMovedToTrashAndDownFromTheListReachesUndo() {
        val events = mutableListOf<FilesBrowserEvent>()
        val undone = mutableListOf<FilesDeleteOutcome>()
        val outcome = trashedOutcome()
        val trashed = ready(item(3, "notes.txt", PutioFileType.TEXT)).withOutcome(outcome)
        compose.setTvContent {
            TvFilesScreen(
                state = trashed,
                onEvent = { events += it; true },
                onPlayMedia = {},
                trashUndo = rememberUndoOffer { undone += it },
            )
        }

        compose.onNodeWithText("Moved to Trash").assertIsDisplayed()
        compose.onAllNodesWithText("old.mp4 is no longer in Files.").assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.DeleteOutcomeAnnounced(outcome)), events)
        }
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Undo") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        compose.runOnIdle { assertEquals(listOf(outcome), undone) }
        compose.onAllNodesWithText("Moved to Trash").assertCountEquals(0)
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused()
    }

    @Test
    fun downFromTheLastRowOfAFullFolderReachesUndo() {
        val rows = (1L..30L).map { item(it, "file-$it.txt", PutioFileType.TEXT) }
        compose.setTvContent {
            TvFilesScreen(
                state = ready(*rows.toTypedArray()).withOutcome(trashedOutcome()),
                onEvent = { true },
                onPlayMedia = {},
                focusMemory = mutableMapOf(FilesFolder.Root.id.value to 30L),
                trashUndo = rememberUndoOffer {},
            )
        }

        compose.onNodeWithContentDescription("file-30.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Undo") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun undoOutlivesThePaneLeavingAndStillExpiresOnTime() {
        val outcome = trashedOutcome()
        var state by mutableStateOf(ready(item(3, "notes.txt", PutioFileType.TEXT)).withOutcome(outcome))
        var shown by mutableStateOf(true)
        compose.setTvContent {
            val offer = rememberUndoOffer {}
            if (shown) {
                TvFilesScreen(
                    state = state,
                    onEvent = { event ->
                        if (event is FilesBrowserEvent.DeleteOutcomeAnnounced) {
                            state = state.withOutcome(event.outcome.copy(announced = true))
                        }
                        true
                    },
                    onPlayMedia = {},
                    trashUndo = offer,
                )
            }
        }

        compose.onNodeWithText("Moved to Trash").assertIsDisplayed()
        compose.runOnIdle { shown = false }
        compose.mainClock.advanceTimeBy(SNACKBAR_MILLIS / 2)
        compose.runOnIdle { shown = true }
        compose.onNodeWithText("Moved to Trash").assertIsDisplayed()
        compose.onNode(hasText("Undo") and hasClickAction()).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(SNACKBAR_MILLIS / 2 + 500)
        compose.onAllNodesWithText("Moved to Trash").assertCountEquals(0)
    }

    private fun trashedOutcome(): FilesDeleteOutcome {
        val gone = item(4, "old.mp4", PutioFileType.VIDEO)
        return FilesDeleteOutcome(
            FilesRequestId(5), FilesFolderOperationIntent.Delete(gone.id, FilesDeleteMode.TRASH), gone.name,
            status = FilesDeleteStatus.NO_LONGER_AVAILABLE,
        )
    }

    private fun FilesBrowserState.withOutcome(outcome: FilesDeleteOutcome): FilesBrowserState =
        copyForTest(stack = listOf(current.copy(deleteOutcome = outcome)))

    @Test
    fun aTrashPressMadeBeforeTrashTurnedOffReachedTheMenuSendsNothing() {
        val events = mutableListOf<FilesBrowserEvent>()
        var trash by mutableStateOf<Boolean?>(true)
        compose.showFiles(
            state = { ready(item(3, "notes.txt", PutioFileType.TEXT)) },
            onEvent = { events += it; true },
            confirmedTrashEnabled = { trash },
        )

        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Move to trash").assertIsFocused()
        compose.mainClock.autoAdvance = false
        trash = false
        compose.onNodeWithText("Move to trash").performKeyInput { pressCenter() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }
        compose.onNodeWithText("Delete permanently").assertIsFocused()
    }

    @Test
    fun aTextRowOffersOnlyDeletionAndNothingWithoutTheTrashSetting() {
        var trash by mutableStateOf<Boolean?>(null)
        compose.showFiles(
            state = { ready(item(3, "notes.txt", PutioFileType.TEXT)) },
            confirmedTrashEnabled = { trash },
        )

        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onAllNodesWithText("Delete permanently").assertCountEquals(0)
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused()
        compose.runOnIdle { trash = false }
        compose.onNodeWithContentDescription("notes.txt").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithText("Delete permanently").assertIsFocused().performKeyInput { pressCenter() }
        compose.onAllNodesWithText("Mark as watched").assertCountEquals(0)
        // The setting flipping under an open confirmation withdraws it rather than rewording it.
        compose.onNodeWithText("Delete permanently?").assertIsDisplayed()
        compose.runOnIdle { trash = true }
        compose.onAllNodesWithText("Delete permanently?").assertCountEquals(0)
        compose.onNodeWithText("Move to trash").assertIsFocused()
    }

    @Test
    fun sharedItemsOfferNeitherTheWatchedToggleNorDeletion() {
        compose.showFiles(
            state = {
                ready(
                    item(2, "clip.mp4", PutioFileType.VIDEO, playback = FilesPlaybackProgress(0.0, 120.0))
                        .copy(isShared = true),
                    item(3, "Items shared with you", PutioFileType.FOLDER)
                        .copy(folderType = PutioFolderType.SHARED_ROOT),
                )
            },
            confirmedTrashEnabled = { true },
            watchedToggleEnabled = true,
        )

        compose.onNodeWithContentDescription("Play clip.mp4").assertIsFocused().performKeyInput { pressKey(Key.Menu) }
        compose.onNode(hasText("Play") and isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Open in VLC").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Open in VLC").assertIsFocused()
        compose.onAllNodesWithText("Mark as watched").assertCountEquals(0)
        compose.onAllNodesWithText("Move to trash").assertCountEquals(0)
    }

    @Test
    fun aSharedFolderOpensNoMenu() {
        compose.showFiles(
            state = {
                ready(
                    item(3, "Items shared with you", PutioFileType.FOLDER)
                        .copy(folderType = PutioFolderType.SHARED_ROOT),
                )
            },
            confirmedTrashEnabled = { true },
        )

        compose.onNodeWithContentDescription("Open Items shared with you").assertIsFocused().performKeyInput {
            pressKey(Key.Menu)
        }
        compose.onAllNodesWithText("Open in VLC").assertCountEquals(0)
        compose.onNodeWithContentDescription("Open Items shared with you").assertIsFocused()
    }

    @Test
    fun aRunningDeleteShowsItsPhaseAndAFailedCheckOffersCheckStatus() {
        val events = mutableListOf<FilesBrowserEvent>()
        val intent = FilesFolderOperationIntent.Delete(FilesItemId(2), FilesDeleteMode.TRASH)
        var state by mutableStateOf(
            state(
                FilesContent.Ready(listOf(item(2, "clip.mp4", PutioFileType.VIDEO)), FilesPaging.Complete),
                operation = FilesFolderOperation.Loading(FilesRequestId(3), intent, FilesFolderOperationPhase.DELETING),
            ),
        )
        compose.showFiles(state = { state }, onEvent = { events += it; true })

        compose.onNodeWithText("Moving to trash").assertIsDisplayed()
        compose.runOnIdle {
            state = state(
                FilesContent.Ready(listOf(item(2, "clip.mp4", PutioFileType.VIDEO)), FilesPaging.Complete),
                operation = FilesFolderOperation.Failed(
                    networkFailure(),
                    intent,
                    FilesFolderOperationPhase.CHECKING_DELETE,
                ),
            )
        }
        compose.onNodeWithText("Couldn’t confirm the result. Check the item before trying again.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play clip.mp4").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNode(hasText("Check status") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)
    }

    @Test
    fun aFolderTooLargeForTrashExplainsAndDeletesPermanentlyOnlyAfterConfirmation() {
        val events = mutableListOf<FilesBrowserEvent>()
        val folder = item(4, "Sample folder", PutioFileType.FOLDER)
        val outcome = FilesDeleteOutcome(
            FilesRequestId(5), FilesFolderOperationIntent.Delete(folder.id, FilesDeleteMode.TRASH), folder.name,
            status = FilesDeleteStatus.TOO_LARGE_FOR_TRASH,
        )
        fun withOutcome(listed: FilesItem) =
            ready(listed).let { it.copyForTest(stack = listOf(it.current.copy(deleteOutcome = outcome))) }
        var state by mutableStateOf(withOutcome(folder))
        setConfirmedTrashScreen({ state }, events)
        val message = "Sample folder contains too many files. Delete it permanently?"

        compose.onNodeWithText("We couldn’t send these files to trash").assertIsDisplayed()
        compose.onAllNodesWithText("Sample folder is still in Files. Open its actions to try again.")
            .assertCountEquals(0)
        compose.onNodeWithContentDescription("Open Sample folder").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNode(hasText("Delete permanently") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
        }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressCenter() }
        compose.onAllNodesWithText(message).assertCountEquals(0)
        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }

        compose.onNode(hasText("Delete permanently") and hasClickAction()).performKeyInput { pressCenter() }
        compose.onNodeWithText("Delete").assertIsFocused().performKeyInput { pressCenter() }
        compose.runOnIdle {
            assertEquals(
                listOf<FilesBrowserEvent>(
                    FilesBrowserEvent.Delete(FilesFolder.Root.id, folder.id, FilesDeleteMode.PERMANENT),
                ),
                events,
            )
        }
        compose.onAllNodesWithText(message).assertCountEquals(0)

        // A rename keeps the outcome; the confirmation names the folder as it is listed now.
        compose.runOnIdle { state = withOutcome(folder.copy(name = "Renamed folder")) }
        compose.onNode(hasText("Delete permanently") and hasClickAction()).apply {
            performSemanticsAction(SemanticsActions.RequestFocus)
            assertIsFocused()
            performKeyInput { pressCenter() }
        }
        compose.onNodeWithText("Renamed folder contains too many files. Delete it permanently?").assertIsDisplayed()
        compose.onAllNodesWithText("Sample folder").assertCountEquals(0)
    }

    private fun setConfirmedTrashScreen(current: () -> FilesBrowserState, events: MutableList<FilesBrowserEvent>) {
        compose.showFiles(state = { current() }, onEvent = { events += it; true }, confirmedTrashEnabled = { true })
    }
}

@Composable
private fun rememberUndoOffer(onUndo: (FilesDeleteOutcome) -> Unit): TvTrashUndoOffer {
    val scope = rememberCoroutineScope()
    return remember { TvTrashUndoOffer(scope, onUndo) }
}
