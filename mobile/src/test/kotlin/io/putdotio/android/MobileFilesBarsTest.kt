package io.putdotio.android

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderCreator
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.MOBILE_FILES_OVERFLOW_TAG
import io.putdotio.android.files.MOBILE_NEW_FOLDER_CREATE_TAG
import io.putdotio.android.files.MOBILE_NEW_FOLDER_FIELD_TAG
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.search.SearchScope
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Files root's search app bar and a folder's bar: where search goes and what New folder makes. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileFilesBarsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFolderSearchesInsideItself() {
        val scopes = mutableListOf<SearchScope?>()
        compose.setShell({ folderState() }, onScope = { scopes += it })

        compose.onNodeWithTag(MOBILE_FILES_SEARCH_TAG).performClick()

        assertEquals(listOf<SearchScope?>(SearchScope(FilesItemId(7L), "Blue Lights")), scopes)
    }

    @Test
    fun theRootSearchPillSearchesEverything() {
        val scopes = mutableListOf<SearchScope?>()
        compose.setShell({ rootState(folder) }, onScope = { scopes += it })

        compose.onNodeWithText("Search your files").performClick()

        assertEquals(listOf<SearchScope?>(null), scopes)
    }

    @Test
    fun newFolderFromTheOverflowCreatesInTheCurrentFolderAndRefreshesIt() {
        val created = mutableListOf<Pair<FilesItemId, String>>()
        val events = mutableListOf<FilesBrowserEvent>()
        val creator = object : FilesFolderCreator {
            override suspend fun createFolder(parentId: FilesItemId, name: String): PutioResult<FilesItem> {
                created += parentId to name
                return PutioResult.Success(file(31L, name, PutioFileType.FOLDER))
            }
        }
        compose.setShell({ folderState() }, creator = creator, onFilesEvent = { events += it })

        compose.onNodeWithTag(MOBILE_FILES_OVERFLOW_TAG).performClick()
        compose.onNodeWithText("New folder").performClick()
        compose.onNodeWithText("In Blue Lights").assertExists()
        compose.onNodeWithTag(MOBILE_NEW_FOLDER_FIELD_TAG).performTextInput("Shorts")
        compose.onNodeWithTag(MOBILE_NEW_FOLDER_CREATE_TAG).performClick()

        compose.waitUntil(TIMEOUT) { FilesBrowserEvent.Refresh in events }
        assertEquals(listOf(FilesItemId(7L) to "Shorts"), created)
        compose.onNodeWithTag(MOBILE_NEW_FOLDER_FIELD_TAG).assertDoesNotExist()
    }

    private fun ComposeContentTestRule.setShell(
        files: () -> FilesBrowserState,
        creator: FilesFolderCreator? = null,
        onScope: (SearchScope?) -> Unit = {},
        onFilesEvent: (FilesBrowserEvent) -> Unit = {},
    ) {
        setContent {
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = files(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = MobileAccount(userId = 42L, username = "user", email = "user@example.com"),
                    playbackRepository = NoPlayback,
                    sessionId = MobileAuthSessionId(1L),
                    onFilesEvent = {
                        onFilesEvent(it)
                        true
                    },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                    searchHistoryActions = MobileSearchHistoryActions(onScopeChanged = onScope),
                    folderCreator = creator,
                )
            }
        }
    }

    private companion object {
        const val TIMEOUT = 5_000L
        val folder = file(7L, "Blue Lights", PutioFileType.FOLDER)

        fun file(id: Long, name: String, type: PutioFileType) =
            FilesItem(FilesItemId(id), FilesFolder.Root.id, name, type, 1L, "2026-10-13T00:00:00")

        fun rootState(vararg items: FilesItem): FilesBrowserState {
            val start = FilesBrowserReducer.start()
            val request = (start.effect as FilesBrowserEffect.LoadFolder).requestId
            return FilesBrowserReducer.reduce(
                start.state,
                FilesBrowserEvent.LoadSucceeded(request, FilesPage(items.toList(), null)),
            ).state
        }

        fun folderState(): FilesBrowserState {
            val opened = FilesBrowserReducer.reduce(rootState(folder), FilesBrowserEvent.OpenFolder(folder.id))
            val request = (opened.effect as FilesBrowserEffect.LoadFolder).requestId
            val page = FilesPage(listOf(file(8L, "Blue.Lights.S04E01.mkv", PutioFileType.VIDEO)), null, total = 1)
            return FilesBrowserReducer.reduce(opened.state, FilesBrowserEvent.LoadSucceeded(request, page)).state
        }

        val NoPlayback = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
                error("No playback in these tests")

            override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
        }
    }
}
