package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferAction
import io.putdotio.android.transfers.TransferFileId
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransferMutation
import io.putdotio.android.transfers.TransferNavigation
import io.putdotio.android.transfers.TransferSubmission
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersReducer
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersRefresh
import io.putdotio.android.transfers.TransfersRequestId
import io.putdotio.android.transfers.TransfersState
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
class MobileTransfersScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun renderedAndEditedSharedCredentialsNeverEnterSavedState() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer("https://example.invalid/file?token=private-share-marker"))
        lateinit var registry: SaveableStateRegistry
        compose.setContent {
            registry = requireNotNull(LocalSaveableStateRegistry.current)
            PutioTheme { MobileTransfersScreen(state(TransfersContent.Empty), {}, draft = draft) }
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG)
            .performTextReplacement("https://example.invalid/edited?signature=private-share-marker")
        compose.runOnIdle {
            assertFalse(registry.performSave().toString().contains("private-share-marker"))
        }
    }

    @Test
    fun sharedTextPrefillsAnEditableSheetAndOnlyConfirmationSubmits() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer("A title\nhttps://example.invalid/first"))
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme { MobileTransfersScreen(state(TransfersContent.Empty), events::add, draft = draft) }
        }
        assertAddInput("https://example.invalid/first")
        compose.runOnIdle { assertEquals(emptyList<TransfersEvent>(), events) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextReplacement("magnet:?xt=urn:btih:12345")
        compose.onNodeWithText("Add").performClick()
        compose.runOnIdle { assertEquals(listOf(TransfersEvent.Add("magnet:?xt=urn:btih:12345")), events) }
    }

    @Test
    fun multipleSharedLinksStayEditableAndReplacementRequiresAChoice() {
        val draft = MobileTransferDraft()
        val multiple = "https://example.invalid/first https://example.invalid/second"
        draft.receive(parseMobileSharedTransfer(multiple))
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme { MobileTransfersScreen(state(TransfersContent.Empty), events::add, draft = draft) }
        }
        assertAddInput(multiple)
        compose.onNodeWithText("Share one URL or magnet link at a time.").assertIsDisplayed()
        compose.onNodeWithText("Add").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<TransfersEvent>(), events)
            draft.receive(parseMobileSharedTransfer("https://example.invalid/replacement"))
        }
        compose.onNodeWithText("Keep draft").performClick()
        assertAddInput(multiple)
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/replacement")) }
        compose.onNodeWithText("Use shared link").performClick()
        assertAddInput("https://example.invalid/replacement")
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodesWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertCountEquals(0)
        compose.runOnIdle { assertEquals(emptyList<TransfersEvent>(), events) }
    }

    @Test
    fun sharedDraftWaitsForTheControllerToAcceptAnAdd() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer("https://example.invalid/first"))
        val events = mutableListOf<TransfersEvent>()
        var current by mutableStateOf(state(TransfersContent.InitialLoading(TransfersRequestId(1))))
        compose.setContent { PutioTheme { MobileTransfersScreen(current, events::add, draft = draft) } }
        val blocked = listOf(
            current,
            state(TransfersContent.Empty).copy(
                mutation = TransferMutation.Running(TransferAction.Clean, TransfersRequestId(2)),
            ),
            state(TransfersContent.Empty).copy(
                navigation = TransferNavigation.Resolving(TransferFileId(3), TransfersRequestId(3)),
            ),
        )
        for (pending in blocked) {
            compose.runOnIdle { current = pending }
            compose.onNodeWithText("Add").assertIsNotEnabled()
            compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performImeAction()
            compose.runOnIdle { assertEquals(emptyList<TransfersEvent>(), events) }
        }
        compose.runOnIdle { current = state(TransfersContent.Empty) }
        compose.onNodeWithText("Add").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(TransfersEvent.Add("https://example.invalid/first")), events)
        }
    }

    @Test
    fun replacingAFailedAddClearsThePreviousFailureWithoutSubmitting() {
        val draft = MobileTransferDraft()
        val original = "https://example.invalid/first"
        draft.receive(parseMobileSharedTransfer(original))
        var current by mutableStateOf(state(TransfersContent.Empty).copy(
            mutation = TransferMutation.Failed(
                TransferAction.Add(requireNotNull(TransferSubmission.parse(original))),
                FilesFailure.Unexpected(IllegalStateException("rejected")),
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
            PutioTheme { MobileTransfersScreen(state(TransfersContent.Empty), events::add, draft = draft) }
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextReplacement("x".repeat(16 * 1024 + 1))
        assertAddInput(original)
        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performImeAction()
        compose.runOnIdle { assertEquals(emptyList<TransfersEvent>(), events) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG)
            .performTextReplacement("https://example.invalid/edited")
        compose.onNodeWithText("Add").assertIsEnabled()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performImeAction()
        compose.runOnIdle {
            assertEquals(listOf(TransfersEvent.Add("https://example.invalid/edited")), events)
        }
    }

    @Test
    fun completedAddIsHandledAfterLeavingAndReturningToTheScreen() {
        val draft = MobileTransferDraft()
        var visible by mutableStateOf(true)
        var current by mutableStateOf(state(TransfersContent.Empty))
        compose.setContent {
            PutioTheme {
                if (visible) MobileTransfersScreen(current, {}, draft = draft)
            }
        }
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/first")) }
        compose.onNodeWithText("Add").performClick()
        compose.runOnIdle {
            current = current.copy(mutation = TransferMutation.Running(
                TransferAction.Add(requireNotNull(TransferSubmission.parse("https://example.invalid/first"))),
                TransfersRequestId(9),
            ))
        }
        compose.waitForIdle()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            draft.receive(parseMobileSharedTransfer("https://example.invalid/second"))
            current = current.copy(mutation = TransferMutation.Idle, lastSuccessfulAddRequestId = TransfersRequestId(9))
            visible = true
        }
        assertAddInput("https://example.invalid/second")
        compose.onNodeWithText("Add").assertIsEnabled()
        compose.onAllNodesWithText("Use shared link").assertCountEquals(0)
    }

    @Test
    fun activeTransferFormatsProgressSpeedAndEtaAndConfirmsCancellation() {
        val events = mutableListOf<TransfersEvent>()
        val item =
            transfer(
                id = 1L,
                status = AppTransferStatus.Downloading,
            ).copy(
                percentDone = 42.0,
                downloadSpeedBytesPerSecond = 1_024.0,
                estimatedSecondsRemaining = 90.0,
                availability = 94.0,
            )
        setScreen(state(TransfersContent.Ready(listOf(item), TransfersPaging.Complete)), events::add)

        compose.onNodeWithText("42%").assertIsDisplayed()
        compose.onAllNodesWithText("/s", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("remaining", substring = true).assertCountEquals(1)
        compose.onNodeWithText("94% available", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel transfer transfer-", substring = true).performClick()
        compose.onNodeWithText("Cancel transfer?").assertIsDisplayed()
        compose.onNodeWithText("Stop transfer").performClick()

        assertEquals(TransfersEvent.Cancel(item.id), events.last())
    }

    @Test
    fun completedRowsExposeOnlyUsableFilesAndUnknownStatusesStaySafe() {
        val events = mutableListOf<TransfersEvent>()
        val available = transfer(1L, AppTransferStatus.Completed, fileId = 11L)
        val preparing = transfer(2L, AppTransferStatus.Completed)
        val unavailable = transfer(3L, AppTransferStatus.Completed, fileId = 13L, userFileExists = false)
        val unknown = transfer(4L, AppTransferStatus.Unknown("SERVER_INTERNAL_STATE"))
        setScreen(
            state(TransfersContent.Ready(listOf(available, preparing, unavailable, unknown), TransfersPaging.Complete)),
            events::add,
        )

        compose.onNodeWithText("Preparing file…").assertIsDisplayed()
        compose.onNodeWithText("File is no longer available").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open transfer-", substring = true).performClick()
        assertEquals(TransfersEvent.Open(available.id), events.last())

        compose.onNodeWithTag(MOBILE_TRANSFERS_LIST_TAG).performScrollToIndex(3)
        compose.onNodeWithText("Updating…").assertIsDisplayed()
        compose.onAllNodesWithText("SERVER_INTERNAL_STATE").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Cancel transfer", substring = true).assertCountEquals(0)
    }

    @Test
    fun seedingRowsCanOpenTheUsableFileAndStopSeeding() {
        val events = mutableListOf<TransfersEvent>()
        val seeding =
            transfer(
                id = 5L,
                status = AppTransferStatus.Seeding,
                fileId = 15L,
                userFileExists = true,
            )
        setScreen(
            state(TransfersContent.Ready(listOf(seeding), TransfersPaging.Complete)),
            events::add,
        )

        compose.onNodeWithContentDescription("Open transfer-", substring = true).performClick()
        assertEquals(TransfersEvent.Open(seeding.id), events.last())
        compose.onNodeWithContentDescription("Cancel transfer transfer-", substring = true).performClick()
        compose.onNodeWithText("Cancel transfer?").assertIsDisplayed()
    }

    @Test
    fun transferActionsIdentifyTheirTargetForAccessibility() {
        val events = mutableListOf<TransfersEvent>()
        setScreen(
            state(TransfersContent.Ready(
                listOf(transfer(1L, AppTransferStatus.Seeding, fileId = 15L)),
                TransfersPaging.Complete,
            )),
            events::add,
        )

        compose.onAllNodesWithText("Open file").assertCountEquals(0)
        compose.onAllNodesWithText("Cancel").assertCountEquals(0)
        compose.onNodeWithContentDescription("Open transfer-1").performClick()
        assertEquals(TransfersEvent.Open(TransferId(1L)), events.last())
        compose.onNodeWithContentDescription("Cancel transfer transfer-1").performClick()
        compose.onNodeWithText("Stop transfer").performClick()
        assertEquals(TransfersEvent.Cancel(TransferId(1L)), events.last())
    }

    @Test
    fun addSheetValidatesAndPreservesInputWhenTheApiRejectsIt() {
        var current by mutableStateOf(state(TransfersContent.Empty))
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme {
                MobileTransfersScreen(
                    state = current,
                    onEvent = { event ->
                        events += event
                        when (event) {
                            is TransfersEvent.Add -> {
                                val action = TransferAction.Add(requireNotNull(TransferSubmission.parse(event.input)))
                                current =
                                    current.copy(
                                        mutation = TransferMutation.Running(action, TransfersRequestId(1L)),
                                    )
                            }
                            TransfersEvent.DismissMutationFailure ->
                                current = current.copy(mutation = TransferMutation.Idle)
                            else -> Unit
                        }
                    },
                )
            }
        }

        compose.onNodeWithText("Add transfer").performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextInput("not a link")
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Enter a valid HTTP URL or magnet link.").assertIsDisplayed()
        assertAddInput("not a link")

        val magnet = "magnet:?xt=urn:btih:abc"
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextReplacement(magnet)
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Add")
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
                current.copy(
                    mutation =
                        TransferMutation.Failed(
                            running.action,
                            FilesFailure.Unexpected(IllegalStateException("rejected")),
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
        var current by mutableStateOf(state(TransfersContent.Empty))
        compose.setContent {
            PutioTheme {
                MobileTransfersScreen(
                    state = current,
                    onEvent = { event ->
                        if (event is TransfersEvent.Add) {
                            current =
                                current.copy(
                                    content =
                                        TransfersContent.Ready(
                                            listOf(transfer(1L, AppTransferStatus.Downloading)),
                                            TransfersPaging.Complete,
                                        ),
                                    lastSuccessfulAddRequestId = TransfersRequestId(1L),
                                )
                        }
                    },
                )
            }
        }

        compose.onNodeWithText("Add transfer").performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextInput("https://example.com/file")
        compose.onNodeWithText("Add").performClick()
        compose.onAllNodesWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertCountEquals(0)

        compose.onNodeWithText("Add transfer").performClick()
        assertAddInput("")
    }

    @Test
    fun addDraftSurvivesToolbarChangesAndResetsWithTheSession() {
        var sessionId by mutableStateOf(MobileAuthSessionId(1L))
        var current by mutableStateOf(state(TransfersContent.Empty))
        compose.setContent {
            PutioTheme {
                MobileTransfersScreen(state = current, onEvent = {}, sessionId = sessionId)
            }
        }

        compose.onNodeWithText("Add transfer").performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).performTextInput("unfinished link")
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Enter a valid HTTP URL or magnet link.").assertIsDisplayed()

        compose.runOnIdle {
            current = current.copy(
                content = TransfersContent.Ready(
                    listOf(transfer(1L, AppTransferStatus.Downloading)),
                    TransfersPaging.Complete,
                ),
            )
        }
        assertAddInput("unfinished link")
        compose.onNodeWithText("Enter a valid HTTP URL or magnet link.").assertIsDisplayed()

        compose.runOnIdle { sessionId = MobileAuthSessionId(2L) }
        compose.onAllNodesWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertCountEquals(0)
        compose.onNodeWithText("Add transfer").performClick()
        assertAddInput("")
        compose.onAllNodesWithText("Enter a valid HTTP URL or magnet link.").assertCountEquals(0)
    }

    @Test
    fun changingSessionDismissesAStaleConfirmation() {
        var sessionId by mutableStateOf(MobileAuthSessionId(1L))
        val current = state(
            TransfersContent.Ready(listOf(transfer(1L, AppTransferStatus.Completed)), TransfersPaging.Complete),
        )
        compose.setContent {
            PutioTheme {
                MobileTransfersScreen(
                    state = current,
                    onEvent = {},
                    sessionId = sessionId,
                )
            }
        }

        openActionsMenu()
        compose.onNodeWithText("Clean completed").performClick()
        compose.onNodeWithText("Clean completed transfers?").assertIsDisplayed()

        compose.runOnIdle { sessionId = MobileAuthSessionId(2L) }

        compose.onAllNodesWithText("Clean completed transfers?").assertCountEquals(0)
    }

    @Test
    fun loadingEmptyFailureRefreshAndPagingStayRecoverable() {
        var current by mutableStateOf(
            state(TransfersContent.InitialLoading(TransfersRequestId(1L))),
        )
        val events = mutableListOf<TransfersEvent>()
        setMutableScreen({ current }, events::add)

        compose.onNodeWithText("Loading transfers").assertIsDisplayed()
        compose.onNodeWithText("Add transfer").assertIsNotEnabled()
        compose.runOnIdle { current = state(TransfersContent.Empty) }
        compose.onNodeWithText("Add a URL or magnet link to fetch something.").assertIsDisplayed()
        openActionsMenu()
        compose.onNodeWithText("Refresh").performClick()
        assertEquals(TransfersEvent.Refresh, events.last())

        compose.runOnIdle {
            current = state(TransfersContent.Failed(FilesFailure.Unexpected(IllegalStateException("broken"))))
        }
        openActionsMenu()
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.onNodeWithText("Add transfer").assertIsEnabled()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(TransfersEvent.RetryLoad, events.last())

        compose.runOnIdle {
            current =
                state(
                    TransfersContent.Ready(
                        listOf(transfer(9L, AppTransferStatus.Waiting)),
                        TransfersPaging.Failed(
                            io.putdotio.android.transfers.TransferCursor("next"),
                            FilesFailure.Unexpected(IllegalStateException("paging")),
                        ),
                    ),
                    refresh = TransfersRefresh.Failed(FilesFailure.Unexpected(IllegalStateException("refresh"))),
                )
        }
        compose.onNodeWithText("Couldn’t refresh transfers.").assertIsDisplayed()
        compose.onNodeWithText("Couldn’t load more transfers.").assertIsDisplayed()
        compose.onNodeWithText("Clean completed").assertIsDisplayed()
    }

    @Test
    fun mutationDisablesRefreshAndEveryRowAction() {
        val rows =
            listOf(
                transfer(1L, AppTransferStatus.Downloading),
                transfer(2L, AppTransferStatus.Failed),
                transfer(3L, AppTransferStatus.Completed, fileId = 13L),
            )
        setScreen(
            state(TransfersContent.Ready(rows, TransfersPaging.Complete)).copy(
                mutation =
                    TransferMutation.Running(
                        TransferAction.Cancel(rows.first().id),
                        TransfersRequestId(9L),
                    ),
            ),
            onEvent = {},
        )

        openActionsMenu()
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Retry transfer", substring = true).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Open transfer-", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("Add transfer").assertIsNotEnabled()
        compose.onNodeWithText("Clean completed").assertIsNotEnabled()
    }

    @Test
    fun pollingKeepsRowActionsEnabledWithoutExposingForegroundRefresh() {
        val rows =
            listOf(
                transfer(1L, AppTransferStatus.Failed),
                transfer(2L, AppTransferStatus.Completed, fileId = 12L),
            )
        setScreen(
            state(
                TransfersContent.Ready(rows, TransfersPaging.Complete),
                refresh = TransfersRefresh.Polling(TransfersRequestId(9L)),
            ),
            onEvent = {},
        )

        openActionsMenu()
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Retry transfer", substring = true).assertIsEnabled()
        compose.onNodeWithContentDescription("Open transfer-", substring = true).assertIsEnabled()
        compose.onNodeWithText("Add transfer").assertIsEnabled()
        compose.onNodeWithText("Clean completed").assertIsEnabled()
    }

    @Test
    fun pollingDisablesAvailableAndFailedPagingControls() {
        val row = transfer(1L, AppTransferStatus.Seeding, fileId = 12L)
        var current by mutableStateOf(
            state(
                TransfersContent.Ready(
                    listOf(row),
                    TransfersPaging.Available(io.putdotio.android.transfers.TransferCursor("next")),
                ),
                refresh = TransfersRefresh.Polling(TransfersRequestId(9L)),
            ),
        )
        setMutableScreen({ current }, onEvent = {})

        compose.onNodeWithContentDescription("Open transfer-", substring = true).assertIsEnabled()
        compose.onNodeWithText("Load more").assertIsNotEnabled()

        compose.runOnIdle {
            current =
                current.copy(
                    content =
                        TransfersContent.Ready(
                            listOf(row),
                            TransfersPaging.Failed(
                                io.putdotio.android.transfers.TransferCursor("next"),
                                FilesFailure.Unexpected(IllegalStateException("paging")),
                            ),
                        ),
                )
        }

        compose.onNodeWithContentDescription("Open transfer-", substring = true).assertIsEnabled()
        compose.onNodeWithText("Try again").assertIsNotEnabled()
    }

    @Test
    fun foregroundRefreshDisablesRowActions() {
        val rows =
            listOf(
                transfer(1L, AppTransferStatus.Failed),
                transfer(2L, AppTransferStatus.Completed, fileId = 12L),
            )
        setScreen(
            state(
                TransfersContent.Ready(rows, TransfersPaging.Complete),
                refresh = TransfersRefresh.Refreshing(TransfersRequestId(9L)),
            ),
            onEvent = {},
        )

        openActionsMenu()
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Retry transfer", substring = true).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Open transfer-", substring = true).assertIsNotEnabled()
    }

    @Test
    fun changingControlsDoesNotResetTheTransfersViewport() {
        val rows = (1L..30L).map { transfer(it, AppTransferStatus.Waiting) }
        var current by mutableStateOf(
            state(TransfersContent.Ready(rows, TransfersPaging.Complete)),
        )
        setMutableScreen({ current }, onEvent = {})

        compose.onNodeWithTag(MOBILE_TRANSFERS_LIST_TAG).performScrollToIndex(19)
        compose.onNodeWithText("transfer-20").assertIsDisplayed()

        compose.runOnIdle {
            current =
                current.copy(
                    mutation =
                        TransferMutation.Running(
                            TransferAction.Cancel(rows.first().id),
                            TransfersRequestId(9L),
                        ),
                )
        }

        compose.onNodeWithText("transfer-20").assertIsDisplayed()
    }

    @Test
    fun fileResolutionDisablesRefreshAndEveryRowAction() {
        val rows =
            listOf(
                transfer(1L, AppTransferStatus.Failed),
                transfer(2L, AppTransferStatus.Completed, fileId = 12L),
            )
        setScreen(
            state(TransfersContent.Ready(rows, TransfersPaging.Complete)).copy(
                navigation =
                    TransferNavigation.Resolving(
                        TransferFileId(12L),
                        TransfersRequestId(9L),
                    ),
            ),
            onEvent = {},
        )

        openActionsMenu()
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Retry transfer", substring = true).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Open transfer-", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("Add transfer").assertIsNotEnabled()
        compose.onNodeWithText("Clean completed").assertIsNotEnabled()
    }

    @Test
    fun mutationDisablesLoadRecoveryAndPagingActions() {
        var current by mutableStateOf(
            state(TransfersContent.Failed(FilesFailure.Unexpected(IllegalStateException("load")))).withRunningAdd(),
        )
        setMutableScreen({ current }, onEvent = {})

        compose.onNodeWithText("Try again").assertIsNotEnabled()

        compose.runOnIdle {
            current =
                state(
                    TransfersContent.Ready(
                        listOf(transfer(1L, AppTransferStatus.Downloading)),
                        TransfersPaging.Available(io.putdotio.android.transfers.TransferCursor("next")),
                    ),
                ).withRunningAdd()
        }
        compose.onNodeWithText("Load more").assertIsNotEnabled()

        compose.runOnIdle {
            current =
                state(
                    TransfersContent.Ready(
                        listOf(transfer(1L, AppTransferStatus.Downloading)),
                        TransfersPaging.Failed(
                            io.putdotio.android.transfers.TransferCursor("next"),
                            FilesFailure.Unexpected(IllegalStateException("paging")),
                        ),
                    ),
                ).withRunningAdd()
        }
        compose.onNodeWithText("Try again").assertIsNotEnabled()

        compose.runOnIdle {
            current =
                state(
                    TransfersContent.Ready(
                        listOf(transfer(1L, AppTransferStatus.Downloading)),
                        TransfersPaging.Complete,
                    ),
                    refresh = TransfersRefresh.Failed(FilesFailure.Unexpected(IllegalStateException("refresh"))),
                ).withRunningAdd()
        }
        compose.onNodeWithText("Try again").assertIsNotEnabled()
    }

    private fun openActionsMenu() {
        if (compose.onAllNodesWithText("Refresh").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("Transfer actions").performClick()
        }
    }

    private fun setScreen(
        state: TransfersState,
        onEvent: (TransfersEvent) -> Unit,
    ) {
        compose.setContent {
            PutioTheme { MobileTransfersScreen(state, onEvent) }
        }
    }

    private fun assertAddInput(expected: String) {
        val semantics = compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).fetchSemanticsNode().config
        assertEquals(expected, semantics[SemanticsProperties.EditableText].text)
    }

    private fun setMutableScreen(
        state: () -> TransfersState,
        onEvent: (TransfersEvent) -> Unit,
    ) {
        compose.setContent {
            PutioTheme { MobileTransfersScreen(state(), onEvent) }
        }
    }

    private fun state(
        content: TransfersContent,
        refresh: TransfersRefresh = TransfersRefresh.Idle,
    ): TransfersState = TransfersState(content = content, refresh = refresh)

    private fun TransfersState.withRunningAdd(): TransfersState =
        copy(
            mutation =
                TransferMutation.Running(
                    TransferAction.Add(requireNotNull(TransferSubmission.parse("https://example.com/file"))),
                    TransfersRequestId(9L),
                ),
        )

    private fun transfer(
        id: Long,
        status: AppTransferStatus,
        fileId: Long? = null,
        userFileExists: Boolean? = null,
    ): TransferItem =
        TransferItem(
            id = TransferId(id),
            name = "transfer-$id",
            status = status,
            fileId = fileId?.let(::TransferFileId),
            sizeBytes = 1_048_576.0,
            percentDone = null,
            downloadSpeedBytesPerSecond = null,
            uploadSpeedBytesPerSecond = null,
            estimatedSecondsRemaining = null,
            availability = null,
            hasError = status == AppTransferStatus.Failed,
            createdAt = "2026-08-30T00:00:00Z",
            userFileExists = userFileExists,
        )
}
