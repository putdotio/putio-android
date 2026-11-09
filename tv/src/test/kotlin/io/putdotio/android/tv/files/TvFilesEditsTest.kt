package io.putdotio.android.tv.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRenameCompletion
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.files.copyForTest
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesEditsTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val target = item(1, "old-name.txt", PutioFileType.TEXT)
    private val events = mutableListOf<FilesBrowserEvent>()

    @Test
    fun renameFromThePanelSendsTheEditedNameAndClosesWhenConfirmed() {
        var state by mutableStateOf(ready(target))
        show { state }
        openPanelRow(rows = 1)
        compose.onNode(hasText("Rename") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }

        val field = compose.onNodeWithTag(TV_FILES_RENAME_FIELD_TAG)
        field.assertIsFocused().assertTextEquals("old-name.txt")
        field.performTextReplacement("new-name.txt")
        field.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag(TV_FILES_RENAME_CONFIRM_TAG).assertIsFocused().performKeyInput { pressCenter() }

        val rename = FilesBrowserEvent.Rename(FilesFolder.Root.id, FilesItemId(1), "new-name.txt")
        assertEquals(listOf<FilesBrowserEvent>(rename), events)

        compose.runOnIdle {
            state = state.copyForTest(
                stack = listOf(
                    state.current.copy(
                        renameCompletion = FilesRenameCompletion(
                            FilesRequestId(7L),
                            FilesFolderOperationIntent.Rename(FilesItemId(1), "new-name.txt"),
                        ),
                    ),
                ),
            )
        }
        field.assertDoesNotExist()
        compose.onNode(hasText("Rename") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNodeWithContentDescription("old-name.txt").assertIsFocused()
    }

    @Test
    fun aFailedRenameExplainsInTheDialogAndCancelAbandonsIt() {
        var state by mutableStateOf(ready(target))
        show { state }
        openPanelRow(rows = 1)
        compose.onNode(hasText("Rename") and hasClickAction()).performKeyInput { pressCenter() }
        val intent = FilesFolderOperationIntent.Rename(FilesItemId(1), "taken.txt")
        compose.runOnIdle {
            state = state.copyForTest(
                stack = listOf(
                    state.current.copy(
                        operation = FilesFolderOperation.Failed(
                            networkFailure(),
                            intent,
                            FilesFolderOperationPhase.RENAMING,
                        ),
                    ),
                ),
            )
        }
        compose.onNode(hasText("Couldn’t rename. Try again or edit the name.")).assertExists()
        compose.onNodeWithTag(TV_FILES_RENAME_FIELD_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Cancel") and hasClickAction()).assertExists().performKeyInput { pressCenter() }

        assertEquals(
            listOf<FilesBrowserEvent>(FilesBrowserEvent.AbandonRename(FilesFolder.Root.id, intent)),
            events,
        )
        compose.onNodeWithTag(TV_FILES_RENAME_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun moveFromThePanelPicksAFolderWithTheDpadAndSubmitsIt() {
        val repository = MoveDestinations()
        show(edits = TvFilesEdits(repository, CoroutineScope(Dispatchers.Unconfined))) { ready(target) }
        openPanelRow(rows = 2)
        compose.onNode(hasText("Move") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }

        compose.waitUntil(5_000) { repository.reads.contains(0L) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Archive") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Archive") and hasClickAction()).assertIsFocused().performKeyInput { pressCenter() }

        compose.waitUntil(5_000) { repository.reads.contains(ARCHIVE) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Old") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Old") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithTag(TV_FILES_MOVE_HERE_TAG).assertIsFocused().performKeyInput { pressCenter() }

        assertEquals(
            listOf<FilesBrowserEvent>(
                FilesBrowserEvent.Move(FilesFolder.Root.id, listOf(FilesItemId(1)), FilesItemId(ARCHIVE)),
            ),
            events,
        )
    }

    @Test
    fun aRejectedMoveExplainsAndLeavesTheRowWhereItWas() {
        val repository = MoveDestinations(
            moveErrors = listOf(FileMoveError("NAME_ALREADY_EXIST", 1L, "old-name.txt", 400)),
        )
        val edits = TvFilesEdits(repository, CoroutineScope(Dispatchers.Unconfined))
        compose.setTvContent {
            TvItemEditLayer(
                TvItemEditTarget(),
                edits,
                trashEnabled = true,
                snackbar = null,
                onAuthenticationRequired = {},
            )
        }

        compose.runOnIdle { edits.move(target, FilesItemId(ARCHIVE)) }

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("named “old-name.txt” already exists there", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(TvItemEditState.Failed::class, edits.state.value::class)
    }

    @Test
    fun aRenameThatFailsAfterSubmittingKeepsFocusOnTheField() {
        var state by mutableStateOf(ready(target))
        show { state }
        openPanelRow(rows = 1)
        compose.onNode(hasText("Rename") and hasClickAction()).performKeyInput { pressCenter() }
        val field = compose.onNodeWithTag(TV_FILES_RENAME_FIELD_TAG).assertIsFocused()
        val intent = FilesFolderOperationIntent.Rename(FilesItemId(1), "taken.txt")

        compose.runOnIdle {
            state = state.copyForTest(
                stack = listOf(
                    state.current.copy(
                        operation = FilesFolderOperation.Loading(
                            FilesRequestId(8L),
                            intent,
                            FilesFolderOperationPhase.RENAMING,
                        ),
                    ),
                ),
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            state = state.copyForTest(
                stack = listOf(
                    state.current.copy(
                        operation = FilesFolderOperation.Failed(
                            networkFailure(),
                            intent,
                            FilesFolderOperationPhase.RENAMING,
                        ),
                    ),
                ),
            )
        }

        field.assertIsFocused()
    }

    @Test
    fun theLastRowTakesFocusWhenMoreFoldersLoadsTheFinalPage() {
        val repository = MoveDestinations(paged = true)
        show(edits = TvFilesEdits(repository, CoroutineScope(Dispatchers.Unconfined))) { ready(target) }
        openPanelRow(rows = 2)
        compose.onNode(hasText("Move") and hasClickAction()).performKeyInput { pressCenter() }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Archive") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Archive") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("More folders") and hasClickAction()).assertIsFocused().performKeyInput {
            pressCenter()
        }

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Old") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNode(hasText("Old") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun anEmptyFolderThatCannotTakeTheItemStillPlacesFocus() {
        val repository = MoveDestinations(noFolders = true)
        show(edits = TvFilesEdits(repository, CoroutineScope(Dispatchers.Unconfined))) { ready(target) }
        openPanelRow(rows = 2)
        compose.onNode(hasText("Move") and hasClickAction()).performKeyInput { pressCenter() }

        compose.waitUntil(5_000) { repository.reads.contains(0L) }
        compose.waitForIdle()

        compose.onNodeWithTag(TV_FILES_MOVE_HERE_TAG).assertIsFocused()
    }

    @Test
    fun aMoveWhoseResponseWasLostCountsOnceTheReadFindsItThere() {
        val updated = mutableListOf<FilesItem>()
        val landed = target.copy(parentId = FilesItemId(ARCHIVE))
        val repository = MoveDestinations(
            failures = EditFailures(move = networkFailure()),
            read = { PutioResult.Success(landed) },
        )
        val edits = TvFilesEdits(
            repository,
            CoroutineScope(Dispatchers.Unconfined),
            TvItemChanges(onUpdated = { updated += it }),
        )

        edits.move(target, FilesItemId(ARCHIVE))

        assertEquals(TvItemEditState.Moved("old-name.txt"), edits.state.value)
        assertEquals(listOf(landed), updated)
    }

    @Test
    fun aDeleteWhoseResponseWasLostRemovesTheItemOnceTheReadFindsItGone() {
        val removed = mutableListOf<FilesItem>()
        val repository = MoveDestinations(
            failures = EditFailures(delete = networkFailure()),
            read = { PutioResult.Failure(notFound()) },
        )
        val edits = TvFilesEdits(
            repository,
            CoroutineScope(Dispatchers.Unconfined),
            TvItemChanges(onRemoved = { item, _ -> removed += item }),
        )

        edits.delete(target, FilesDeleteMode.TRASH)

        assertEquals(listOf(target), removed)
        assertEquals(TvItemEditState.Idle, edits.state.value)
    }

    @Test
    fun aFolderOverTheTrashLimitOffersPermanentDeletion() {
        val failures = EditFailures(
            delete = PutioFailure.ApiRejected(
                400,
                "FileDeleteChildrenLimitError",
                PutioConfigurationException("limit"),
                400,
            ),
        )
        val repository = MoveDestinations(failures = failures)
        val edits = TvFilesEdits(repository, CoroutineScope(Dispatchers.Unconfined))
        compose.setTvContent {
            TvItemEditLayer(
                TvItemEditTarget(),
                edits,
                trashEnabled = true,
                snackbar = null,
                onAuthenticationRequired = {},
            )
        }

        compose.runOnIdle { edits.delete(target, FilesDeleteMode.TRASH) }

        compose.onNode(hasText("We couldn’t send these files to trash")).assertExists()
        failures.delete = null
        repository.read = { PutioResult.Failure(notFound()) }
        compose.onNode(hasText("Delete") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(listOf(FilesDeleteMode.TRASH, FilesDeleteMode.PERMANENT), repository.deletes)
        }
    }

    @Test
    fun aFailedPermanentDeleteNeverTalksAboutTrash() {
        val repository = MoveDestinations(failures = EditFailures(delete = networkFailure()))
        val edits = TvFilesEdits(repository, CoroutineScope(Dispatchers.Unconfined))
        compose.setTvContent {
            TvItemEditLayer(
                TvItemEditTarget(),
                edits,
                trashEnabled = false,
                snackbar = null,
                onAuthenticationRequired = {},
            )
        }

        compose.runOnIdle { edits.delete(target, FilesDeleteMode.PERMANENT) }

        compose.onNode(hasText("old-name.txt is still in Files. Open its actions to try again.")).assertExists()
        compose.onAllNodes(hasText("trash", substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    @Test
    fun anEditWhileAnotherRunsIsRefusedAndNothingIsSent() {
        val gate = CompletableDeferred<Unit>()
        val repository = MoveDestinations(deleteGate = gate)
        val edits = TvFilesEdits(repository, CoroutineScope(Dispatchers.Unconfined))

        assertTrue(edits.delete(target, FilesDeleteMode.TRASH))
        assertTrue(edits.busy.value)
        assertFalse(edits.move(target, FilesItemId(ARCHIVE)))
        assertEquals(0, repository.moves)

        gate.complete(Unit)
        assertFalse(edits.busy.value)
    }

    @Test
    fun aRejectedSessionFromAnyEditGoesToTheSessionRoute() {
        val expired = PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))
        val rejected = mutableListOf<PutioFailure>()
        val edits = TvFilesEdits(
            MoveDestinations(failures = EditFailures(expired, expired, expired)),
            CoroutineScope(Dispatchers.Unconfined),
            TvItemChanges(onSessionRejected = { rejected += it }),
        )

        edits.rename(target, "new.txt")
        edits.move(target, FilesItemId(ARCHIVE))
        edits.delete(target, FilesDeleteMode.TRASH)

        assertEquals(listOf<PutioFailure>(expired, expired, expired), rejected)
        assertEquals(TvItemEditState.Idle, edits.state.value)
    }

    @Test
    fun aRefreshUnderTheRenameDialogIsNotARenameInProgress() {
        val listed = item(1L, "old-name.txt", PutioFileType.TEXT)
        val folder = ready(listed).current.copy(
            operation = FilesFolderOperation.Loading(
                FilesRequestId(1L),
                FilesFolderOperationIntent.Refresh,
                FilesFolderOperationPhase.PERSISTING_SORT,
            ),
        )
        compose.setTvContent { TvFilesListingRename(listed, folder, onEvent = { true }, onClose = {}) }

        compose.onAllNodesWithText("Renaming…").assertCountEquals(0)
        compose.onNodeWithTag(TV_FILES_RENAME_CONFIRM_TAG).assertIsNotEnabled()
    }

    @Test
    fun aBlankNameCannotBeSubmitted() {
        compose.setTvContent {
            TvRenameDialog(
                name = "old-name.txt",
                isFolder = false,
                initialDraft = null,
                pending = false,
                blocked = false,
                failure = null,
                onRename = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithTag(TV_FILES_RENAME_FIELD_TAG).performTextClearance()

        compose.onNodeWithTag(TV_FILES_RENAME_CONFIRM_TAG).assertIsNotEnabled()
    }

    @Test
    fun aResultThatLandedWhileNoScreenWasShowingIsNotRaisedLater() {
        val edits = TvFilesEdits(MoveDestinations(), CoroutineScope(Dispatchers.Unconfined))
        edits.delete(item(1L, "old-name.txt", PutioFileType.TEXT), FilesDeleteMode.TRASH)
        assertTrue(edits.state.value is TvItemEditState.Failed)

        compose.setTvContent {
            TvItemEditLayer(
                target = TvItemEditTarget(),
                edits = edits,
                trashEnabled = true,
                snackbar = null,
                onAuthenticationRequired = {},
            )
        }

        compose.onAllNodesWithText("old-name.txt is still in Files", substring = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals(TvItemEditState.Idle, edits.state.value) }
    }

    private fun openPanelRow(rows: Int) {
        compose.onNodeWithContentDescription("old-name.txt").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag(TV_FILES_DETAIL_PRIMARY_TAG).assertIsFocused().performKeyInput {
            repeat(rows) { pressKey(Key.DirectionDown) }
        }
    }

    private fun show(
        edits: TvFilesEdits = TvFilesEdits(MoveDestinations(), CoroutineScope(Dispatchers.Unconfined)),
        state: () -> FilesBrowserState,
    ) {
        compose.setTvContent {
            TvFilesScreen(
                state = state(),
                onEvent = {
                    events += it
                    true
                },
                onPlayMedia = {},
                edits = edits,
            )
        }
    }

    private class EditFailures(
        val rename: PutioFailure? = null,
        val move: PutioFailure? = null,
        var delete: PutioFailure? = null,
    )

    private class MoveDestinations(
        private val moveErrors: List<FileMoveError> = emptyList(),
        private val paged: Boolean = false,
        private val noFolders: Boolean = false,
        private val failures: EditFailures = EditFailures(),
        private val deleteGate: CompletableDeferred<Unit>? = null,
        var read: () -> PutioResult<FilesItem> = {
            PutioResult.Success(item(1L, "old-name.txt", PutioFileType.TEXT).copy(parentId = FilesItemId(ARCHIVE)))
        },
    ) : StubFilesRepository() {
        val reads = mutableListOf<Long>()
        val deletes = mutableListOf<FilesDeleteMode>()
        var moves = 0

        override suspend fun loadFolder(folderId: FilesItemId) = error("not used")

        override suspend fun loadMoveDestinations(
            folderId: FilesItemId,
            cursor: FilesCursor?,
        ): PutioResult<FilesPage> {
            reads += folderId.value
            if (noFolders) return PutioResult.Success(FilesPage(items = emptyList(), nextCursor = null))
            val atRoot = folderId.value == 0L && cursor == null
            val names = if (atRoot) listOf("Archive" to ARCHIVE) else listOf("Old" to OLD)
            return PutioResult.Success(
                FilesPage(
                    items = names.map { (name, id) -> folder(id, name, folderId) },
                    nextCursor = if (paged && cursor == null) FilesCursor("more") else null,
                ),
            )
        }

        override suspend fun rename(itemId: FilesItemId, name: String): PutioResult<Unit> =
            failures.rename?.let { PutioResult.Failure(it) } ?: PutioResult.Success(Unit)

        override suspend fun move(
            itemIds: List<FilesItemId>,
            destinationId: FilesItemId,
        ): PutioResult<List<FileMoveError>> {
            moves++
            return failures.move?.let { PutioResult.Failure(it) } ?: PutioResult.Success(moveErrors)
        }

        override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> = read()

        override suspend fun delete(itemIds: List<FilesItemId>, mode: FilesDeleteMode): PutioResult<FileDeleteResult> {
            deletes += mode
            deleteGate?.await()
            return failures.delete?.let { PutioResult.Failure(it) }
                ?: PutioResult.Success(FileDeleteResult(cursor = null, skipped = 0, status = "OK"))
        }

        private fun folder(id: Long, name: String, parent: FilesItemId) =
            item(id, name, PutioFileType.FOLDER).copy(parentId = parent)
    }

    private fun notFound() = PutioFailure.ApiRejected(404, null, PutioConfigurationException("gone"), 404)

    private companion object {
        const val ARCHIVE = 501L
        const val OLD = 502L
    }
}
