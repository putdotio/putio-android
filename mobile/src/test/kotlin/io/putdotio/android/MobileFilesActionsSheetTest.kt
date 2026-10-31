package io.putdotio.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.MOBILE_FILES_COPY_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_DELETE_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_PLAY_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_RENAME_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_RENAME_FIELD_TAG
import io.putdotio.android.files.MOBILE_FILES_SELECT_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_VLC_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_WATCHED_ACTION_TAG
import io.putdotio.android.files.MobileFilesActions
import io.putdotio.android.files.MobileFilesItemActions
import io.putdotio.android.files.filesBrowserState
import io.putdotio.android.files.sharedByName
import io.putdotio.android.files.withNameSelected
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.files.MobileSearchResultActions
import io.putdotio.android.files.MobileSearchResultActionsHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.putdotio.android.files.FilesOpenOrigin

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileFilesActionsSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val started = FilesItem(
        FilesItemId(7L), FilesFolder.Root.id, "Blue.Lights.S04E01.1080p.HDTV.H264-ORGANiC.mkv",
        PutioFileType.VIDEO, 3_200_000_000L, "2026-10-13T10:00:00",
        playback = FilesPlaybackProgress(startFromSeconds = 600.0, durationSeconds = 3_000.0),
    )

    @Test
    fun aStartedVideoOffersResumeTheVideoRowsAndTheOwnerActions() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            PutioTheme {
                MobileFilesActions(
                    item = started,
                    folderId = FilesFolder.Root.id,
                    operation = FilesFolderOperation.Idle,
                    renameCompletion = null,
                    onEvent = {},
                    onDismiss = {},
                    confirmedTrashEnabled = true,
                    onMoveItem = { chosen += "move" },
                    onDownloadItem = { chosen += "download" },
                    onShareItem = { chosen += "share" },
                    onPublicLinkItem = { chosen += "link" },
                    onPlayItem = { chosen += "play" },
                    itemActions = MobileFilesItemActions(
                        onOpenInVlc = { chosen += "vlc" },
                        onSelect = { chosen += "select" },
                        onSetWatched = { _, watched -> chosen += "watched=$watched" },
                    ),
                )
            }
        }
        // The header keeps the whole name the list cut.
        compose.onNodeWithText(started.name).assertIsDisplayed()
        compose.onNodeWithText("3.2GB").assertIsDisplayed()
        compose.onNodeWithText("Resume").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onNodeWithText("Move to trash").performScrollTo().assertIsDisplayed()
        // A video part-way through can be marked watched; a finished one offers unwatched instead.
        compose.onNodeWithText("Mark as watched").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exclusive link").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_VLC_ACTION_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(MOBILE_FILES_SELECT_ACTION_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(MOBILE_FILES_WATCHED_ACTION_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(MOBILE_FILES_PLAY_ACTION_TAG).performClick()
        compose.runOnIdle { assertEquals(listOf("vlc", "select", "watched=true", "play"), chosen) }
    }

    @Test
    fun theHandleCarryingTheSheetsActionsIsALabelledTouchTarget() {
        compose.setContent {
            PutioTheme {
                MobileFilesActions(
                    started, FilesFolder.Root.id, FilesFolderOperation.Idle, null, {}, {},
                    confirmedTrashEnabled = true, onMoveItem = {},
                )
            }
        }
        // Android's minimum touch target; Material's sheet hangs expand and dismiss on the handle.
        compose.onNodeWithContentDescription("Drag handle")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun aFriendsFileKeepsTheTilesAndMakeACopyAndNamesTheSharer() {
        val shared = started.copy(id = FilesItemId(9L), name = "Caminandes - Llama Drama.mp4", isShared = true,
            playback = null)
        compose.setContent {
            PutioTheme {
                MobileFilesActions(
                    item = shared,
                    folderId = FilesItemId(5L),
                    operation = FilesFolderOperation.Idle,
                    renameCompletion = null,
                    onEvent = {},
                    onDismiss = {},
                    confirmedTrashEnabled = true,
                    onMoveItem = {},
                    onDownloadItem = {},
                    onShareItem = {},
                    onCopyItem = {},
                    onPublicLinkItem = {},
                    onPlayItem = {},
                    itemActions = MobileFilesItemActions(onOpenInVlc = {}, onSetWatched = { _, _ -> }),
                    sharedBy = "sam",
                )
            }
        }
        compose.onNodeWithText("Shared by sam").assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_VLC_ACTION_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_COPY_ACTION_TAG).assertIsDisplayed()
        val owner = listOf(
            MOBILE_FILES_WATCHED_ACTION_TAG,
            MOBILE_FILES_RENAME_ACTION_TAG,
            MOBILE_FILES_MOVE_ACTION_TAG,
            MOBILE_FILES_DELETE_ACTION_TAG,
        )
        owner.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test
    fun aSearchResultsSheetLeavesOutWhatActsOnAFolderListing() {
        var played = 0
        compose.setContent {
            PutioTheme {
                MobileSearchResultActionsHost(
                    results = listOf(started),
                    actions = MobileSearchResultActions(
                        itemActions = MobileFilesItemActions(onOpenInVlc = {}),
                        downloads = DownloadsState(),
                        onPlayItem = { played++ },
                        onDownloadItem = {},
                        onShareItem = {},
                        publicLinks = null,
                    ),
                ) { open -> Button(onClick = { open(started) }) { Text("Open actions") } }
            }
        }
        compose.onNodeWithText("Open actions").performClick()
        compose.onNodeWithTag(MOBILE_FILES_VLC_ACTION_TAG).assertIsDisplayed()
        listOf(MOBILE_FILES_RENAME_ACTION_TAG, MOBILE_FILES_MOVE_ACTION_TAG, MOBILE_FILES_DELETE_ACTION_TAG)
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithTag(MOBILE_FILES_PLAY_ACTION_TAG).performClick()
        compose.runOnIdle { assertEquals(1, played) }
    }

    @Test
    fun aVideoWithinTenSecondsOfItsEndReadsAsFinished() {
        var progress by mutableStateOf(FilesPlaybackProgress(startFromSeconds = 2_900.0, durationSeconds = 3_000.0))
        compose.setContent {
            PutioTheme {
                MobileFilesActions(
                    item = started.copy(playback = progress),
                    folderId = FilesFolder.Root.id,
                    operation = FilesFolderOperation.Idle,
                    renameCompletion = null,
                    onEvent = {},
                    onDismiss = {},
                    confirmedTrashEnabled = true,
                    onPlayItem = {},
                    itemActions = MobileFilesItemActions(onSetWatched = { _, _ -> }),
                )
            }
        }
        // The player still offers to resume 100 s before the end, so the sheet does too.
        compose.onNodeWithText("Resume").assertIsDisplayed()
        compose.onNodeWithText("Mark as watched").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { progress = FilesPlaybackProgress(startFromSeconds = 2_995.0, durationSeconds = 3_000.0) }
        compose.onNodeWithText("Play").assertIsDisplayed()
        compose.onNodeWithText("Mark as unwatched").performScrollTo().assertIsDisplayed()
        // Played with no known length still clears.
        compose.runOnIdle { progress = FilesPlaybackProgress(startFromSeconds = 600.0, durationSeconds = null) }
        compose.onNodeWithText("Mark as unwatched").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun renameSelectsTheNameUpToItsExtension() {
        compose.setContent {
            PutioTheme {
                MobileFilesActions(started, FilesFolder.Root.id, FilesFolderOperation.Idle, null, {}, {},
                    confirmedTrashEnabled = true)
            }
        }
        compose.onNodeWithTag(MOBILE_FILES_RENAME_ACTION_TAG).performScrollTo().performClick()
        val field = compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).fetchSemanticsNode()
        assertEquals(
            TextRange(0, "Blue.Lights.S04E01.1080p.HDTV.H264-ORGANiC".length),
            field.config[SemanticsProperties.TextSelectionRange],
        )
    }

    @Test
    fun theStemIsTheNameBeforeItsLastDotUnlessThatDotLeadsOrTheItemIsAFolder() {
        assertEquals(TextRange(0, 11), "Sintel.2010.mkv".withNameSelected(isFolder = false).selection)
        assertEquals(TextRange(0, 7), ".hidden".withNameSelected(isFolder = false).selection)
        assertEquals(TextRange(0, 6), "Series".withNameSelected(isFolder = false).selection)
        assertEquals(TextRange(0, 10), "Season 1.5".withNameSelected(isFolder = true).selection)
    }

    @Test
    fun theSharerIsTheFriendFolderAboveTheCurrentOne() {
        val friend = FilesItem(FilesItemId(5L), FilesItemId(4L), "sam", PutioFileType.FOLDER, 0L, "",
            folderType = PutioFolderType.SHARED_FRIEND)
        val sharedRoot = FilesItem(FilesItemId(4L), FilesFolder.Root.id, "Items shared with you", PutioFileType.FOLDER,
            0L, "", folderType = PutioFolderType.SHARED_ROOT)
        val nested = FilesItem(FilesItemId(6L), friend.id, "Shorts", PutioFileType.FOLDER, 0L, "", isShared = true)
        fun folder(item: FilesItem?, children: List<FilesItem>) = FilesFolderState(
            folder = item?.let { FilesFolder(it.id, it.name) } ?: FilesFolder.Root,
            content = FilesContent.Ready(children, FilesPaging.Complete),
        )
        val root = folder(null, listOf(sharedRoot))
        val inShared = folder(sharedRoot, listOf(friend))
        val inFriend = folder(friend, listOf(nested))
        val inNested = folder(nested, listOf(started.copy(isShared = true)))
        assertNull(filesBrowserState(listOf(root, inShared), 1L).sharedByName())
        assertEquals("sam", filesBrowserState(listOf(root, inShared, inFriend), 1L).sharedByName())
        assertEquals("sam", filesBrowserState(listOf(root, inShared, inFriend, inNested), 1L).sharedByName())
        // A folder opened from Search on top of sam's starts its own path.
        val opened = folder(FilesItem(FilesItemId(70L), FilesItemId(71L), "Mine", PutioFileType.FOLDER, 0L, ""),
            listOf(started)).copy(openedFrom = FilesOpenOrigin.SEARCH)
        assertNull(filesBrowserState(listOf(root, inShared, inFriend, opened), 1L).sharedByName())
    }
}
