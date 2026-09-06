package io.putdotio.android

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferFileId
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransferNavigation
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersReducer
import io.putdotio.android.transfers.TransfersState
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class FilesDeleteNavigationUiProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Synthetic shell navigation proof requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.delete.ui.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun rejectedSearchAndTransferNavigationPreserveRecovery() {
        // Controlled reducers and file resolution only; this activity never initializes auth or an SDK client.
        val preview = DeleteNavigationPreview()
        compose.setContent {
            PutioTheme {
                Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    color = MaterialTheme.colorScheme.background) {
                    MobileShell(
                        filesState = preview.files, accountSettingsState = preview.settings,
                        appConfigState = preview.appConfig, transfersState = preview.transfers,
                        account = MobileAccount(42L, "Synthetic proof", "synthetic@example.invalid"),
                        sessionId = preview.session, transfersSessionId = preview.session,
                        playbackRepository = NoNavigationPlayback,
                        onFilesEvent = preview::filesEvent, onTransfersEvent = preview::transfersEvent,
                        resolveTransferFile = { FilesRepositoryResult.Success(preview.resolved) },
                        contentNavigation = preview.searchResults,
                        onAccountSettingsEvent = { error("Unexpected settings mutation") },
                        onPlaybackAuthenticationRequired = { error("Unexpected authentication request") },
                        onSignOut = { error("Unexpected sign out") },
                    )
                }
            }
        }
        rejectSearch(preview)
        rejectTransfer(preview)
        navigate("Files")
        compose.onNodeWithText(preview.item.name).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_OPERATION_RETRY_TAG).performClick()
        compose.runOnIdle { preview.reconcileDelete() }
        navigate("Transfers")
        compose.onNodeWithText("Open file").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { preview.transferEvents.any { it is TransfersEvent.OpenSucceeded } }
        selected("Files")
        compose.runOnIdle {
            assertEquals(1, preview.transferEvents.count { it is TransfersEvent.OpenFailed })
            assertEquals(1, preview.transferEvents.count { it is TransfersEvent.OpenSucceeded })
            assertEquals(2, preview.transferEvents.count { it is TransfersEvent.Open })
            assertEquals(1, preview.effects.count { it is FilesBrowserEffect.Delete })
            assertEquals(TransferNavigation.Idle, preview.transfers.navigation)
            assertEquals(preview.resolved.id, preview.files.current.folder.id)
            preview.deliveries.close()
        }
    }

    private fun rejectSearch(preview: DeleteNavigationPreview) {
        navigate("Search")
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsDisplayed()
        val retained = preview.files
        compose.runOnIdle { assertTrue(preview.deliveries.trySend(preview.resolved).isSuccess) }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.mobile_navigation_blocked)).assertIsDisplayed()
        deleteProofScreenshot("synthetic-navigation")
        compose.onNodeWithText("OK").performClick()
        selected("Search")
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle { assertSame(retained, preview.files) }
    }

    private fun rejectTransfer(preview: DeleteNavigationPreview) {
        navigate("Transfers")
        val retained = preview.files
        compose.onNodeWithText("Open file").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { preview.transferEvents.any { it is TransfersEvent.OpenFailed } }
        compose.runOnIdle {
            assertSame(retained, preview.files)
            assertEquals(TransferNavigation.Failed(FilesFailure.NavigationBlocked), preview.transfers.navigation)
            assertTrue(preview.transferEvents.none { it is TransfersEvent.OpenSucceeded })
        }
        compose.onNodeWithText("OK").performClick()
        selected("Transfers")
        compose.onNodeWithText("Open file").assertIsEnabled()
    }

    private fun navigate(name: String) {
        compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).performClick()
        selected(name)
    }
    private fun selected(name: String) {
        compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).assertIsSelected()
    }
}

private class DeleteNavigationPreview {
    val session = MobileAuthSessionId(42)
    val item = FilesItem(FilesItemId(13), FilesItemId(12), "A Action été", PutioFileType.FOLDER, 0, "2026-09-06")
    val resolved = item.copy(id = FilesItemId(19), parentId = FilesFolder.Root.id, name = "Resolved folder")
    private val source = item.copy(id = FilesItemId(12), parentId = FilesFolder.Root.id, name = "Delete recovery preview")
    val settings = AccountSettingsState(AccountSettingsContent.Ready(AccountSettingsPreferences(false, true, false, false)),
        AccountSettingsMutation.Idle, 1)
    val appConfig = AndroidAppConfigState(AndroidAppConfigContent.Ready(AndroidAppConfigPreferences()),
        AndroidAppConfigMutation.Idle, 1)
    val deliveries = Channel<FilesItem>(Channel.UNLIMITED)
    val searchResults = deliveries.receiveAsFlow()
    val effects = mutableListOf<FilesBrowserEffect>()
    val transferEvents = mutableListOf<TransfersEvent>()
    var files by mutableStateOf(FilesBrowserState(listOf(
        FilesFolderState(FilesFolder.Root, FilesContent.Ready(listOf(source), FilesPaging.Complete)),
        FilesFolderState(FilesFolder(source.id, source.name), FilesContent.Ready(listOf(item), FilesPaging.Complete)),
    ), 1))
        private set
    var transfers by mutableStateOf(TransfersState(TransfersContent.Ready(listOf(TransferItem(
        TransferId(7), "Completed transfer", AppTransferStatus.Completed, TransferFileId(resolved.id.value),
        1.0, 100.0, null, null, null, null, false, "2026-09-06", true,
    )), TransfersPaging.Complete)))
        private set

    init {
        filesEvent(FilesBrowserEvent.Delete(source.id, item.id, FilesDeleteMode.TRASH))
        val failure = FilesFailure.Unexpected(IllegalStateException("Synthetic unknown delete"))
        filesEvent(FilesBrowserEvent.DeleteFinished((effects.last() as FilesBrowserEffect.Delete).requestId,
            FilesRepositoryResult.Failure(failure)))
        filesEvent(FilesBrowserEvent.DeleteChecked((effects.last() as FilesBrowserEffect.CheckDelete).requestId,
            FilesRepositoryResult.Failure(failure)))
    }
    fun filesEvent(event: FilesBrowserEvent): Boolean {
        val transition = FilesBrowserReducer.reduce(files, event)
        files = transition.state
        transition.effect?.let(effects::add)
        return transition.consumed
    }
    fun transfersEvent(event: TransfersEvent) {
        transferEvents.add(event)
        transfers = TransfersReducer.reduce(transfers, event).state
    }
    fun reconcileDelete() {
        val check = effects.last() as FilesBrowserEffect.CheckDelete
        assertEquals(item.id, check.itemId)
        filesEvent(FilesBrowserEvent.DeleteChecked(check.requestId, FilesRepositoryResult.Success(item)))
        val read = effects.last() as FilesBrowserEffect.LoadFolder
        assertEquals(source.id, read.folderId)
        filesEvent(FilesBrowserEvent.LoadSucceeded(read.requestId, FilesPage(listOf(item), null)))
        assertEquals(FilesFolderOperation.Idle, files.current.operation)
        assertEquals(2, effects.count { it is FilesBrowserEffect.CheckDelete })
    }
}

private object NoNavigationPlayback : PlaybackRepository {
    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
        error("Unexpected playback in navigation proof")
    override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
        error("Unexpected playback in navigation proof")
}
