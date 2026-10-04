package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.downloads.DownloadArtifact
import io.putdotio.android.downloads.DownloadEntry
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.FakeDownloadEngine
import io.putdotio.android.downloads.FakeDownloadStore
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_ITEM_SHEET_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_REMOVE_CONFIRM_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_SELECTION_DELETE_TAG
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_SELECT_TAG
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.DefaultAccountSettingsPreferences
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.transfersState
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Downloads screen inside the real shell, with the real controller over a scripted store and engine. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileShellDownloadsTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = FakeDownloadStore()
    private val engine = FakeDownloadEngine()
    private val remoteDeletes = mutableListOf<FilesItemId>()
    private val filesEvents = mutableListOf<FilesBrowserEvent>()
    private val repository = object : StubFilesRepository() {
        override suspend fun loadFolder(folderId: FilesItemId) = error("Unexpected folder load")

        override suspend fun delete(itemId: FilesItemId, mode: FilesDeleteMode): PutioResult<FileDeleteResult> {
            remoteDeletes += itemId
            return super.delete(itemId, mode)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun bulkDeleteRemovesOnlyLocalCopiesAndNeverReachesThePutioOriginals() {
        val ids = listOf(10L, 11L, 12L)
        runBlocking { ids.forEach { store.upsert(completed(it)) } }
        val links = MobileDeepLinkRequests.None
        setShell(links)
        compose.runOnIdle { links.receive(MobileDeepLink.Downloads()) }

        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECT_TAG).performClick()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("3 selected").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_SELECTION_DELETE_TAG).performClick()
        compose.onNodeWithText("Delete 3 downloads?").assertIsDisplayed()
        compose.onNodeWithText(
            "The files stay in your put.io account. Only the copies on this device are removed.",
        ).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_REMOVE_CONFIRM_TAG).performClick()

        compose.waitUntil(TIMEOUT) { engine.removed.size == ids.size }
        compose.runOnIdle {
            assertEquals(ids.map(::FilesItemId).toSet(), engine.removed.toSet())
            assertTrue(remoteDeletes.isEmpty())
            assertTrue(filesEvents.none { it is FilesBrowserEvent.DeleteEvent })
        }
    }

    @Test
    fun aDownloadNotificationLinkOpensItsRow() {
        runBlocking {
            store.upsert(completed(10L))
            store.upsert(completed(11L).copy(name = "Tears.mkv"))
        }
        val links = MobileDeepLinkRequests.None
        setShell(links)

        compose.runOnIdle { links.receive(MobileDeepLink.Downloads(FilesItemId(11L))) }

        compose.onNodeWithTag(MOBILE_DOWNLOADS_ITEM_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithText("Tears.mkv", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, links.pending.value) }
    }

    @Test
    fun aDownloadStartedFromFilesCarriesTheConfirmedSubtitleSetting() {
        val video = FilesItem(
            id = FilesItemId(20L),
            parentId = FilesItemId(0L),
            name = "Sintel.mkv",
            type = PutioFileType.VIDEO,
            sizeBytes = 1_000L,
            createdAt = "2026-10-01T00:00:00Z",
        )
        setShell(filesState = filesState(video), showSubtitles = false)

        compose.onNodeWithContentDescription("Actions for Sintel.mkv").performClick()
        compose.onNodeWithText("Download to this device").performClick()

        compose.waitUntil(TIMEOUT) { engine.started.isNotEmpty() }
        compose.runOnIdle {
            val started = engine.started.single()
            assertEquals(true, started.subtitlesHidden)
            assertEquals(DownloadArtifact.HLS, started.artifact)
        }
    }

    private fun setShell(
        links: MobileDeepLinkRequests = MobileDeepLinkRequests.None,
        filesState: FilesBrowserState = filesState(),
        showSubtitles: Boolean = true,
    ) {
        val controller = DownloadsController(store, engine, scope)
        compose.setContent {
            PutioTheme {
                MobileShell(
                    filesState = filesState,
                    filesRepository = repository,
                    downloadsController = controller,
                    deepLinkRequests = links,
                    playbackPlayerFactory = NoAudioSessionFactory,
                    accountSettingsState = readyAccountSettingsState(
                        DefaultAccountSettingsPreferences.copy(showSubtitles = showSubtitles),
                    ),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersState = transfersState(TransfersContent.Empty),
                    account = MobileAccount(userId = 42L, username = "user", email = "user@example.com"),
                    playbackRepository = Converting,
                    sessionId = MobileAuthSessionId(1L),
                    onFilesEvent = {
                        filesEvents += it
                        true
                    },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
    }

    private fun completed(fileId: Long) = DownloadEntry(
        FilesItemId(fileId), "file-$fileId.mkv", PutioFileType.VIDEO, DownloadArtifact.HLS,
        DownloadStatus.Completed(1_024L), createdAt = fileId, accepted = true,
    )

    private fun filesState(vararg items: FilesItem): FilesBrowserState {
        val initial = FilesBrowserReducer.start()
        val requestId = (initial.effect as FilesBrowserEffect.LoadFolder).requestId
        return FilesBrowserReducer.reduce(
            initial.state,
            FilesBrowserEvent.LoadSucceeded(requestId, FilesPage(items.toList(), nextCursor = null)),
        ).state
    }

    private companion object {
        const val TIMEOUT = 5_000L
        val Converting = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
                PlaybackRepositoryResult.Success(PlaybackResolution.Conversion(PlaybackConversionState.Queued))

            override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
        }
    }
}
