package io.putdotio.android

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.search.MOBILE_SEARCH_FIELD_TAG
import io.putdotio.android.settings.DefaultAccountSettingsPreferences
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Hardware keys through the real input stages, on the production shell over a fixed Files listing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileKeyboardShortcutsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = mutableListOf<FilesBrowserEvent>()
    private var files by mutableStateOf(rootState())

    @Test
    fun f5AndCtrlRRefreshFilesWhenNoControlTakesThem() {
        setShell()

        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_F5)
        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_R, CTRL)
        // Alt and Meta belong to Android's own shortcuts; R alone is just a letter.
        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_R, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_R)

        compose.runOnIdle { assertEquals(listOf(FilesBrowserEvent.Refresh, FilesBrowserEvent.Refresh), events) }
    }

    @Test
    fun escapeAndBackspaceGoUpAFolderAndNeverLeaveTheApp() {
        setShell(filesState = nestedState())

        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_ESCAPE)
        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_DEL)

        compose.runOnIdle {
            assertEquals(listOf(FilesBrowserEvent.NavigateBack, FilesBrowserEvent.NavigateBack), events)
        }

        compose.runOnIdle { files = rootState() }
        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_ESCAPE)
        compose.runOnIdle { assertFalse(compose.activity.isFinishing) }
    }

    @Test
    fun deleteOnAFocusedRowMovesItToTrashWithoutTheSheetWhenTrashIsOn() {
        setShell()
        focusRow("notes.txt")

        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_FORWARD_DEL)

        compose.runOnIdle {
            assertEquals(listOf(FilesBrowserEvent.Delete(FilesFolder.Root.id, Notes.id, FilesDeleteMode.TRASH)), events)
        }
        compose.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test
    fun theKeyboardKeepsARowFocusedWhenTheFocusedOneLeavesTheListing() {
        setShell()
        focusRow("notes.txt")

        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_FORWARD_DEL)
        compose.runOnIdle { files = rootState(without = Notes) }

        compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(FILES_LIST))).assertCountEquals(1)
    }

    @Test
    fun deleteOnAFocusedRowConfirmsFirstWhenTrashIsOff() {
        setShell(trashEnabled = false)
        focusRow("notes.txt")

        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_FORWARD_DEL)

        compose.onNodeWithText("Rename").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(FilesBrowserEvent.Delete(FilesFolder.Root.id, Notes.id, FilesDeleteMode.PERMANENT)),
                events,
            )
        }
    }

    @Test
    fun deleteOnASharedRowDoesNothing() {
        setShell()
        focusRow("friend.mkv")

        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_FORWARD_DEL)

        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }
        compose.onNodeWithText("Make a copy").assertDoesNotExist()
    }

    @Test
    fun enterOpensTheFocusedFolderAndArrowsMoveBetweenRows() {
        setShell()
        focusRow("Shows")

        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("movie.mkv") and hasAnyAncestor(hasTestTag(FILES_LIST))).assertIsFocused()
        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_DPAD_UP)
        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_ENTER)

        compose.runOnIdle { assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.OpenFolder(Shows.id)), events) }
    }

    @Test
    fun ctrlFOpensSearchWithTheCursorInItsFieldWhichKeepsBackspace() {
        setShell()

        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_F, CTRL)

        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsFocused()
        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_DEL)
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsFocused()
        compose.onNode(hasText("Search") and hasAnyAncestor(hasTestTag(MOBILE_NAV_RAIL_TAG))).assertIsSelected()
    }

    @Test
    fun theShortcutsHelperListsTheKeysTheAppTakes() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val groups = mutableListOf<KeyboardShortcutGroup>()
            controller.get().onProvideKeyboardShortcuts(groups, null, -1)

            val listed = groups.associate { group ->
                group.label.toString() to group.items.map { "${it.label}:${it.keycode}:${it.modifiers}" }
            }
            assertEquals(
                listOf(
                    "Open the focused item:${KeyEvent.KEYCODE_ENTER}:0",
                    "Go up a folder or back:${KeyEvent.KEYCODE_DEL}:0",
                    "Go up a folder or back:${KeyEvent.KEYCODE_ESCAPE}:0",
                    "Search:${KeyEvent.KEYCODE_F}:${KeyEvent.META_CTRL_ON}",
                    "Refresh:${KeyEvent.KEYCODE_R}:${KeyEvent.META_CTRL_ON}",
                    "Refresh:${KeyEvent.KEYCODE_F5}:0",
                    "Move the focused item to trash, or delete it:${KeyEvent.KEYCODE_FORWARD_DEL}:0",
                ),
                listed["Files"],
            )
            assertEquals(
                listOf(
                    "Play or pause:${KeyEvent.KEYCODE_SPACE}:0",
                    "Back 10 seconds:${KeyEvent.KEYCODE_DPAD_LEFT}:0",
                    "Forward 10 seconds:${KeyEvent.KEYCODE_DPAD_RIGHT}:0",
                    "Go up a folder or back:${KeyEvent.KEYCODE_ESCAPE}:0",
                    "Go up a folder or back:${KeyEvent.KEYCODE_DEL}:0",
                ),
                listed["Player"],
            )
        }
    }

    @Test
    fun onlyBareKeysAndCtrlAreTaken() {
        val files = MobileShortcutScope.Files
        val ctrlShift = CTRL or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertEquals(MobileKeyCommand.Search, mobileKeyCommand(files, KeyEvent.KEYCODE_F, CTRL))
        assertNull(mobileKeyCommand(files, KeyEvent.KEYCODE_F, ctrlShift))
        assertNull(mobileKeyCommand(files, KeyEvent.KEYCODE_F, KeyEvent.META_META_ON))
        assertNull(mobileKeyCommand(files, KeyEvent.KEYCODE_DEL, KeyEvent.META_META_ON))
        assertNull(mobileKeyCommand(files, KeyEvent.KEYCODE_SPACE, 0))
        assertNull(mobileKeyCommand(files, KeyEvent.KEYCODE_ENTER, 0))
        assertNull(mobileKeyCommand(MobileShortcutScope.Player, KeyEvent.KEYCODE_SPACE, CTRL))
        val player = MobileShortcutScope.Player
        assertEquals(MobileKeyCommand.PlayPause, mobileKeyCommand(player, KeyEvent.KEYCODE_SPACE, 0))
    }

    private fun setShell(filesState: FilesBrowserState = rootState(), trashEnabled: Boolean = true) {
        files = filesState
        compose.setContent {
            PutioTheme {
                Box(Modifier.requiredSize(width = 900.dp, height = 700.dp)) {
                    MobileShell(
                        playbackPlayerFactory = NoAudioSessionFactory,
                        filesState = files,
                        accountSettingsState = readyAccountSettingsState(
                            DefaultAccountSettingsPreferences.copy(trashEnabled = trashEnabled),
                        ),
                        appConfigState = readyAndroidAppConfigState(),
                        account = Account,
                        playbackRepository = UnusedPlayback,
                        sessionId = Session,
                        onFilesEvent = {
                            events += it
                            true
                        },
                        onAccountSettingsEvent = {},
                        onPlaybackAuthenticationRequired = {},
                        onShareItem = {},
                        onSignOut = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** A navigation key leaves touch mode, as on a device; then the row takes focus as arrows would give it. */
    private fun focusRow(name: String) {
        compose.activity.pressHardwareKey(KeyEvent.KEYCODE_TAB)
        compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag(FILES_LIST))).requestFocus().assertIsFocused()
    }

    private companion object {
        const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val FILES_LIST = "mobile-files-list"
        val Account = MobileAccount(userId = 42L, username = "user", email = "user@example.com")
        val Session = MobileAuthSessionId(1L)
        val Shows = item(7L, "Shows", PutioFileType.FOLDER)
        val Movie = item(9L, "movie.mkv", PutioFileType.VIDEO)
        val Notes = item(11L, "notes.txt", PutioFileType.TEXT)
        val Friend = item(12L, "friend.mkv", PutioFileType.VIDEO).copy(isShared = true)

        fun item(id: Long, name: String, type: PutioFileType) = FilesItem(
            id = FilesItemId(id),
            parentId = FilesFolder.Root.id,
            name = name,
            type = type,
            sizeBytes = 42L,
            createdAt = "2026-08-29T00:00:00Z",
        )

        fun rootState(without: FilesItem? = null): FilesBrowserState {
            val initial = FilesBrowserReducer.start()
            val requestId = (initial.effect as FilesBrowserEffect.LoadFolder).requestId
            val items = listOf(Shows, Movie, Notes, Friend) - listOfNotNull(without).toSet()
            val page = FilesPage(items, nextCursor = null)
            return FilesBrowserReducer.reduce(initial.state, FilesBrowserEvent.LoadSucceeded(requestId, page)).state
        }

        fun nestedState(): FilesBrowserState =
            FilesBrowserReducer.reduce(rootState(), FilesBrowserEvent.OpenFolder(Shows.id)).state

        val UnusedPlayback = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
                PlaybackRepositoryResult.Success(PlaybackResolution.Conversion(PlaybackConversionState.Queued))

            override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
        }
    }
}
