package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderCreator
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveDestinationEvent
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.MOBILE_FILES_MOVE_NEW_FOLDER_TAG
import io.putdotio.android.files.MOBILE_NEW_FOLDER_CREATE_TAG
import io.putdotio.android.files.MOBILE_NEW_FOLDER_FIELD_TAG
import io.putdotio.android.files.MobileFilesMoveDestination
import io.putdotio.android.files.MobileMovePickerFolders
import io.putdotio.android.files.filesMoveDestinationFolder
import io.putdotio.android.files.filesMoveDestinationState
import io.putdotio.android.share.MobileShareProgress
import io.putdotio.android.share.MobileShareProgressDialog
import io.putdotio.android.share.MobileShareProgressState
import io.putdotio.android.sharing.shortRemainder
import io.putdotio.android.trash.MobileTrashScreen
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashState
import io.putdotio.android.trash.trashItem
import io.putdotio.sdk.files.PutioFileType
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileItemDialogsTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun clearShareProgress() = MobileShareProgress.end()

    @Test
    fun thePickerMakesAFolderWhereItIsAndOpensIt() {
        val series = folder(8L, "Series", FilesFolder.Root.id)
        val created = folder(11L, "Slow Horses", FilesFolder.Root.id)
        val state = filesMoveDestinationState(
            sourceItem = folder(7L, "Blue Lights", FilesItemId(3L)),
            sourceFolderId = FilesItemId(3L),
            stack = listOf(
                filesMoveDestinationFolder(FilesFolder.Root, FilesContent.Ready(listOf(series), FilesPaging.Complete)),
            ),
            nextRequestValue = 1L,
        )
        val events = mutableListOf<FilesMoveDestinationEvent>()
        val announced = mutableListOf<FilesItem>()
        val creator = object : FilesFolderCreator {
            override suspend fun createFolder(parentId: FilesItemId, name: String): PutioResult<FilesItem> =
                PutioResult.Success(created.copy(parentId = parentId, name = name))
        }
        compose.setContent {
            PutioTheme {
                MobileFilesMoveDestination(
                    state = state,
                    onEvent = { events += it },
                    onCancel = {},
                    onConfirm = {},
                    folders = MobileMovePickerFolders(creator, {}) { announced += it },
                )
            }
        }
        compose.onNodeWithText("Move 1 item").assertIsDisplayed()
        // The confirm names the folder on screen.
        compose.onNodeWithText("Move to Files").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_NEW_FOLDER_TAG).performClick()
        compose.onNodeWithTag(MOBILE_NEW_FOLDER_FIELD_TAG).performTextInput("Slow Horses")
        compose.onNodeWithTag(MOBILE_NEW_FOLDER_CREATE_TAG).performClick()
        compose.runOnIdle {
            assertEquals(listOf<FilesMoveDestinationEvent>(FilesMoveDestinationEvent.FolderCreated(created)), events)
            assertEquals(listOf(created), announced)
        }
    }

    @Test
    fun shareProgressShowsTheNameAndBytesAndCancels() {
        var cancelled = 0
        MobileShareProgress.start("Tears.of.Steel.2012.1080p.mkv")
        MobileShareProgress.progress(
            MobileShareProgressState("Tears.of.Steel.2012.1080p.mkv", 456_000_000L, 1_200_000_000L),
        )
        compose.setContent { PutioTheme { MobileShareProgressDialog(onCancel = { cancelled++ }) } }
        compose.onNodeWithText("Preparing to share").assertIsDisplayed()
        compose.onNodeWithText("Tears.of.Steel.2012.1080p.mkv downloads first, then the share sheet opens.")
            .assertIsDisplayed()
        compose.onNodeWithText("38% · 456 MB of 1.2 GB").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            assertNull(MobileShareProgress.state.value)
        }
        compose.onNodeWithText("Preparing to share").assertDoesNotExist()
        // Progress the export sends before the cancel reaches it doesn't reopen the dialog.
        MobileShareProgress.progress(
            MobileShareProgressState("Tears.of.Steel.2012.1080p.mkv", 500_000_000L, 1_200_000_000L),
        )
        compose.onNodeWithText("Preparing to share").assertDoesNotExist()
    }

    @Test
    fun aTrashRowRestoresFromItsOwnButton() {
        val item = trashItem()
        val events = mutableListOf<TrashEvent>()
        val state = TrashState(content = TrashContent.Loaded(listOf(item), null, 1, item.sizeBytes))
        compose.setContent { PutioTheme { MobileTrashScreen(state, { events += it; true }) } }
        compose.onNodeWithContentDescription("Restore ${item.name}").performClick()
        compose.runOnIdle { assertEquals(listOf<TrashEvent>(TrashEvent.SelectRestore(item.id)), events) }
    }

    @Test
    fun aLinksRemainderNamesItsTwoLargestUnits() {
        assertEquals("2d 14h", Duration.ofHours(62).plusMinutes(5).shortRemainder())
        assertEquals("3d", Duration.ofDays(3).shortRemainder())
        assertEquals("5h", Duration.ofHours(5).plusMinutes(59).shortRemainder())
        assertEquals("12m", Duration.ofMinutes(12).shortRemainder())
        assertEquals("1m", Duration.ofSeconds(20).shortRemainder())
    }

    private fun folder(id: Long, name: String, parent: FilesItemId) =
        FilesItem(FilesItemId(id), parent, name, PutioFileType.FOLDER, 0L, "2026-10-13T10:00:00")
}
