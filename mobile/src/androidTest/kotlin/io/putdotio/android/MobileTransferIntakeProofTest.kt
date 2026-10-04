package io.putdotio.android

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesCopyId
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.MOBILE_FILES_MOVE_HERE_TAG
import io.putdotio.android.files.mobileFilesMoveFolderTag
import io.putdotio.android.transfers.MOBILE_TRANSFER_ADD_FIELD_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_CHANGE_DESTINATION_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_DESTINATION_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_TORRENT_TAG
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.MobileTransfersScreen
import io.putdotio.android.transfers.TransfersController
import io.putdotio.android.transfers.readMobileTorrent
import io.putdotio.android.transfers.scriptedSdkTransfersRepository
import io.putdotio.sdk.files.FileUploadInput
import io.putdotio.sdk.files.FileUploadResult
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.transfers.Transfer
import io.putdotio.sdk.transfers.TransferAddInput
import io.putdotio.sdk.transfers.TransferStatus
import io.putdotio.sdk.transfers.TransfersAddManyError
import io.putdotio.sdk.transfers.TransfersAddManyResponse
import io.putdotio.sdk.transfers.TransfersCleanResponse
import io.putdotio.sdk.transfers.TransfersListResponse
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Synthetic proof of magnet, multi-link and torrent intake: the magnet arrives through the real
 * MainActivity intent path, the Transfers screen and controller are production, and the SDK and
 * Files boundaries are faked, so no API call is made.
 */
@RunWith(AndroidJUnit4::class)
class MobileTransferIntakeProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Synthetic transfer intake proof requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.transfers.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun magnetLinksPastedLinksAndTorrentsWaitForConfirmationAndSaveToTheChosenFolder() {
        val draft = magnetDraftFromMainActivity()
        val added = CopyOnWriteArrayList<TransferAddInput>()
        val addedMany = CopyOnWriteArrayList<List<TransferAddInput>>()
        val uploaded = CopyOnWriteArrayList<FileUploadInput>()
        val repository = scriptedSdkTransfersRepository(
            list = { TransfersListResponse(cursor = null, transfers = emptyList(), status = "OK") },
            add = { input -> added += input; transfer(31L, "Harbor film") },
            addMany = { inputs ->
                addedMany += inputs
                TransfersAddManyResponse(
                    errors = listOf(TransfersAddManyError("UNKNOWN_SCHEME", 400, REFUSED_LINK)),
                    transfers = listOf(transfer(32L, "Archive été 東京")),
                    status = "OK",
                )
            },
            upload = { input -> uploaded += input; FileUploadResult.Transfer(transfer(33L, "Sample torrent")) },
            cleanTransfers = { TransfersCleanResponse(deletedIds = emptyList(), status = "OK") },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = TransfersController(repository, scope)
        try {
            compose.setContent {
                val state by controller.state.collectAsStateWithLifecycle()
                PutioTheme {
                    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        MobileTransfersScreen(
                            state = state,
                            onEvent = { controller.dispatch(it) },
                            draft = draft,
                            filesRepository = SampleFolders,
                        )
                    }
                }
            }

            compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText(MAGNET).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(MOBILE_TRANSFER_DESTINATION_TAG).assertTextContains("Default download folder")
            screenshot("01-magnet-draft-unsubmitted")
            assertTrue(added.isEmpty() && addedMany.isEmpty() && uploaded.isEmpty())

            compose.onNodeWithTag(MOBILE_TRANSFER_CHANGE_DESTINATION_TAG).performClick()
            compose.onNodeWithTag(mobileFilesMoveFolderTag(SAMPLE_FOLDER.id)).performClick()
            compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("No folders here.").fetchSemanticsNodes().isNotEmpty() }
            screenshot("02-save-to-picker")
            compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).performClick()
            compose.onNodeWithTag(MOBILE_TRANSFER_DESTINATION_TAG).assertTextContains(SAMPLE_FOLDER.name)
            screenshot("03-save-to-sample-folder")

            compose.onNodeWithText("Add").performClick()
            compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("1 transfer added").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf(MAGNET to SAMPLE_FOLDER.id.value), added.map { it.url to it.saveParentId })
            screenshot("04-magnet-added")

            compose.onNodeWithText("Add transfer").performClick()
            compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextReplacement("$SECOND_LINK\n$REFUSED_LINK")
            compose.onNodeWithText("Add").performClick()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText(REFUSED_MESSAGE).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(REFUSED_LINK).assertIsDisplayed()
            assertEquals(
                listOf(listOf(SECOND_LINK to SAMPLE_FOLDER.id.value, REFUSED_LINK to SAMPLE_FOLDER.id.value)),
                addedMany.map { batch -> batch.map { it.url to it.saveParentId } },
            )
            screenshot("05-refused-link-kept")

            compose.runOnIdle {
                draft.edit("")
                draft.receive(readMobileTorrent("Sample torrent.torrent") { TORRENT.inputStream() })
            }
            compose.onNodeWithText("Use shared link").performClick()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText("Replace the current draft?").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag(MOBILE_TRANSFER_TORRENT_TAG).assertTextContains("Sample torrent.torrent")
            Thread.sleep(DIALOG_EXIT_MILLIS) // The replacement dialog's window fades after its semantics leave.
            screenshot("06-torrent-draft")
            compose.onNodeWithText("Add").performClick()
            compose.waitUntil(TIMEOUT) { uploaded.isNotEmpty() }
            val upload = uploaded.single()
            assertEquals("Sample torrent.torrent" to SAMPLE_FOLDER.id.value, upload.fileName to upload.parentId)
            assertTrue(upload.requireTorrent)
            assertTrue(TORRENT.contentEquals(upload.content))
        } finally {
            compose.runOnIdle { controller.close() }
            scope.cancel()
        }
    }

    /**
     * The same VIEW intent `adb shell am start -d magnet:...` sends, launching the production activity.
     * ActivityScenario can't track a launch intent that consumption scrubs, so the activity is started directly.
     */
    private fun magnetDraftFromMainActivity(): MobileTransferDraft {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse(MAGNET)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        lateinit var draft: MobileTransferDraft
        instrumentation.runOnMainSync {
            draft = activity.transferDraft
            assertEquals(null, activity.intent.data)
            activity.finish()
        }
        instrumentation.waitForIdleSync()
        val state = draft.state.value
        assertEquals(MAGNET, state.input)
        assertTrue(state.open && !state.submitting)
        return draft
    }

    private fun screenshot(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId =
            UUID.fromString(requireNotNull(InstrumentationRegistry.getArguments().getString("putio.transfers.runId")))
        val directory =
            File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "transfer-intake-proof-$runId")
        check(directory.mkdirs() || directory.isDirectory)
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

private object SampleFolders : FilesRepository {
    override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) =
        PutioResult.Success(
            FilesPage(if (folderId == FilesFolder.Root.id) listOf(SAMPLE_FOLDER) else emptyList(), null),
        )
    override suspend fun loadFolder(folderId: FilesItemId): Nothing = error("No Files browsing in this proof")
    override suspend fun loadNextPage(cursor: FilesCursor): Nothing = error("No Files paging in this proof")
    override suspend fun move(itemId: FilesItemId, destinationId: FilesItemId): Nothing = error("No move")
    override suspend fun persistSort(folderId: FilesItemId, sort: FilesSort): Nothing = error("No sort")
    override suspend fun rename(itemId: FilesItemId, name: String): Nothing = error("No rename")
    override suspend fun delete(itemId: FilesItemId, mode: FilesDeleteMode): Nothing = error("No delete")
    override suspend fun resolveItem(itemId: FilesItemId): Nothing = error("No item resolution")
    override suspend fun startCopy(itemId: FilesItemId, destinationId: FilesItemId): Nothing = error("No copy")
    override suspend fun checkCopy(copyId: FilesCopyId): Nothing = error("No copy")
}

private fun transfer(id: Long, name: String) =
    Transfer(id = id, name = name, status = TransferStatus.IN_QUEUE, createdAt = "2026-10-01T00:00:00")

private const val TIMEOUT = 5_000L
private const val DIALOG_EXIT_MILLIS = 1_000L
private const val MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Harbor%20film"
private const val SECOND_LINK = "magnet:?xt=urn:btih:89abcdef0123456789abcdef0123456789abcdef&dn=Archive"
private const val REFUSED_LINK = "https://example.invalid/refused.mp4"
private const val REFUSED_MESSAGE = "put.io couldn’t add these links. Check them and try again."
private val SAMPLE_FOLDER = FilesItem(FilesItemId(8L), FilesFolder.Root.id, "Sample folder", PutioFileType.FOLDER, 0L, "")
private val TORRENT = "d8:announce3:url4:infod4:name14:Sample torrentee".toByteArray()
