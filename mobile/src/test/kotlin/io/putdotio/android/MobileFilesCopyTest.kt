package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesCopyId
import io.putdotio.android.files.FilesCopyProgress
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.MOBILE_FILES_COPY_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_COPY_DISMISS_TAG
import io.putdotio.android.files.MOBILE_FILES_COPY_STATUS_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_CANCEL_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_FOLDER_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_REMEMBER_TAG
import io.putdotio.android.files.FilesMoveTargetMemory
import io.putdotio.android.files.InMemoryMoveTargetStore
import io.putdotio.android.files.MOBILE_FILES_MOVE_HERE_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_PICKER_TAG
import io.putdotio.android.files.MobileFilesRoute
import io.putdotio.android.files.MobileFilesScreen
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.files.mobileFilesMoveFolderTag
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileFilesCopyTest {
    @get:Rule
    val compose = createComposeRule()

    private val sharedVideo = item(7L, "Harbor film.mp4", PutioFileType.VIDEO, isShared = true)
    private val sharedFolder = item(8L, "Archive été 東京", PutioFileType.FOLDER, isShared = true)
    private val friend = item(9L, "friend", PutioFileType.FOLDER, PutioFolderType.SHARED_FRIEND, isShared = true)
    private val owned = item(10L, "Owned notes.txt", PutioFileType.TEXT)
    private val destination = item(11L, "Sample folder", PutioFileType.FOLDER)

    @Test
    fun sharedItemsOfferMakeACopyIntoAFolderPickedFromRoot() {
        val events = mutableListOf<FilesBrowserEvent>()
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected source read")

            override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) =
                FilesRepositoryResult.Success(
                    FilesPage(if (folderId == FilesFolder.Root.id) listOf(destination) else emptyList(), null),
                )
        }
        compose.setContent {
            PutioTheme {
                val state = loaded(listOf(sharedVideo, sharedFolder, friend, owned))
                MobileFilesRoute(state, repository, events::add, {}, true, {})
            }
        }

        compose.onNodeWithContentDescription("Actions for ${friend.name}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Actions for ${sharedVideo.name}").performClick()
        for (owner in listOf("Rename", "Move", "Move to trash")) compose.onAllNodesWithText(owner).assertCountEquals(0)
        compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG).assertDoesNotExist()

        compose.onNodeWithContentDescription("Actions for ${sharedFolder.name}").performClick()
        compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).performClick()
        compose.onNode(hasText(sharedFolder.name) and hasAnyAncestor(hasTestTag(MOBILE_FILES_MOVE_PICKER_TAG)))
            .assertIsDisplayed()
        compose.onNodeWithText("Make a copy").assertIsDisplayed()
        compose.onNodeWithText("Copy here").assertIsEnabled()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).performClick()
        compose.onNodeWithText("No folders here.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).performClick()

        compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    FilesBrowserEvent.Copy(
                        FilesFolder.Root.id, sharedFolder.id, FilesFolder(destination.id, destination.name),
                    ),
                ),
                events.filterIsInstance<FilesBrowserEvent.Copy>(),
            )
        }
        compose.onNodeWithContentDescription("Actions for ${owned.name}").performClick()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).assertDoesNotExist()
    }

    @Test
    fun makeACopyOpensAtTheRememberedFolderUntilRememberIsTurnedOff() {
        val remembered = listOf(FilesFolder(destination.id, destination.name))
        val store = InMemoryMoveTargetStore(FilesMoveTargetMemory(remember = true, lastTarget = remembered))
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected source read")
            override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) =
                FilesRepositoryResult.Success(
                    FilesPage(if (folderId == FilesFolder.Root.id) listOf(destination) else emptyList(), null),
                )
        }
        compose.setContent {
            PutioTheme {
                MobileFilesRoute(loaded(listOf(sharedVideo)), repository, { true }, {}, true, {},
                    moveTargetStore = store)
            }
        }
        compose.onNodeWithContentDescription("Actions for ${sharedVideo.name}").performClick()
        compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_FOLDER_TAG).assertTextEquals(destination.name)
        compose.onNodeWithTag(MOBILE_FILES_MOVE_REMEMBER_TAG).assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
        compose.runOnIdle { assertEquals(FilesMoveTargetMemory(remember = false, lastTarget = remembered), store.memory) }

        compose.onNodeWithContentDescription("Actions for ${sharedVideo.name}").performClick()
        compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).performClick()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_REMEMBER_TAG).assertIsOff()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
    }

    @Test
    fun copyHereWithRememberOnRecordsTheFolderTheNextPickerOpensAt() {
        val store = InMemoryMoveTargetStore(FilesMoveTargetMemory(remember = true))
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): FilesRepositoryResult<FilesPage> =
                error("Unexpected source read")
            override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) =
                FilesRepositoryResult.Success(
                    FilesPage(if (folderId == FilesFolder.Root.id) listOf(destination) else emptyList(), null),
                )
        }
        compose.setContent {
            PutioTheme {
                MobileFilesRoute(loaded(listOf(sharedVideo)), repository, { true }, {}, true, {},
                    moveTargetStore = store)
            }
        }
        compose.onNodeWithContentDescription("Actions for ${sharedVideo.name}").performClick()
        compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).performClick()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(destination.id)).performClick()
        compose.onNodeWithText("No folders here.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG).assertDoesNotExist()
        val chosen = listOf(FilesFolder(destination.id, destination.name))
        compose.runOnIdle { assertEquals(FilesMoveTargetMemory(remember = true, lastTarget = chosen), store.memory) }

        compose.onNodeWithContentDescription("Actions for ${sharedVideo.name}").performClick()
        compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_FOLDER_TAG).assertTextEquals(destination.name)
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
    }

    @Test
    fun theCopyLineFollowsTheCopyAndClearsOnceSettled() {
        val start = FilesBrowserReducer.reduce(
            loaded(listOf(sharedVideo)), FilesBrowserEvent.Copy(FilesFolder.Root.id, sharedVideo.id, FilesFolder.Root),
        )
        val copying = FilesBrowserReducer.reduce(
            start.state,
            FilesBrowserEvent.CopyStarted(
                checkNotNull(start.effect).requestId, FilesRepositoryResult.Success(FilesCopyId(42L)),
            ),
        )
        var state by mutableStateOf(copying.state)
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                MobileFilesScreen(
                    state,
                    onEvent = { events += it; state = FilesBrowserReducer.reduce(state, it).state },
                    onPlayMedia = {},
                    onCopyItem = {},
                )
            }
        }

        compose.onNodeWithText("Copying “Harbor film.mp4” to Files…").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_COPY_DISMISS_TAG).assertDoesNotExist()

        compose.runOnIdle {
            state = FilesBrowserReducer.reduce(
                state,
                FilesBrowserEvent.CopyChecked(
                    checkNotNull(copying.effect).requestId, FilesRepositoryResult.Success(FilesCopyProgress.Done),
                ),
            ).state
        }
        compose.onNodeWithText("Copied “Harbor film.mp4” to Files.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_COPY_DISMISS_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_COPY_STATUS_TAG).assertDoesNotExist()
        compose.runOnIdle { assertEquals(FilesBrowserEvent.DismissCopyOutcome, events.last()) }
    }

    @Test
    fun theCopyLineStaysWhileAFolderLoadsOrFails() {
        val running = FilesBrowserReducer.reduce(
            loaded(listOf(sharedVideo, sharedFolder)),
            FilesBrowserEvent.Copy(FilesFolder.Root.id, sharedVideo.id, FilesFolder.Root),
        ).state
        val opening = FilesBrowserReducer.reduce(running, FilesBrowserEvent.OpenFolder(sharedFolder.id))
        var state by mutableStateOf(opening.state)
        compose.setContent { PutioTheme { MobileFilesScreen(state, onEvent = {}, onPlayMedia = {}) } }

        compose.onNodeWithText("Copying “Harbor film.mp4” to Files…").assertIsDisplayed()

        compose.runOnIdle {
            state = FilesBrowserReducer.reduce(
                state,
                FilesBrowserEvent.LoadFailed(
                    checkNotNull(opening.effect).requestId,
                    FilesFailure.Unexpected(IllegalStateException("offline")),
                ),
            ).state
        }
        compose.onNodeWithText("Copying “Harbor film.mp4” to Files…").assertIsDisplayed()
    }

    @Test
    fun aRunningCopyHoldsMakeACopyOnOtherItems() {
        val running = FilesBrowserReducer.reduce(
            loaded(listOf(sharedVideo, sharedFolder)),
            FilesBrowserEvent.Copy(FilesFolder.Root.id, sharedVideo.id, FilesFolder.Root),
        ).state
        compose.setContent {
            PutioTheme { MobileFilesScreen(running, onEvent = {}, onPlayMedia = {}, onCopyItem = {}) }
        }

        compose.onNodeWithContentDescription("Actions for ${sharedFolder.name}").performClick()
        compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).assertIsNotEnabled()
    }

    @Test
    fun aRejectedCopyNamesTheLimitAndPutIoOwnReason() {
        val start = FilesBrowserReducer.reduce(
            loaded(listOf(sharedVideo)),
            FilesBrowserEvent.Copy(FilesFolder.Root.id, sharedVideo.id, FilesFolder(destination.id, destination.name)),
        )
        val requestId = checkNotNull(start.effect).requestId
        val limited = FilesBrowserReducer.reduce(
            start.state,
            FilesBrowserEvent.CopyStarted(
                requestId, FilesRepositoryResult.Failure(rejected("SharedFileCloneConcurrentLimit")),
            ),
        ).state
        var state by mutableStateOf(limited)
        compose.setContent { PutioTheme { MobileFilesScreen(state, onEvent = {}, onPlayMedia = {}) } }

        compose.onNodeWithText("Couldn’t copy “Harbor film.mp4” to Sample folder.").assertIsDisplayed()
        compose.onNodeWithText(
            "You’re already copying as much as put.io allows at once. Try again when that copy finishes.",
        ).assertIsDisplayed()

        compose.runOnIdle {
            val copying = FilesBrowserReducer.reduce(
                start.state, FilesBrowserEvent.CopyStarted(requestId, FilesRepositoryResult.Success(FilesCopyId(42L))),
            )
            state = FilesBrowserReducer.reduce(
                copying.state,
                FilesBrowserEvent.CopyChecked(
                    checkNotNull(copying.effect).requestId,
                    FilesRepositoryResult.Success(FilesCopyProgress.Failed("File(s) size exceed disk limit.")),
                ),
            ).state
        }
        compose.onNodeWithText("File(s) size exceed disk limit.").assertIsDisplayed()
    }

    private fun loaded(items: List<FilesItem>): FilesBrowserState {
        val initial = FilesBrowserReducer.start()
        return FilesBrowserReducer.reduce(
            initial.state,
            FilesBrowserEvent.LoadSucceeded(checkNotNull(initial.effect).requestId, FilesPage(items, null)),
        ).state
    }

    private fun rejected(errorType: String) = FilesFailure.ApiRejected(
        400, errorType,
        PutioApiException(
            request = PutioRequestData("POST", "https://api.put.io/v2/sharing/clone"), resolvedStatusCode = 400,
            resolvedErrorType = errorType, envelope = PutioApiErrorEnvelope(errorType = errorType, statusCode = 400),
            responseBody = "{}", message = "Rejected",
        ),
    )

    private fun item(
        id: Long,
        name: String,
        type: PutioFileType,
        folderType: PutioFolderType = PutioFolderType.REGULAR,
        isShared: Boolean = false,
    ) = FilesItem(
        FilesItemId(id), FilesFolder.Root.id, name, type, 1L, "2026-09-06", isShared = isShared, folderType = folderType,
    )
}
