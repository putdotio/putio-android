package io.putdotio.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.MOBILE_FILES_MOVE_HERE_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_PICKER_TAG
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.files.mobileFilesMoveFolderTag
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.MOBILE_TRANSFER_ADD_CONFIRM_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_ADD_FIELD_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_CHANGE_DESTINATION_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_DESTINATION_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_PASTE_TAG
import io.putdotio.android.transfers.MOBILE_TRANSFER_TORRENT_TAG
import io.putdotio.android.transfers.MobileSharedTransfer
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.MobileTransfersScreen
import io.putdotio.android.transfers.TorrentUpload
import io.putdotio.android.transfers.TransferAction
import io.putdotio.android.transfers.TransferAddReceipt
import io.putdotio.android.transfers.TransferFileId
import io.putdotio.android.transfers.TransferMutation
import io.putdotio.android.transfers.TransferNavigation
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersReducer
import io.putdotio.android.transfers.TransfersRequestId
import io.putdotio.android.transfers.copyForTest
import io.putdotio.android.transfers.parseMobileSharedTransfer
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileTransfersAddSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun renderedAndEditedSharedCredentialsNeverEnterSavedState() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer("https://example.invalid/file?token=private-share-marker"))
        lateinit var registry: SaveableStateRegistry
        compose.setContent {
            registry = requireNotNull(LocalSaveableStateRegistry.current)
            PutioTheme { MobileTransfersScreen(transfersTestState(TransfersContent.Empty), {}, draft = draft) }
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG)
            .performTextReplacement("https://example.invalid/edited?signature=private-share-marker")
        compose.runOnIdle {
            assertFalse(registry.performSave().toString().contains("private-share-marker"))
        }
    }

    @Test
    fun multipleSharedLinksBecomeOneLinePerLinkAndReplacementRequiresAChoice() {
        val draft = MobileTransferDraft()
        val normalized = "https://example.invalid/first\nhttps://example.invalid/second"
        draft.receive(parseMobileSharedTransfer("https://example.invalid/first https://example.invalid/second"))
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme { MobileTransfersScreen(transfersTestState(TransfersContent.Empty), events::add, draft = draft) }
        }
        assertAddInput(normalized)
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/replacement")) }
        compose.onNodeWithText("Keep draft").performClick()
        assertAddInput(normalized)
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/replacement")) }
        compose.onNodeWithText("Use shared link").performClick()
        assertAddInput("https://example.invalid/replacement")
        compose.runOnIdle { assertEquals(emptyList<TransfersEvent>(), events) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextReplacement("magnet:?xt=urn:btih:12345")
        addButton().performClick()
        compose.runOnIdle { assertEquals(listOf(TransfersEvent.Add("magnet:?xt=urn:btih:12345")), events) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodesWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertCountEquals(0)
        compose.runOnIdle { assertEquals(listOf(TransfersEvent.Add("magnet:?xt=urn:btih:12345")), events) }
    }

    @Test
    fun pastedLinksAddTogetherToTheFolderChosenWithTheMovePicker() {
        val draft = MobileTransferDraft()
        val folder = FilesItem(FilesItemId(8L), FilesFolder.Root.id, "Sample folder", PutioFileType.FOLDER, 0L, "")
        val repository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId) = error("Unexpected folder read")
            override suspend fun loadMoveDestinations(folderId: FilesItemId, cursor: FilesCursor?) =
                PutioResult.Success(
                    FilesPage(if (folderId == FilesFolder.Root.id) listOf(folder) else emptyList(), null),
                )
        }
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme {
                MobileTransfersScreen(
                    transfersTestState(TransfersContent.Empty),
                    events::add,
                    draft = draft,
                    filesRepository = repository,
                )
            }
        }
        compose.onNode(AddTransferAction).performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_DESTINATION_TAG).assertTextContains("Default download folder")
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG)
            .performTextReplacement("magnet:?xt=urn:btih:1\nhttps://example.invalid/second")
        compose.onNodeWithTag(MOBILE_TRANSFER_CHANGE_DESTINATION_TAG).performClick()
        compose.onNodeWithTag(mobileFilesMoveFolderTag(folder.id)).performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).assertTextEquals("Save here").performClick()
        compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_TRANSFER_DESTINATION_TAG).assertTextContains("Sample folder")
        addButton().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(TransfersEvent.Add("magnet:?xt=urn:btih:1\nhttps://example.invalid/second", saveParentId = 8L)),
                events,
            )
        }
    }

    @Test
    fun aSharedTorrentShowsItsNameAndUploadsOnlyAfterConfirmation() {
        val draft = MobileTransferDraft()
        val torrent = TorrentUpload("Harbor film.torrent", "d4:infod4:name6:Harboree".toByteArray())
        draft.receive(MobileSharedTransfer(torrent = torrent))
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme { MobileTransfersScreen(transfersTestState(TransfersContent.Empty), events::add, draft = draft) }
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_TORRENT_TAG).assertTextContains("Harbor film.torrent")
        compose.onAllNodesWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(MOBILE_TRANSFER_CHANGE_DESTINATION_TAG).assertCountEquals(0)
        compose.runOnIdle { assertEquals(emptyList<TransfersEvent>(), events) }
        addButton().performClick()
        compose.runOnIdle { assertEquals(listOf(TransfersEvent.AddTorrent(torrent)), events) }
        compose.onNodeWithContentDescription("Remove torrent file").performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
    }

    @Test
    fun refusedLinksReopenTheSheetAndOnlyStartedTransfersAreAnnounced() {
        val draft = MobileTransferDraft()
        var current by mutableStateOf(transfersTestState(TransfersContent.Empty))
        compose.setContent { PutioTheme { MobileTransfersScreen(current, {}, draft = draft) } }
        compose.runOnIdle {
            draft.receive(parseMobileSharedTransfer("magnet:?xt=urn:btih:1 https://example.invalid/refused"))
            current = current.withRunningLinkAdd()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            current = current.copyForTest(
                mutation = TransferMutation.Idle,
                lastAddReceipt = TransferAddReceipt(
                    TransfersRequestId(9L),
                    0,
                    listOf("https://example.invalid/refused"),
                ),
            )
        }
        assertAddInput("https://example.invalid/refused")
        compose.onNodeWithText("put.io couldn’t add these links. Check them and try again.").assertIsDisplayed()
        compose.onAllNodesWithText("0 transfers added").assertCountEquals(0)
        compose.runOnIdle {
            current = current.copyForTest(lastAddReceipt = TransferAddReceipt(TransfersRequestId(10L), 2, emptyList()))
        }
        compose.onNodeWithText("2 transfers added").assertIsDisplayed()
    }

    @Test
    fun sharedDraftWaitsForTheControllerToAcceptAnAdd() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer("https://example.invalid/first"))
        val events = mutableListOf<TransfersEvent>()
        var current by mutableStateOf(transfersTestState(TransfersContent.InitialLoading(TransfersRequestId(1))))
        compose.setContent { PutioTheme { MobileTransfersScreen(current, events::add, draft = draft) } }
        val blocked = listOf(
            current,
            transfersTestState(TransfersContent.Empty).copyForTest(
                mutation = TransferMutation.Running(TransferAction.Clean, TransfersRequestId(2)),
            ),
            transfersTestState(TransfersContent.Empty).copyForTest(
                navigation = TransferNavigation.Resolving(TransferFileId(3), TransfersRequestId(3)),
            ),
        )
        for (pending in blocked) {
            compose.runOnIdle { current = pending }
            addButton().assertIsNotEnabled()
            compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performImeAction()
            compose.runOnIdle { assertEquals(emptyList<TransfersEvent>(), events) }
        }
        compose.runOnIdle { current = transfersTestState(TransfersContent.Empty) }
        addButton().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(TransfersEvent.Add("https://example.invalid/first")), events)
        }
    }

    @Test
    fun replacingAFailedAddClearsThePreviousFailureWithoutSubmitting() {
        val draft = MobileTransferDraft()
        val original = "https://example.invalid/first"
        draft.receive(parseMobileSharedTransfer(original))
        var current by mutableStateOf(transfersTestState(TransfersContent.Empty).copyForTest(
            mutation = TransferMutation.Failed(
                TransferAction.Add(linksAddRequest(original)),
                PutioFailure.Unexpected(IllegalStateException("rejected")),
            ),
        ))
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme {
                MobileTransfersScreen(current, { event ->
                    events.add(event)
                    current = TransfersReducer.reduce(current, event).state
                }, draft = draft)
            }
        }
        compose.onNodeWithText("put.io is temporarily unavailable. Try again.").assertIsDisplayed()
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/second")) }
        compose.onNodeWithText("Use shared link").performClick()
        assertAddInput("https://example.invalid/second")
        compose.onAllNodesWithText("put.io is temporarily unavailable. Try again.").assertCountEquals(0)
        compose.runOnIdle { assertEquals(listOf(TransfersEvent.DismissMutationFailure), events) }
    }

    @Test
    fun oversizedEditDisablesAddAndImeUntilTheDraftIsEditedAgain() {
        val draft = MobileTransferDraft()
        val original = "https://example.invalid/first"
        draft.receive(parseMobileSharedTransfer(original))
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme { MobileTransfersScreen(transfersTestState(TransfersContent.Empty), events::add, draft = draft) }
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextReplacement("x".repeat(16 * 1024 + 1))
        assertAddInput(original)
        addButton().assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performImeAction()
        compose.runOnIdle { assertEquals(emptyList<TransfersEvent>(), events) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG)
            .performTextReplacement("https://example.invalid/edited")
        addButton().assertIsEnabled()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performImeAction()
        compose.runOnIdle {
            assertEquals(listOf(TransfersEvent.Add("https://example.invalid/edited")), events)
        }
    }

    @Test
    fun completedAddIsHandledAfterLeavingAndReturningToTheScreen() {
        val draft = MobileTransferDraft()
        var visible by mutableStateOf(true)
        var current by mutableStateOf(transfersTestState(TransfersContent.Empty))
        compose.setContent {
            PutioTheme {
                if (visible) MobileTransfersScreen(current, {}, draft = draft)
            }
        }
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/first")) }
        addButton().performClick()
        compose.runOnIdle {
            current = current.copyForTest(mutation = TransferMutation.Running(
                TransferAction.Add(linksAddRequest("https://example.invalid/first")),
                TransfersRequestId(9),
            ))
        }
        compose.waitForIdle()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            draft.receive(parseMobileSharedTransfer("https://example.invalid/second"))
            current = current.copyForTest(
                mutation = TransferMutation.Idle,
                lastAddReceipt = TransferAddReceipt(TransfersRequestId(9), 1, emptyList()),
            )
            visible = true
        }
        assertAddInput("https://example.invalid/second")
        addButton().assertIsEnabled()
        compose.onAllNodesWithText("Use shared link").assertCountEquals(0)
    }

    @Test
    fun addSheetValidatesAndPreservesInputWhenTheApiRejectsIt() {
        var current by mutableStateOf(transfersTestState(TransfersContent.Empty))
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme {
                MobileTransfersScreen(
                    state = current,
                    onEvent = { event ->
                        events += event
                        when (event) {
                            is TransfersEvent.Add -> {
                                val action = TransferAction.Add(linksAddRequest(event.input))
                                current =
                                    current.copyForTest(
                                        mutation = TransferMutation.Running(action, TransfersRequestId(1L)),
                                    )
                            }
                            TransfersEvent.DismissMutationFailure ->
                                current = current.copyForTest(mutation = TransferMutation.Idle)
                            else -> Unit
                        }
                    },
                )
            }
        }

        compose.onNode(AddTransferAction).performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextInput("not a link")
        addButton().performClick()
        compose.onNodeWithText("Line 1 isn’t a magnet or a file link.").assertIsDisplayed()
        assertAddInput("not a link")

        val magnet = "magnet:?xt=urn:btih:abc"
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextReplacement(magnet)
        addButton().performClick()
        addButton()
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Adding transfer",
                ),
            )
        compose.runOnIdle {
            val running = current.mutation as TransferMutation.Running
            current =
                current.copyForTest(
                    mutation =
                        TransferMutation.Failed(
                            running.action,
                            PutioFailure.Unexpected(IllegalStateException("rejected")),
                        ),
                )
        }

        assertAddInput(magnet)
        compose.onNodeWithText("put.io is temporarily unavailable. Try again.").assertIsDisplayed()
        val edited = "$magnet&dn=edited"
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextReplacement(edited)
        assertAddInput(edited)
        assertEquals(TransfersEvent.DismissMutationFailure, events.last())
    }

    @Test
    fun immediateAddSuccessClosesAndClearsTheSheet() {
        var current by mutableStateOf(transfersTestState(TransfersContent.Empty))
        compose.setContent {
            PutioTheme {
                MobileTransfersScreen(
                    state = current,
                    onEvent = { event ->
                        if (event is TransfersEvent.Add) {
                            current =
                                current.copyForTest(
                                    content =
                                        TransfersContent.Ready(
                                            listOf(transferRow(1L, AppTransferStatus.Downloading)),
                                            TransfersPaging.Complete,
                                        ),
                                    lastAddReceipt = TransferAddReceipt(TransfersRequestId(1L), 1, emptyList()),
                                )
                        }
                    },
                )
            }
        }

        compose.onNode(AddTransferAction).performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextInput("https://example.com/file")
        addButton().performClick()
        compose.onAllNodesWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertCountEquals(0)

        compose.onNode(AddTransferAction).performClick()
        assertAddInput("")
    }

    @Test
    fun addDraftSurvivesToolbarChangesAndResetsWithTheSession() {
        var sessionId by mutableStateOf(MobileAuthSessionId(1L))
        var current by mutableStateOf(transfersTestState(TransfersContent.Empty))
        compose.setContent {
            PutioTheme {
                MobileTransfersScreen(state = current, onEvent = {}, sessionId = sessionId)
            }
        }

        compose.onNode(AddTransferAction).performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextInput("unfinished link")
        addButton().performClick()
        compose.onNodeWithText("Line 1 isn’t a magnet or a file link.").assertIsDisplayed()

        compose.runOnIdle {
            current = current.copyForTest(
                content = TransfersContent.Ready(
                    listOf(transferRow(1L, AppTransferStatus.Downloading)),
                    TransfersPaging.Complete,
                ),
            )
        }
        assertAddInput("unfinished link")
        compose.onNodeWithText("Line 1 isn’t a magnet or a file link.").assertIsDisplayed()

        compose.runOnIdle { sessionId = MobileAuthSessionId(2L) }
        compose.onAllNodesWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertCountEquals(0)
        compose.onNode(AddTransferAction).performClick()
        assertAddInput("")
        compose.onAllNodesWithText("Line 1 isn’t a magnet or a file link.").assertCountEquals(0)
    }

    @Test
    fun eachLineIsCheckedOnItsOwnAndAddCountsTheLinksThatGo() {
        val draft = MobileTransferDraft()
        draft.receive(MobileSharedTransfer("magnet:?xt=urn:btih:1\nhttps://example.invalid/page and prose"))
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme { MobileTransfersScreen(transfersTestState(TransfersContent.Empty), events::add, draft = draft) }
        }
        compose.onNodeWithText("Line 2 isn’t a magnet or a file link.").assertIsDisplayed()
        addButton().assertTextEquals("Add 1").performClick()
        compose.runOnIdle { assertEquals(listOf(TransfersEvent.Add("magnet:?xt=urn:btih:1")), events) }
        assertAddInput("magnet:?xt=urn:btih:1\nhttps://example.invalid/page and prose")

        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextReplacement("one\ntwo\nmagnet:?xt=urn:btih:2")
        compose.onNodeWithText("Lines 1, 2 aren’t magnet or file links.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextReplacement("")
        addButton().assertTextEquals("Add")
        compose.onAllNodesWithText("aren’t magnet", substring = true).assertCountEquals(0)
    }

    @Test
    fun pasteAppendsTheClipboardOnANewLine() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("link", "  magnet:?xt=urn:btih:pasted  "))
        val draft = MobileTransferDraft()
        draft.receive(MobileSharedTransfer("https://example.invalid/first"))
        compose.setContent {
            PutioTheme { MobileTransfersScreen(transfersTestState(TransfersContent.Empty), {}, draft = draft) }
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_PASTE_TAG).performClick()
        compose.waitForIdle()
        assertAddInput("https://example.invalid/first\nmagnet:?xt=urn:btih:pasted")
        addButton().assertTextEquals("Add 2")
    }

    private fun addButton() = compose.onNodeWithTag(MOBILE_TRANSFER_ADD_CONFIRM_TAG)

    private fun assertAddInput(expected: String) {
        val semantics = compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).fetchSemanticsNode().config
        assertEquals(expected, semantics[SemanticsProperties.EditableText].text)
    }
}
