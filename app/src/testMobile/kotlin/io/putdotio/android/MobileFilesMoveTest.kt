package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesBrowserTransition
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileFilesMoveTest {
    @get:Rule
    val compose = createComposeRule()

    private val source = folder(7L, "été 東京")
    private val destination = folder(8L, "Destination")

    @Test
    fun pickerNavigationRejectsCurrentParentAndSelfAndCancelKeepsTheSource() {
        val events = mutableListOf<FilesBrowserEvent>()
        val initial = loadedRoot()
        val repository = destinationRepository()
        compose.setContent {
            PutioTheme { MobileFilesRoute(initial, repository, events::add, {}, true, {}) }
        }
        openMove()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(source.id)).assertIsNotEnabled()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).performClick()
        compose.onNodeWithText("No folders here.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsEnabled()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_BACK_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription("Actions for ${source.name}").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(events.none { it is FilesBrowserEvent.Move || it is FilesBrowserEvent.OpenFolder })
            assertEquals(listOf(FilesFolder.Root), initial.path)
        }
    }

    @Test
    fun cancelInvalidatesAQueuedConfirmBeforeThePickerIsDisposed() {
        val events = mutableListOf<FilesBrowserEvent>()
        val repository = destinationRepository()
        compose.setContent {
            PutioTheme { MobileFilesRoute(loadedRoot(), repository, events::add, {}, true, {}) }
        }
        openMove()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsEnabled()
        val cancel = clickAction(MOBILE_FILES_MOVE_CANCEL_TAG)
        val confirm = clickAction(MOBILE_FILES_MOVE_HERE_TAG)
        compose.runOnIdle {
            cancel()
            confirm()
            assertTrue(events.none { it is FilesBrowserEvent.Move })
        }
        compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG).assertDoesNotExist()
    }

    @Test
    fun duplicateQueuedConfirmSendsOneMoveAndAFreshPickerCanBeOpened() {
        val events = mutableListOf<FilesBrowserEvent>()
        val repository = destinationRepository()
        compose.setContent {
            PutioTheme { MobileFilesRoute(loadedRoot(), repository, events::add, {}, true, {}) }
        }
        openMove()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).performClick()
        val confirm = clickAction(MOBILE_FILES_MOVE_HERE_TAG)
        compose.runOnIdle {
            confirm()
            confirm()
            assertEquals(
                listOf(FilesBrowserEvent.Move(FilesFolder.Root.id, source.id, destination.id)),
                events.filterIsInstance<FilesBrowserEvent.Move>(),
            )
        }
        compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG).assertDoesNotExist()
        openMove()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsEnabled()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
    }

    @Test
    fun emptyDestinationPageCanContinueAndRetryWithoutTouchingSourceFiles() {
        var continuations = 0
        val requests = mutableListOf<FilesCursor?>()
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected source read")
            override suspend fun loadMoveDestinations(
                folderId: FilesItemId,
                cursor: FilesCursor?,
            ): FilesRepositoryResult<FilesPage> {
                requests += cursor
                return when {
                    cursor == null -> FilesRepositoryResult.Success(FilesPage(emptyList(), FilesCursor("next")))
                    ++continuations == 1 -> FilesRepositoryResult.Failure(failure("offline page"))
                    else -> FilesRepositoryResult.Success(FilesPage(listOf(destination), null))
                }
            }
        }
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme { MobileFilesRoute(loadedRoot(), repository, events::add, {}, true, {}) }
        }
        openMove()
        compose.onNodeWithText("No folders on this page.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_LOAD_MORE_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_RETRY_TAG).performClick()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
        compose.runOnIdle {
            assertEquals(listOf(null, FilesCursor("next"), FilesCursor("next")), requests)
            assertTrue(events.none { it is FilesBrowserEvent.Move || it is FilesBrowserEvent.LoadNextPage })
        }
    }

    @Test
    fun itemErrorsNeverShowSuccessEvenWhenReadbackFindsTheDestination() {
        val errors = listOf(FileMoveError("NAME_ALREADY_EXIST", source.id.value, source.name, 400))
        val checking = finishedPost(errors)
        val reloading = FilesBrowserReducer.reduce(checking.state, FilesBrowserEvent.MoveChecked(
            checkNotNull(checking.effect).requestId, FilesRepositoryResult.Success(source.copy(parentId = destination.id)),
        ))
        var state by mutableStateOf(reload(
            reloading.state, checkNotNull(reloading.effect).requestId, FilesPage(listOf(source), null),
        ))
        compose.setContent { PutioTheme { MobileFilesScreen(state, {}, {}, confirmedTrashEnabled = true) } }
        compose.onNodeWithText(
            "A file or folder named “${source.name}” already exists there. Choose another folder.",
        ).assertIsDisplayed()
        compose.onNodeWithText("“${source.name}” is in the selected folder.").assertDoesNotExist()
        compose.runOnIdle {
            val unknown = listOf(FileMoveError("FUTURE_ERROR", 999L, null, 409))
            val next = finishedPost(unknown)
            val read = FilesBrowserReducer.reduce(next.state, FilesBrowserEvent.MoveChecked(
                checkNotNull(next.effect).requestId, FilesRepositoryResult.Success(source.copy(parentId = destination.id)),
            ))
            state = reload(read.state, checkNotNull(read.effect).requestId, FilesPage(emptyList(), null))
        }
        compose.onNodeWithText("Couldn’t move “${source.name}”. Choose another folder and try again.").assertIsDisplayed()
        compose.onNodeWithText("“${source.name}” is in the selected folder.").assertDoesNotExist()
    }

    @Test
    fun failedMoveOffersStatusCheckAndFailedReloadOffersReadRetry() {
        val checking = finishedPost()
        var state by mutableStateOf(FilesBrowserReducer.reduce(checking.state, FilesBrowserEvent.LoadFailed(
            checkNotNull(checking.effect).requestId, failure("offline read"),
        )).state)
        val effects = mutableListOf<FilesBrowserEffect>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(state, { event ->
                    val next = FilesBrowserReducer.reduce(state, event)
                    state = next.state
                    next.effect?.let(effects::add)
                }, {}, confirmedTrashEnabled = true)
            }
        }
        compose.onNodeWithText("Check status").performClick()
        compose.runOnIdle {
            assertTrue(effects.single() is FilesBrowserEffect.CheckMove)
            val reloading = FilesBrowserReducer.reduce(state, FilesBrowserEvent.MoveChecked(
                effects.single().requestId, FilesRepositoryResult.Success(source.copy(parentId = destination.id)),
            ))
            state = FilesBrowserReducer.reduce(reloading.state, FilesBrowserEvent.LoadFailed(
                checkNotNull(reloading.effect).requestId, failure("reload offline"),
            )).state
        }
        compose.onNodeWithText("Item location checked, but couldn’t reload files.").assertIsDisplayed()
        compose.onNodeWithText("Check status").assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle {
            assertTrue(effects.last() is FilesBrowserEffect.LoadFolder)
            assertTrue(effects.none { it is FilesBrowserEffect.Move })
        }
    }

    @Test
    fun replacingTheSessionRepositoryCancelsReadsAndResetsDestinationSelection() {
        val readStarted = CompletableDeferred<Unit>()
        val readCancelled = CompletableDeferred<Unit>()
        val previousRepository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected source read")
            override suspend fun loadMoveDestinations(
                folderId: FilesItemId,
                cursor: FilesCursor?,
            ): FilesRepositoryResult<FilesPage> {
                if (cursor != null) {
                    readStarted.complete(Unit)
                    try { awaitCancellation() } finally { readCancelled.complete(Unit) }
                }
                return FilesRepositoryResult.Success(
                    if (folderId == FilesFolder.Root.id) FilesPage(listOf(destination), null)
                    else FilesPage(emptyList(), FilesCursor("old-session-page")),
                )
            }
        }
        val nextRequests = mutableListOf<FilesItemId>()
        val nextRepository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected source read")
            override suspend fun loadMoveDestinations(
                folderId: FilesItemId,
                cursor: FilesCursor?,
            ): FilesRepositoryResult<FilesPage> {
                nextRequests += folderId
                return FilesRepositoryResult.Success(FilesPage(
                    if (folderId == FilesFolder.Root.id) listOf(destination) else emptyList(), null,
                ))
            }
        }
        var repository by mutableStateOf<FilesRepository>(previousRepository)
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme { MobileFilesRoute(loadedRoot(), repository, events::add, {}, true, {}) }
        }
        openMove()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsEnabled()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_LOAD_MORE_TAG).performClick()
        compose.runOnIdle { assertTrue(readStarted.isCompleted) }
        compose.runOnIdle { repository = nextRepository }
        compose.waitForIdle()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(readCancelled.isCompleted)
            assertTrue(events.none { it is FilesBrowserEvent.Move })
        }
        openMove()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(FilesFolder.Root.id, destination.id), nextRequests)
            assertEquals(
                listOf(FilesBrowserEvent.Move(FilesFolder.Root.id, source.id, destination.id)),
                events.filterIsInstance<FilesBrowserEvent.Move>(),
            )
        }
    }

    @Test
    fun rejectedConfirmationKeepsThePickerAndDestination() {
        var attempts = 0
        val repository = destinationRepository()
        compose.setContent {
            PutioTheme {
                MobileFilesRoute(loadedRoot(), repository, { event ->
                    if (event is FilesBrowserEvent.Move) attempts += 1
                    false
                }, {}, true, {})
            }
        }
        openMove()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsEnabled()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
        compose.runOnIdle { assertEquals(1, attempts) }
    }

    @Test
    fun failedAncestorOperationDisablesAnOpenPickerAndHidesNewMoveActions() {
        val root = loadedRoot()
        val opened = FilesBrowserReducer.reduce(root, FilesBrowserEvent.OpenFolder(source.id))
        val child = source.copy(id = FilesItemId(99), parentId = source.id)
        val loaded = reload(opened.state, checkNotNull(opened.effect).requestId, FilesPage(listOf(child), null))
        var state by mutableStateOf(loaded)
        val repository = destinationRepository()
        compose.setContent {
            PutioTheme { MobileFilesRoute(state, repository, { true }, {}, true, {}) }
        }
        openMove()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsEnabled()
        compose.runOnIdle {
            val ancestor = loaded.stack.first().copy(operation = FilesFolderOperation.Failed(
                failure("ancestor refresh failed"), FilesFolderOperationIntent.Refresh,
                FilesFolderOperationPhase.RELOADING,
            ))
            state = loaded.copy(stack = listOf(ancestor, loaded.current))
            val rejected = FilesBrowserReducer.reduce(state,
                FilesBrowserEvent.Move(state.current.folder.id, child.id, FilesFolder.Root.id))
            assertTrue(!rejected.consumed)
        }
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
        compose.onNodeWithContentDescription("Actions for ${source.name}").performClick()
        compose.onNodeWithText("Move").assertDoesNotExist()
    }

    @Test
    fun pickerAuthenticationFailureRejectsTheSession() {
        var rejections = 0
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected source read")
            override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) =
                FilesRepositoryResult.Failure(
                    FilesFailure.AuthenticationRequired(PutioConfigurationException("Expired picker session")),
                )
        }
        compose.setContent {
            PutioTheme { MobileFilesRoute(loadedRoot(), repository, { true }, {}, true, { rejections += 1 }) }
        }
        openMove()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_RETRY_TAG).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, rejections) }
    }

    @Test
    fun pickerPaginationAuthenticationFailureBlocksQueuedConfirmationWhileRejectionIsPending() {
        val events = mutableListOf<FilesBrowserEvent>()
        var rejections = 0
        val pageResult = CompletableDeferred<FilesRepositoryResult<FilesPage>>()
        val child = folder(9L, "Nested child").copy(parentId = destination.id)
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected source read")
            override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) =
                when {
                    folderId == FilesFolder.Root.id ->
                        FilesRepositoryResult.Success(FilesPage(listOf(destination), null))
                    folderId == destination.id && cursor == null ->
                        FilesRepositoryResult.Success(FilesPage(listOf(child), FilesCursor("next")))
                    folderId == destination.id && cursor == FilesCursor("next") -> pageResult.await()
                    else -> error("Unexpected destination read")
                }
        }
        compose.setContent {
            PutioTheme {
                MobileFilesRoute(loadedRoot(), repository, events::add, {}, true, {
                    rejections += 1
                    awaitCancellation()
                })
            }
        }
        openMove()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsEnabled()
        val queuedConfirm = clickAction(MOBILE_FILES_MOVE_HERE_TAG)
        compose.onNodeWithTag(MOBILE_FILES_MOVE_LOAD_MORE_TAG).performClick()
        compose.runOnIdle {
            pageResult.complete(FilesRepositoryResult.Failure(
                FilesFailure.AuthenticationRequired(PutioConfigurationException("Expired picker session")),
            ))
        }
        compose.onNodeWithTag(MOBILE_FILES_MOVE_RETRY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, rejections)
            queuedConfirm()
            queuedConfirm()
            assertTrue(events.none { it is FilesBrowserEvent.Move })
        }
        compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(child.id)).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
    }

    private fun openMove() {
        compose.onNodeWithContentDescription("Actions for ${source.name}").performClick()
        compose.onNodeWithText("Move").performClick()
    }

    private fun clickAction(tag: String) = checkNotNull(compose.onNodeWithTag(tag)
        .fetchSemanticsNode().config[SemanticsActions.OnClick].action)

    private fun destinationRepository() = object : StubFilesRepository() {
        override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected source read")
        override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) =
            FilesRepositoryResult.Success(FilesPage(
                if (folderId == FilesFolder.Root.id) listOf(source, destination) else emptyList(), null,
            ))
    }

    private fun finishedPost(errors: List<FileMoveError> = emptyList()): FilesBrowserTransition {
        val moving = FilesBrowserReducer.reduce(
            loadedRoot(), FilesBrowserEvent.Move(FilesFolder.Root.id, source.id, destination.id),
        )
        return FilesBrowserReducer.reduce(moving.state, FilesBrowserEvent.MoveFinished(
            checkNotNull(moving.effect).requestId, FilesRepositoryResult.Success(errors),
        ))
    }

    private fun loadedRoot(): FilesBrowserState {
        val initial = FilesBrowserReducer.start()
        return reload(initial.state, checkNotNull(initial.effect).requestId, FilesPage(listOf(source), null))
    }

    private fun reload(state: FilesBrowserState, requestId: FilesRequestId, page: FilesPage) =
        FilesBrowserReducer.reduce(state, FilesBrowserEvent.LoadSucceeded(requestId, page)).state
    private fun failure(message: String) = FilesFailure.Unexpected(IllegalStateException(message))
    private fun folder(id: Long, name: String) =
        FilesItem(FilesItemId(id), FilesFolder.Root.id, name, PutioFileType.FOLDER, 1L, "2026-09-06")
}
