package io.putdotio.android

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.MOBILE_FILES_DETAIL_PRIMARY_TAG
import io.putdotio.android.files.MOBILE_FILES_DETAIL_SHARE_TAG
import io.putdotio.android.files.MOBILE_FILES_DETAIL_TAG
import io.putdotio.android.files.MOBILE_FILES_LIST_PANE_TAG
import io.putdotio.android.files.MobileFileOpener
import io.putdotio.android.files.MobileFilesRoute
import io.putdotio.android.files.filesBrowserState
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.android.viewer.MOBILE_FILE_VIEWER_POSITION_TAG
import io.putdotio.android.viewer.MOBILE_FILE_VIEWER_TAG
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileFilesListDetailTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val played = mutableListOf<FilesItem>()
    private val shared = mutableListOf<FilesItem>()

    @Test
    fun aWideWindowSelectsATappedFileIntoTheDetailPaneWhosePlayAndShareRunTheFile() {
        setRoute(width = 1184.dp)

        compose.onNodeWithTag(MOBILE_FILES_DETAIL_TAG).assertExists()
        compose.onNode(hasText("Blue Lights") and hasAnyAncestor(hasTestTag(MOBILE_FILES_LIST_PANE_TAG)))
            .assertExists()

        compose.onNode(hasText(EPISODE.name) and hasAnyAncestor(hasTestTag(MOBILE_FILES_LIST_PANE_TAG)))
            .performClick()

        // A tap only selects: nothing plays until the pane's Play.
        compose.runOnIdle { assertTrue(played.isEmpty()) }
        compose.onNode(hasText(EPISODE.name) and hasAnyAncestor(hasTestTag(MOBILE_FILES_DETAIL_TAG))).assertExists()
        compose.onNodeWithText("Resume from 18:42").assertExists()
        compose.onNodeWithText("49:49").assertExists()
        compose.onNodeWithText("Series / Blue Lights").assertExists()
        compose.onNode(hasText(EPISODE.name) and hasAnyAncestor(hasTestTag(MOBILE_FILES_LIST_PANE_TAG)))
            .assertIsSelected()

        compose.onNodeWithTag(MOBILE_FILES_DETAIL_PRIMARY_TAG).performClick()
        compose.onNodeWithTag(MOBILE_FILES_DETAIL_SHARE_TAG).performClick()
        compose.runOnIdle {
            assertEquals(listOf(EPISODE), played)
            assertEquals(listOf(EPISODE), shared)
        }
    }

    @Test
    fun aNarrowWindowKeepsTheListAloneAndATapPlays() {
        setRoute(width = 400.dp, wide = false)

        compose.onNodeWithTag(MOBILE_FILES_DETAIL_TAG).assertDoesNotExist()
        compose.onNodeWithText(EPISODE.name).performClick()

        compose.runOnIdle { assertEquals(listOf(EPISODE), played) }
    }

    @Test
    fun anImageOpensTheViewerOverTheFoldersImagesInListingOrder() {
        setRoute(width = 400.dp, wide = false)

        compose.onNodeWithText(SECOND_IMAGE.name).performClick()

        compose.onNodeWithTag(MOBILE_FILE_VIEWER_TAG).assertExists()
        compose.onNodeWithTag(MOBILE_FILE_VIEWER_POSITION_TAG).assertExists()
        compose.onNode(hasContentDescription("2 of 2")).assertExists()
    }

    @Test
    fun aTypeNoViewerTakesGoesToAnotherAppThroughTheDocumentsProviderWithOnlyAReadGrant() {
        setRoute(width = 400.dp, wide = false)

        compose.onNodeWithText(ARCHIVE.name).performClick()

        compose.runOnIdle {
            val started = shadowOf(compose.activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, started.action)
            assertEquals("application/zip", started.type)
            assertEquals(
                "content://${compose.activity.packageName}.documents/document/42%3A${ARCHIVE.id.value}",
                started.data.toString(),
            )
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(0, started.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        compose.onNodeWithTag(MOBILE_FILE_VIEWER_TAG).assertDoesNotExist()
    }

    @Test
    fun theShellTurnsFilesIntoListAndDetailAt840dpWithTheFolderBarOverTheList() {
        var width by mutableStateOf(839.dp)
        compose.setContent {
            PutioTheme {
                Box(Modifier.requiredSize(width = width, height = 800.dp)) {
                    MobileShell(
                        playbackPlayerFactory = NoAudioSessionFactory,
                        filesState = blueLights(),
                        accountSettingsState = readyAccountSettingsState(),
                        appConfigState = readyAndroidAppConfigState(),
                        account = MobileAccount(userId = 42L, username = "user", email = "user@example.com"),
                        playbackRepository = unusedPlayback,
                        sessionId = MobileAuthSessionId(1L),
                        onFilesEvent = { true },
                        onAccountSettingsEvent = {},
                        onPlaybackAuthenticationRequired = {},
                        onSignOut = {},
                    )
                }
            }
        }
        compose.onNodeWithTag(MOBILE_FILES_LIST_PANE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_FILES_DETAIL_TAG).assertDoesNotExist()

        compose.runOnIdle { width = 840.dp }

        compose.onNode(hasText("Blue Lights") and hasAnyAncestor(hasTestTag(MOBILE_FILES_LIST_PANE_TAG))).assertExists()
        compose.onNodeWithTag(MOBILE_FILES_DETAIL_TAG).assertExists()
    }

    private val unusedPlayback = object : PlaybackRepository {
        override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(PlaybackResolution.Conversion(PlaybackConversionState.Queued))

        override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
    }

    private fun setRoute(width: Dp, wide: Boolean = true) {
        compose.setContent {
            PutioTheme {
                Box(Modifier.requiredSize(width = width, height = 700.dp)) {
                    MobileFilesRoute(
                        state = blueLights(),
                        repository = null,
                        onEvent = { true },
                        onPlayMedia = { played += it },
                        confirmedTrashEnabled = true,
                        onAuthenticationRequired = {},
                        onShareItem = { shared += it },
                        listPaneTopBar = if (wide) { { Text("Blue Lights") } } else null,
                        fileOpener = MobileFileOpener(userId = 42L),
                    )
                }
            }
        }
    }

    private fun blueLights(): FilesBrowserState {
        val series = FilesFolder(FilesItemId(10L), "Series")
        val blueLights = FilesFolder(FilesItemId(11L), "Blue Lights")
        val items = listOf(EPISODE, FIRST_IMAGE, ARCHIVE, SECOND_IMAGE)
        return filesBrowserState(
            stack = listOf(
                FilesFolderState(FilesFolder.Root, FilesContent.Empty(FilesPaging.Complete)),
                FilesFolderState(series, FilesContent.Empty(FilesPaging.Complete)),
                FilesFolderState(blueLights, FilesContent.Ready(items, FilesPaging.Complete)),
            ),
            nextRequestValue = 2L,
        )
    }

    private companion object {
        val EPISODE = file(1L, "Blue.Lights.S04E01.1080p.HDTV.H264-ORGANiC.mkv", PutioFileType.VIDEO)
            .copy(playback = FilesPlaybackProgress(startFromSeconds = 1_122.0, durationSeconds = 2_989.0))
        val FIRST_IMAGE = file(2L, "Alfama at dusk.jpg", PutioFileType.IMAGE)
        val ARCHIVE = file(3L, "Blue.Lights.S04.Extras.zip", PutioFileType.ARCHIVE)
        val SECOND_IMAGE = file(4L, "Belém at noon.jpg", PutioFileType.IMAGE)

        fun file(id: Long, name: String, type: PutioFileType) = FilesItem(
            id = FilesItemId(id),
            parentId = FilesItemId(11L),
            name = name,
            type = type,
            sizeBytes = 3_200_000_000L,
            createdAt = "2026-10-13T10:00:00",
        )
    }
}
