package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderCreator
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveOutcome
import io.putdotio.android.files.FilesMoveStatus
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.FilesSelection
import io.putdotio.android.files.MOBILE_FILES_SELECTION_TOOLBAR_TAG
import io.putdotio.android.files.MOBILE_FILES_SNACKBAR_TAG
import io.putdotio.android.files.MOBILE_NEW_FOLDER_CREATE_TAG
import io.putdotio.android.files.MOBILE_NEW_FOLDER_FIELD_TAG
import io.putdotio.android.files.MobileFilesScreen
import io.putdotio.android.files.copyForTest
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import io.putdotio.sdk.files.PutioFileType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS-w400dp-h640dp")
class MobileFilesMoveBannerAndNewFolderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun moveSuccessShowsViaSnackbarAndDoesNotOverlapSelectionToolbar() {
        val moved = item(1L, "Moved.mkv", PutioFileType.VIDEO)
        val remaining = item(2L, "Remaining.mkv", PutioFileType.VIDEO)
        val outcome = FilesMoveOutcome(
            requestId = FilesRequestId(9L),
            intent = FilesFolderOperationIntent.Move(moved.id, FilesItemId(99L)),
            itemName = moved.name,
            status = FilesMoveStatus.MOVED,
        )
        val loaded = loadedState(listOf(remaining))
        val state = loaded.copyForTest(stack = listOf(loaded.current.copy(moveOutcome = outcome)))
        val selection = FilesSelection()
        val message = "“${moved.name}” is in the selected folder."

        compose.setBuiltContent {
            PutioTheme {
                MobileFilesScreen(
                    state = state,
                    onEvent = {},
                    onPlayMedia = {},
                    confirmedTrashEnabled = true,
                    selection = selection,
                )
            }
        }

        compose.onNode(hasText(message) and hasAnyAncestor(hasTestTag(MOBILE_FILES_SNACKBAR_TAG)))
            .assertIsDisplayed()

        compose.onNodeWithText(remaining.name).performTouchInput { longClick() }
        compose.onNodeWithTag(MOBILE_FILES_SELECTION_TOOLBAR_TAG).assertIsDisplayed()
        compose.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun moveSuccessIsNotReannouncedAfterOpeningChildFolderAndNavigatingBack() {
        val moved = item(1L, "Moved.mkv", PutioFileType.VIDEO)
        val childFolder = item(2L, "Child", PutioFileType.FOLDER)
        val outcome = FilesMoveOutcome(
            requestId = FilesRequestId(9L),
            intent = FilesFolderOperationIntent.Move(moved.id, FilesItemId(99L)),
            itemName = moved.name,
            status = FilesMoveStatus.MOVED,
        )
        val rootLoaded = loadedState(listOf(childFolder))
        val rootWithOutcome = rootLoaded.copyForTest(stack = listOf(rootLoaded.current.copy(moveOutcome = outcome)))
        var state by mutableStateOf(rootWithOutcome)
        val message = "“${moved.name}” is in the selected folder."

        compose.setBuiltContent {
            PutioTheme {
                MobileFilesScreen(
                    state = state,
                    onEvent = {},
                    onPlayMedia = {},
                    confirmedTrashEnabled = true,
                )
            }
        }

        compose.onNode(hasText(message) and hasAnyAncestor(hasTestTag(MOBILE_FILES_SNACKBAR_TAG)))
            .assertIsDisplayed()

        val opened = FilesBrowserReducer.reduce(state, FilesBrowserEvent.OpenFolder(childFolder.id))
        val childLoaded = FilesBrowserReducer.reduce(
            opened.state,
            FilesBrowserEvent.LoadSucceeded(checkNotNull(opened.effect).requestId, FilesPage(emptyList(), null)),
        ).state
        state = childLoaded
        compose.waitForIdle()

        state = FilesBrowserReducer.reduce(state, FilesBrowserEvent.NavigateBack).state
        compose.waitForIdle()

        compose.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun newFolderCreatedAtTopOfNewestFirstListIsImmediatelyVisible() {
        val existing = (1L..20L).map { item(it, "Existing $it", PutioFileType.FOLDER) }
        var state by mutableStateOf(loadedState(existing))
        var createdItem: FilesItem? = null
        val creator = object : FilesFolderCreator {
            override suspend fun createFolder(parentId: FilesItemId, name: String): PutioResult<FilesItem> {
                val created = item(500L, name, PutioFileType.FOLDER, parentId = parentId)
                createdItem = created
                return PutioResult.Success(created)
            }
        }

        compose.setBuiltContent {
            PutioTheme {
                MobileShell(
                    filesStates = rememberUpdatedState(state),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = MobileAccount(userId = 42L, username = "user", email = "user@example.com"),
                    playbackRepository = NoPlayback,
                    playbackPlayerFactory = NoAudioSessionFactory,
                    sessionId = MobileAuthSessionId(1L),
                    onFilesEvent = { event ->
                        val next = FilesBrowserReducer.reduce(state, event)
                        state = next.state
                        if (event == FilesBrowserEvent.Refresh && next.effect != null) {
                            val updated = listOfNotNull(createdItem) + existing
                            state = FilesBrowserReducer.reduce(
                                state,
                                FilesBrowserEvent.LoadSucceeded(next.effect!!.requestId, FilesPage(updated, null)),
                            ).state
                        }
                        next.consumed
                    },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                    folderCreator = creator,
                )
            }
        }

        compose.onNodeWithText("Existing 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("New folder").performClick()
        compose.onNodeWithTag(MOBILE_NEW_FOLDER_FIELD_TAG).performTextInput("Brand New Folder")
        compose.onNodeWithTag(MOBILE_NEW_FOLDER_CREATE_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Brand New Folder").assertIsDisplayed()
    }

    private fun loadedState(items: List<FilesItem>): FilesBrowserState {
        val started = FilesBrowserReducer.start()
        return FilesBrowserReducer.reduce(
            started.state,
            FilesBrowserEvent.LoadSucceeded(checkNotNull(started.effect).requestId, FilesPage(items, null)),
        ).state
    }

    private fun item(
        id: Long,
        name: String,
        type: PutioFileType,
        parentId: FilesItemId = FilesFolder.Root.id,
    ) = FilesItem(FilesItemId(id), parentId, name, type, 1L, "2026-09-06")

    private object NoPlayback : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> = error("Unexpected playback")

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
    }
}
