package io.putdotio.android

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesSelection
import io.putdotio.android.files.copyForTest
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.MOBILE_FILES_SELECTION_BAR_TAG
import io.putdotio.android.files.MOBILE_FILES_SELECTION_DELETE_TAG
import io.putdotio.android.files.MOBILE_FILES_SELECTION_TOOLBAR_TAG
import io.putdotio.android.files.selectionActions
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.DefaultAccountSettingsPreferences
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Files selection as the shell draws it: Select from the sheet, the bar, the toolbar and Back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS-w400dp-h900dp")
class MobileFilesSelectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<FilesBrowserEvent>()
    private lateinit var backOwner: OnBackPressedDispatcherOwner

    @Test
    fun selectFromTheSheetReplacesTheFolderBarAndBackEndsItBeforeLeavingTheFolder() {
        setShell()

        selectFromTheSheet(SINTEL)
        compose.onNodeWithTag(MOBILE_FILES_SELECTION_BAR_TAG).assertExists()
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithText(SERIES.name).performClick()
        compose.onNodeWithText("2 selected").assertExists()
        compose.onNodeWithContentDescription("Select all").performClick()
        compose.onNodeWithText("3 selected").assertExists()
        assertTrue(events.none { it is FilesBrowserEvent.OpenFolder })

        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag(MOBILE_FILES_SELECTION_BAR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_FILES_SELECTION_TOOLBAR_TAG).assertDoesNotExist()
        compose.onNodeWithText("Blue Lights").assertExists()
        assertTrue(events.none { it == FilesBrowserEvent.NavigateBack })

        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(FilesBrowserEvent.NavigateBack, events.last()) }
    }

    @Test
    fun longPressStartsSelectionWithThatRowAndFoldersKeepTheirActionsInTheOverflow() {
        setShell()

        compose.onNodeWithText(SERIES.name).performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithText("Select").assertDoesNotExist()
        assertTrue(events.none { it is FilesBrowserEvent.OpenFolder })
        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithContentDescription("Actions for ${SERIES.name}").performClick()
        compose.onNodeWithText("Rename").assertExists()
        compose.onNodeWithTag(MOBILE_FILES_SELECTION_BAR_TAG).assertDoesNotExist()
    }

    @Test
    fun trashSendsEveryCheckedRowInOneDeleteAndEndsTheSelection() {
        setShell()

        selectFromTheSheet(SINTEL)
        compose.onNodeWithText(SERIES.name).performClick()
        compose.onNodeWithContentDescription("Move to trash").performClick()

        compose.runOnIdle {
            val delete = events.filterIsInstance<FilesBrowserEvent.Delete>().single()
            // In the listing's order, whatever order they were checked in.
            val expected = FilesBrowserEvent.Delete(FOLDER.id, listOf(SERIES.id, SINTEL.id), FilesDeleteMode.TRASH)
            assertEquals(expected, delete)
        }
        compose.onNodeWithTag(MOBILE_FILES_SELECTION_BAR_TAG).assertDoesNotExist()
    }

    @Test
    fun withTrashOffTheToolbarDeletesPermanentlyAfterAConfirmThatNamesTheCount() {
        setShell(trashEnabled = false)

        selectFromTheSheet(SINTEL)
        compose.onNodeWithText(FINISHED.name).performClick()
        compose.onNodeWithTag(MOBILE_FILES_SELECTION_DELETE_TAG).performClick()
        compose.onNodeWithText("Delete 2 items permanently?").assertExists()
        assertTrue(events.none { it is FilesBrowserEvent.Delete })
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()

        compose.runOnIdle {
            assertEquals(
                FilesBrowserEvent.Delete(FOLDER.id, listOf(SINTEL.id, FINISHED.id), FilesDeleteMode.PERMANENT),
                events.filterIsInstance<FilesBrowserEvent.Delete>().single(),
            )
        }
    }

    @Test
    fun eachToolbarActionShowsOnlyWhenEverySelectedRowAllowsIt() {
        val shared = SINTEL.copy(id = FilesItemId(12L), isShared = true)
        val unknownLength = SINTEL.copy(id = FilesItemId(13L), playback = null)
        fun actions(vararg items: FilesItem) =
            items.toList().selectionActions(canMove = true, canDownload = true, canSetWatched = true)

        val mixed = actions(SERIES, SINTEL)
        assertTrue(mixed.move && mixed.delete)
        assertFalse(mixed.download)
        assertNull(mixed.watched)

        val videos = actions(SINTEL, FINISHED)
        assertTrue(videos.download)
        // One is finished and one isn't: Watched saves both ends.
        assertEquals(true, videos.watched)
        assertEquals(false, actions(FINISHED, FINISHED.copy(id = FilesItemId(14L))).watched)
        // Watched is a saved end, so every file needs a known length.
        assertNull(actions(SINTEL, unknownLength).watched)

        val withShared = actions(SINTEL, shared)
        assertFalse(withShared.move || withShared.delete)
        assertNull(withShared.watched)
        assertTrue(withShared.download)

        // A friend's folder beside an owned file shares no action, so the toolbar shows none.
        assertFalse(actions(SERIES.copy(id = FilesItemId(15L), isShared = true), SINTEL).any)

        val offline = listOf(SINTEL).selectionActions(canMove = false, canDownload = false, canSetWatched = false)
        assertFalse(offline.move || offline.download)
        assertNull(offline.watched)
    }

    @Test
    fun theSelectionEndsInAnotherFolderAndForgetsRowsThatLeft() {
        val folder = folderState()
        val selection = FilesSelection()
        selection.start(folder, SINTEL)
        selection.toggle(folder, FINISHED)

        // A row leaves the listing, then comes back: it is not checked again.
        val without = folder.withItems(SERIES, SINTEL)
        selection.reconcile(without)
        selection.reconcile(folder)
        assertEquals(listOf(SINTEL), selection.selected(folder))

        // The same folder read afresh, as an outside open that replaces it does, ends it too.
        val again = FilesSelection()
        again.start(folder, SINTEL)
        again.reconcile(folder.copyForTest(stack = folder.stack.dropLast(1) + folder.current.copy(
            content = FilesContent.Loading(FilesRequestId(99L)),
        )))
        assertFalse(again.isActive(folder))

        // Another folder ends it, so coming back shows none.
        val root = FilesBrowserReducer.reduce(folder, FilesBrowserEvent.NavigateBack).state
        selection.reconcile(root)
        assertFalse(selection.isActive(folder))
        assertTrue(selection.selected(folder).isEmpty())
    }

    @Test
    fun theSelectionSurvivesASavedStateRestore() {
        val folder = folderState()
        val selection = FilesSelection()
        selection.start(folder, SINTEL)
        selection.toggle(folder, FINISHED)

        val saved = with(FilesSelection.Saver) { SaverScope { true }.save(selection) }
        val restored = FilesSelection.Saver.restore(checkNotNull(saved))

        assertEquals(listOf(SINTEL, FINISHED), restored?.selected(folder))
        assertTrue(restored?.isActive(folder) == true)
    }

    @Test
    @Config(qualifiers = "en-rUS-w1280dp-h800dp")
    fun onATabletTheDetailPaneShowsNoFileWhileRowsAreSelected() {
        setShell()

        compose.onNodeWithText(SINTEL.name).performClick()
        compose.onNodeWithText("No file selected.").assertDoesNotExist()
        selectFromTheSheet(FINISHED)

        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithText("No file selected.").assertExists()
    }

    private fun selectFromTheSheet(item: FilesItem) {
        compose.onNodeWithContentDescription("Actions for ${item.name}").performClick()
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithText("Select").assertDoesNotExist()
    }

    private fun setShell(trashEnabled: Boolean = true) {
        compose.setContent {
            val owner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            SideEffect { backOwner = owner }
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = folderState(),
                    accountSettingsState = readyAccountSettingsState(
                        DefaultAccountSettingsPreferences.copy(trashEnabled = trashEnabled),
                    ),
                    appConfigState = readyAndroidAppConfigState(),
                    account = MobileAccount(userId = 42L, username = "user", email = "user@example.com"),
                    playbackRepository = NoPlayback,
                    sessionId = MobileAuthSessionId(1L),
                    onFilesEvent = {
                        events += it
                        true
                    },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
    }

    private companion object {
        val FOLDER = file(7L, "Blue Lights", PutioFileType.FOLDER, parent = FilesFolder.Root.id)
        val SERIES = file(8L, "Series", PutioFileType.FOLDER)
        val SINTEL = file(9L, "Sintel.2010.1080p.mkv", PutioFileType.VIDEO, FilesPlaybackProgress(0.0, 600.0))
        val FINISHED = file(10L, "Big_Buck_Bunny.mp4", PutioFileType.VIDEO, FilesPlaybackProgress(600.0, 600.0))

        fun file(
            id: Long,
            name: String,
            type: PutioFileType,
            playback: FilesPlaybackProgress? = null,
            parent: FilesItemId = FilesItemId(7L),
        ) = FilesItem(FilesItemId(id), parent, name, type, 1_000_000L, "2026-10-13T00:00:00", playback = playback)

        fun FilesBrowserState.withItems(vararg items: FilesItem): FilesBrowserState =
            copyForTest(
                stack = stack.dropLast(1) +
                    current.copy(content = FilesContent.Ready(items.toList(), FilesPaging.Complete)),
            )

        fun folderState(): FilesBrowserState {
            val start = FilesBrowserReducer.start()
            val root = FilesBrowserReducer.reduce(start.state, FilesBrowserEvent.LoadSucceeded(
                (start.effect as FilesBrowserEffect.LoadFolder).requestId, FilesPage(listOf(FOLDER), null),
            )).state
            val opened = FilesBrowserReducer.reduce(root, FilesBrowserEvent.OpenFolder(FOLDER.id))
            val page = FilesPage(listOf(SERIES, SINTEL, FINISHED), null, parent = FOLDER, total = 3)
            return FilesBrowserReducer.reduce(opened.state, FilesBrowserEvent.LoadSucceeded(
                (opened.effect as FilesBrowserEffect.LoadFolder).requestId, page,
            )).state
        }

        val NoPlayback = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
                error("No playback in these tests")

            override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
        }
    }
}
