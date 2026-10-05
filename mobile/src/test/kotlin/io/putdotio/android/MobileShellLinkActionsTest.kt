package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_LIST_TAG
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import io.putdotio.android.transfers.MOBILE_TRANSFER_ADD_FIELD_TAG
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.transfersState
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PutioFileType
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Links that shortcuts and notification actions open, routed by the real shell. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileShellLinkActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = FakeDownloadStore()
    private val resolved = CopyOnWriteArrayList<PlaybackTarget>()
    private val links = MobileDeepLinkRequests.None

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun playFromANotificationPlaysTheCopyOnThisDevice() {
        runBlocking { store.upsert(row(10L, DownloadStatus.Completed(1_024L))) }
        setShell()

        compose.runOnIdle { links.receive(MobileDeepLink.Downloads(FilesItemId(10L), play = true, userId = USER)) }

        compose.waitUntil(TIMEOUT) { resolved.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(FilesItemId(10L), resolved.single().fileId)
            assertEquals("file-10.mkv", resolved.single().name)
            assertNull(links.pending.value)
        }
    }

    @Test
    fun playWithoutACopyOnThisDeviceOpensTheRowInstead() {
        runBlocking { store.upsert(row(11L, DownloadStatus.Missing)) }
        setShell()

        compose.runOnIdle { links.receive(MobileDeepLink.Downloads(FilesItemId(11L), play = true, userId = USER)) }

        compose.onNodeWithTag(MOBILE_DOWNLOADS_ITEM_SHEET_TAG).assertIsDisplayed()
        compose.runOnIdle { assertTrue(resolved.isEmpty()) }
    }

    @Test
    fun anotherAccountsNotificationOpensNothing() {
        runBlocking { store.upsert(row(10L, DownloadStatus.Completed(1_024L))) }
        setShell()

        compose.runOnIdle { links.receive(MobileDeepLink.Downloads(FilesItemId(10L), play = true, userId = OTHER)) }

        compose.waitForIdle()
        compose.runOnIdle {
            // Dropped, not left pending for a later account, and nothing played or opened.
            assertNull(links.pending.value)
            assertTrue(resolved.isEmpty())
        }
        compose.onNodeWithTag(MOBILE_DOWNLOADS_LIST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_DOWNLOADS_ITEM_SHEET_TAG).assertDoesNotExist()
    }

    @Test
    fun theAddTransferShortcutOpensAnEmptySheetAndAddsNothing() {
        val draft = MobileTransferDraft()
        setShell(draft)

        compose.runOnIdle { links.receive(MobileDeepLink.AddTransfer) }

        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("", draft.state.value.input)
            assertTrue(draft.state.value.open)
            assertNull(links.pending.value)
        }
    }

    private fun setShell(draft: MobileTransferDraft = MobileTransferDraft()) {
        val controller = DownloadsController(store, FakeDownloadEngine(), scope)
        compose.setContent {
            PutioTheme {
                MobileShell(
                    filesState = FilesBrowserReducer.start().state,
                    downloadsController = controller,
                    deepLinkRequests = links,
                    playbackPlayerFactory = NoAudioSessionFactory,
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersState = transfersState(TransfersContent.Empty),
                    account = MobileAccount(userId = USER, username = "user", email = "user@example.com"),
                    playbackRepository = recording,
                    sessionId = MobileAuthSessionId(1L),
                    onFilesEvent = { true },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                    transferDraft = draft,
                )
            }
        }
    }

    private val recording = object : PlaybackRepository {
        override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
            resolved += target
            return PlaybackRepositoryResult.Success(PlaybackResolution.Conversion(PlaybackConversionState.Queued))
        }

        override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
    }

    private fun row(fileId: Long, status: DownloadStatus) = DownloadEntry(
        FilesItemId(fileId), "file-$fileId.mkv", PutioFileType.VIDEO, DownloadArtifact.HLS, status,
        createdAt = fileId, accepted = true,
    )

    private companion object {
        const val TIMEOUT = 5_000L
        const val USER = 42L
        const val OTHER = 43L
    }
}
