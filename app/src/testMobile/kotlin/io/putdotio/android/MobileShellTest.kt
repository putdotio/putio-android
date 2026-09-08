package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player as Media3Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileSignedOutReason
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.FilesSort
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigFailure
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigReducer
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersReducer
import io.putdotio.android.transfers.TransferFileId
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferNavigation
import io.putdotio.android.transfers.TransferNotice
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersRequestId
import io.putdotio.android.transfers.TransfersState
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** JVM proof for the mobile Material 3 shell; LaunchSmokeTest owns device launch proof. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileShellTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun incomingShareWaitsForFilesRecoveryThenOpensTransfersWithoutSubmitting() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer("https://example.invalid/shared"))
        var files by mutableStateOf(pendingShellDeleteState())
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme {
                MobileShell(
                    transferDraft = draft,
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = files,
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { true },
                    onAccountSettingsEvent = {},
                    onTransfersEvent = events::add,
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertDoesNotExist()
        compose.runOnIdle {
            org.junit.Assert.assertNotNull(draft.state.value.incomingRequestId)
            files = emptyFilesState()
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle {
            org.junit.Assert.assertNull(draft.state.value.incomingRequestId)
            assertTrue(events.none { it is TransfersEvent.Add })
        }
    }

    @Test
    fun incomingShareWaitsForTransferResolutionBeforeOpeningItsDraft() {
        val draft = MobileTransferDraft()
        var transfers by mutableStateOf(resolvingTransfersState())
        val resolved = CompletableDeferred<FilesRepositoryResult<FilesItem>>()
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme {
                MobileShell(
                    transferDraft = draft,
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersState = transfers,
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { true },
                    onTransfersEvent = { event ->
                        events.add(event)
                        transfers = TransfersReducer.reduce(transfers, event).state
                    },
                    resolveTransferFile = { resolved.await() },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/shared")) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertDoesNotExist()
        compose.runOnIdle {
            org.junit.Assert.assertNotNull(draft.state.value.incomingRequestId)
            resolved.complete(FilesRepositoryResult.Success(shellResolvedFolder()))
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle {
            org.junit.Assert.assertNull(draft.state.value.incomingRequestId)
            assertTrue(events.contains(TransfersEvent.OpenSucceeded(TransfersRequestId(3L))))
            assertTrue(events.none { it is TransfersEvent.Add })
        }
    }

    @Test
    fun acceptingAReplacementShareWaitsForTransferResolution() {
        val draft = MobileTransferDraft()
        var transfers by mutableStateOf(resolvingTransfersState())
        val resolved = CompletableDeferred<FilesRepositoryResult<FilesItem>>()
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme {
                MobileShell(
                    transferDraft = draft,
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersState = transfers,
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { true },
                    onTransfersEvent = { event ->
                        events.add(event)
                        transfers = TransfersReducer.reduce(transfers, event).state
                    },
                    resolveTransferFile = { resolved.await() },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Transfers").performClick()
        compose.runOnIdle {
            draft.edit("https://example.invalid/previous")
            draft.receive(parseMobileSharedTransfer("https://example.invalid/shared"))
        }
        compose.onNodeWithText("Use shared link").performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle {
            org.junit.Assert.assertNotNull(draft.state.value.incomingRequestId)
            resolved.complete(FilesRepositoryResult.Success(shellResolvedFolder()))
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle {
            org.junit.Assert.assertNull(draft.state.value.incomingRequestId)
            assertTrue(events.contains(TransfersEvent.OpenSucceeded(TransfersRequestId(3L))))
            assertTrue(events.none { it is TransfersEvent.Add })
        }
    }

    @Test
    fun dismissingAShareDuringTransferResolutionPreservesTheRequestedFilesNavigation() {
        val draft = MobileTransferDraft()
        var transfers by mutableStateOf(resolvingTransfersState())
        val resolved = CompletableDeferred<FilesRepositoryResult<FilesItem>>()
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme {
                MobileShell(
                    transferDraft = draft,
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersState = transfers,
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { true },
                    onTransfersEvent = { event ->
                        events.add(event)
                        transfers = TransfersReducer.reduce(transfers, event).state
                    },
                    resolveTransferFile = { resolved.await() },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Transfers").performClick()
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/shared")) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { resolved.complete(FilesRepositoryResult.Success(shellResolvedFolder())) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertDoesNotExist()
        compose.onNodeWithText("Add transfer").assertDoesNotExist()
        compose.runOnIdle {
            org.junit.Assert.assertNull(draft.state.value.incomingRequestId)
            assertEquals("https://example.invalid/shared", draft.state.value.input)
            assertTrue(events.contains(TransfersEvent.OpenSucceeded(TransfersRequestId(3L))))
            assertTrue(events.none { it is TransfersEvent.Add })
        }
    }

    @Test
    fun shareNavigationWaitsForARunningTransferMutationToSettle() {
        val draft = MobileTransferDraft()
        var transfers by mutableStateOf(TransfersState(
            content = TransfersContent.Empty,
            mutation = io.putdotio.android.transfers.TransferMutation.Running(
                io.putdotio.android.transfers.TransferAction.Clean, TransfersRequestId(2L),
            ),
        ))
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme {
                MobileShell(
                    transferDraft = draft,
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersState = transfers,
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { true },
                    onTransfersEvent = events::add,
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/shared")) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertDoesNotExist()
        compose.runOnIdle {
            org.junit.Assert.assertNotNull(draft.state.value.incomingRequestId)
            transfers = transfers.copy(mutation = io.putdotio.android.transfers.TransferMutation.Idle)
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle {
            org.junit.Assert.assertNull(draft.state.value.incomingRequestId)
            assertTrue(events.none { it is TransfersEvent.Add })
        }
    }

    @Test
    fun aDelayedHistoryResultDoesNotHideTheSharedDraft() {
        val draft = MobileTransferDraft()
        val results = Channel<FilesItem>(Channel.BUFFERED)
        val filesEvents = mutableListOf<FilesBrowserEvent>()
        compose.setShell(
            transferDraft = draft,
            contentNavigation = results.receiveAsFlow(),
            onFilesEvent = { filesEvents += it; true },
        )
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/shared")) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle { results.trySend(shellResolvedFolder()) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(filesEvents.contains(FilesBrowserEvent.OpenExternalItem(shellResolvedFolder())))
            assertEquals("https://example.invalid/shared", draft.state.value.input)
        }
    }

    @Test
    fun aDelayedHistoryResultOpensFilesAfterTheSharedDraftIsDismissed() {
        val draft = MobileTransferDraft()
        val results = Channel<FilesItem>(Channel.BUFFERED)
        compose.setShell(transferDraft = draft, contentNavigation = results.receiveAsFlow())
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/shared")) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { results.trySend(shellResolvedFolder()) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertDoesNotExist()
        compose.onNodeWithText("Add transfer").assertDoesNotExist()
    }

    @Test
    fun aRejectedHistoryResultKeepsTheSharedDraftAndReportsNavigationRecovery() {
        val draft = MobileTransferDraft()
        val results = Channel<FilesItem>(Channel.BUFFERED)
        val filesEvents = mutableListOf<FilesBrowserEvent>()
        compose.setShell(
            transferDraft = draft,
            contentNavigation = results.receiveAsFlow(),
            onFilesEvent = { filesEvents += it; false },
        )
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/shared")) }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle { results.trySend(shellResolvedFolder()) }
        compose.onNodeWithText("Couldn’t open this file").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(filesEvents.contains(FilesBrowserEvent.OpenExternalItem(shellResolvedFolder())))
            assertEquals("https://example.invalid/shared", draft.state.value.input)
        }
    }

    @Test
    fun aSharedDraftWaitsForTheEarlierNowPlayingLookup() = verifyShareAfterNowPlayingLookup(
        ActiveAudio(FilesItemId(9L), "song.mp3"),
    )

    @Test
    fun aSharedDraftOpensWhenTheEarlierNowPlayingLookupFindsNoSession() = verifyShareAfterNowPlayingLookup(null)

    @Test
    fun acceptingAReplacementShareWaitsForTheEarlierNowPlayingLookup() = verifyShareAfterNowPlayingLookup(
        ActiveAudio(FilesItemId(9L), "song.mp3"),
        replaceDraft = true,
    )

    private fun verifyShareAfterNowPlayingLookup(result: ActiveAudio?, replaceDraft: Boolean = false) {
        val draft = MobileTransferDraft()
        val pending = kotlinx.coroutines.flow.MutableStateFlow(true)
        val resolved = CompletableDeferred<ActiveAudio?>()
        val requests = NowPlayingRequests(pending) { pending.value = false }
        val factory = object : MobilePlayerFactory by NoAudioSessionFactory {
            override suspend fun activeAudio(context: android.content.Context): ActiveAudio? = resolved.await()
        }
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme {
                MobileShell(
                    transferDraft = draft,
                    nowPlayingRequests = requests,
                    playbackPlayerFactory = factory,
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { true },
                    onTransfersEvent = events::add,
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        if (replaceDraft) {
            compose.onNodeWithText("Transfers").performClick()
            compose.runOnIdle { draft.edit("https://example.invalid/previous") }
        }
        compose.runOnIdle { draft.receive(parseMobileSharedTransfer("https://example.invalid/shared")) }
        if (replaceDraft) {
            compose.onNodeWithText("Use shared link").performClick()
        } else {
            compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertDoesNotExist()
        }
        compose.runOnIdle {
            assertTrue(pending.value)
            org.junit.Assert.assertNotNull(draft.state.value.incomingRequestId)
            resolved.complete(result)
        }
        compose.onNodeWithTag(MOBILE_TRANSFER_ADD_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(pending.value)
            org.junit.Assert.assertNull(draft.state.value.incomingRequestId)
            assertTrue(events.none { it is TransfersEvent.Add })
        }
    }

    @Test
    fun filesDeleteRequiresConfirmedSettingsThroughSaveAndRefreshFailure() {
        val original = DefaultAccountSettingsPreferences.copy(trashEnabled = true)
        val optimistic = original.copy(trashEnabled = false)
        val change = AccountSettingsChange(AccountSettingsKey.Trash, enabled = false)
        var settings by mutableStateOf(readyAccountSettingsState(preferences = original))
        compose.setContent {
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = videoFilesState(),
                    accountSettingsState = settings,
                    appConfigState = readyAndroidAppConfigState(),
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { true },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Actions for episode.mkv").performClick()
        compose.onNodeWithText("Move to trash").performClick()
        compose.onNodeWithText("Confirm").assertIsEnabled()
        compose.runOnIdle {
            settings = readyAccountSettingsState(
                preferences = optimistic,
                mutation = AccountSettingsMutation.Saving(
                    AccountSettingsRequestId(3L), change, original, AccountSettingsMutation.Operation.Refresh,
                ),
            )
        }
        compose.onNodeWithText("Confirm").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.runOnIdle {
            settings = readyAccountSettingsState(
                preferences = optimistic,
                mutation = AccountSettingsMutation.Failed(
                    change, AccountSettingsFailure.Unexpected(IllegalStateException("refresh failed")),
                    original, AccountSettingsMutation.Operation.Refresh,
                ),
            )
        }
        compose.onNodeWithText("Delete").assertIsNotEnabled()
        compose.runOnIdle { settings = readyAccountSettingsState(preferences = optimistic) }
        compose.onNodeWithText("Delete").assertIsEnabled().performClick()
        compose.onNodeWithText("Permanently delete “episode.mkv”? This cannot be undone.").assertIsDisplayed()
    }

    @Test
    fun appConfigAuthenticationFailureTriggersRootSessionRejection() {
        var rejections = 0
        val failure = AndroidAppConfigFailure.AuthenticationRequired(
            PutioConfigurationException("expired"),
        )
        val state = AndroidAppConfigState(
            content = AndroidAppConfigContent.Failed(failure),
            mutation = AndroidAppConfigMutation.Idle,
            nextRequestValue = 2L,
        )

        compose.setContent {
            AuthoritativeSessionFailureEffect(
                shouldReject = settingsRequireSessionRejection(
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = state,
                ),
                onReject = { rejections += 1 },
            )
        }

        compose.waitForIdle()
        assertEquals(1, rejections)
    }

    @Test
    fun shellRendersTheContractDestinations() {
        compose.setShell()

        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertIsDisplayed()
        listOf("Files", "Search", "Transfers", "Account").forEach { label ->
            compose.onAllNodes(hasText(label)).onFirst().assertIsDisplayed()
        }
        compose.onAllNodes(hasContentDescription("Transfers")).assertCountEquals(0)
    }

    @Test
    fun selectingADestinationRetitlesTheTopBar() {
        compose.setShell()

        compose.onNodeWithText("Transfers").performClick()

        compose.onAllNodes(hasText("Transfers")).assertCountEquals(2)
    }

    @Test
    fun nestedFolderUsesItsNameAndBackAction() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setShell(
            filesState = nestedFilesState(),
            onFilesEvent = events::add,
        )

        compose.onNodeWithText("Shows").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf(FilesBrowserEvent.NavigateBack), events)
    }

    @Test
    fun tabletWidthUsesNavigationRail() {
        compose.setContent {
            PutioTheme {
                Box(modifier = Modifier.requiredSize(width = 700.dp, height = 500.dp)) {
                    MobileShell(
                        playbackPlayerFactory = NoAudioSessionFactory,
                        filesState = emptyFilesState(),
                        accountSettingsState = readyAccountSettingsState(),
                        appConfigState = readyAndroidAppConfigState(),
                        account = Account,
                        playbackRepository = ConversionRepository,
                        sessionId = Session,
                        onFilesEvent = { true },
                        onAccountSettingsEvent = {},
                        onPlaybackAuthenticationRequired = {},
                        onSignOut = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(MOBILE_NAV_RAIL_TAG).assertExists()
        compose.onAllNodesWithTag(MOBILE_NAV_BAR_TAG).assertCountEquals(0)

        // The rail narrows the content column below 600dp; device class still comes from the window.
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_ABOUT_ROW_TAG))
        compose.onNodeWithTag(MOBILE_ABOUT_ROW_TAG).performClick()
        compose.onNodeWithText("Tablet").assertIsDisplayed()
    }

    @Test
    fun filesTopBarSelectsSortAndOtherDestinationsHideIt() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setShell(
            filesState = readyFilesState(FilesSort.NAME_ASCENDING),
            onFilesEvent = events::add,
        )

        compose.onNodeWithTag(MOBILE_FILES_SORT_TAG)
            .assertIsEnabled()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Name, A–Z",
                ),
            )
            .performClick()
        val sortOptions = listOf(
            "Name, A–Z",
            "Name, Z–A",
            "Size, smallest first",
            "Size, largest first",
            "Date added, oldest first",
            "Date added, newest first",
            "Date modified, oldest first",
            "Date modified, newest first",
            "Type, A–Z",
            "Type, Z–A",
            "Unwatched first",
            "Watched first",
        )
        sortOptions.forEachIndexed { index, label ->
            val option = compose.onNodeWithText(label)
                .assertExists()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            if (index == 0) {
                option.assertIsSelected()
            } else {
                option.assertIsNotSelected()
            }
        }
        compose.onNodeWithText("Size, largest first").performClick()

        assertEquals(FilesBrowserEvent.SelectSort(FilesSort.SIZE_DESCENDING), events.last())

        compose.onNodeWithText("Transfers").performClick()
        compose.onAllNodesWithTag(MOBILE_FILES_SORT_TAG).assertCountEquals(0)
    }

    @Test
    fun filesSortIsDisabledWhileARefreshIsRunning() {
        val ready = readyFilesState(FilesSort.NAME_ASCENDING)
        val refreshing = ready.copy(
            stack = ready.stack.dropLast(1) + ready.current.copy(
                operation = FilesFolderOperation.Loading(
                    requestId = FilesRequestId(12L),
                    intent = FilesFolderOperationIntent.Refresh,
                    phase = FilesFolderOperationPhase.RELOADING,
                ),
            ),
        )
        compose.setShell(filesState = refreshing)

        compose.onNodeWithTag(MOBILE_FILES_SORT_TAG).assertIsNotEnabled()
    }

    @Test
    fun failedSortReloadCanSelectTheDisplayedSort() {
        val ready = readyFilesState(FilesSort.NAME_ASCENDING)
        val failed = ready.copy(
            stack = ready.stack.dropLast(1) + ready.current.copy(
                operation = FilesFolderOperation.Failed(
                    failure = FilesFailure.Unexpected(IllegalStateException("reload failed")),
                    intent = FilesFolderOperationIntent.Sort(FilesSort.SIZE_DESCENDING),
                    phase = FilesFolderOperationPhase.RELOADING,
                ),
            ),
        )
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setShell(filesState = failed, onFilesEvent = events::add)

        compose.onNodeWithTag(MOBILE_FILES_SORT_TAG).assertIsEnabled().performClick()
        compose.onNodeWithText("Name, A–Z").assertIsSelected().performClick()

        assertEquals(FilesBrowserEvent.SelectSort(FilesSort.NAME_ASCENDING), events.last())
    }

    @Test
    fun openFilesSortMenuClosesWhenLoadingStarts() {
        var filesState by mutableStateOf(readyFilesState(FilesSort.NAME_ASCENDING))
        val events = mutableListOf<FilesBrowserEvent>()
        compose.setContent {
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = filesState,
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = events::add,
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }

        compose.onNodeWithTag(MOBILE_FILES_SORT_TAG).performClick()
        compose.onNodeWithText("Size, largest first").assertIsDisplayed()

        compose.runOnIdle {
            filesState = filesState.copy(
                stack = filesState.stack.dropLast(1) + filesState.current.copy(
                    operation = FilesFolderOperation.Loading(
                        requestId = FilesRequestId(13L),
                        intent = FilesFolderOperationIntent.Refresh,
                        phase = FilesFolderOperationPhase.RELOADING,
                    ),
                ),
            )
        }

        compose.onAllNodesWithText("Size, largest first").assertCountEquals(0)
        assertTrue(events.isEmpty())
    }

    @Test
    fun secureStorageFailureHidesSignInWhenOAuthIsConfigured() {
        compose.setContent {
            PutioTheme {
                MobileSignedOutScreen(
                    reason = MobileSignedOutReason.SecureStorageUnavailable,
                    canSignIn = true,
                    onSignIn = {},
                )
            }
        }

        compose.onNodeWithText("Secure storage is unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("Sign in").assertCountEquals(0)
    }

    @Test
    fun phoneShellForwardsAccountAndAppSettingEvents() {
        val accountEvents = mutableListOf<AccountSettingsEvent>()
        val appConfigEvents = mutableListOf<AndroidAppConfigEvent>()
        compose.setShell(
            onAccountSettingsEvent = accountEvents::add,
            onAppConfigEvent = appConfigEvents::add,
        )

        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Show subtitles"))
        compose.onNodeWithText("Show subtitles").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Autoplay next video"))
        compose.onNodeWithText("Autoplay next video").performClick()

        assertEquals(
            listOf(
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.ShowSubtitles, enabled = false),
                ),
            ),
            accountEvents,
        )
        assertEquals(
            listOf(
                AndroidAppConfigEvent.ChangeRequested(
                    AndroidAppConfigChange.AutoplayNextVideo(enabled = true),
                ),
            ),
            appConfigEvents,
        )
    }

    @Test
    fun compactPhoneShellBringsRecoverableFailureIntoView() {
        var settingsState by mutableStateOf(readyAccountSettingsState())
        compose.setContent {
            PutioTheme {
                Box(modifier = Modifier.requiredSize(width = 360.dp, height = 240.dp)) {
                    MobileShell(
                        playbackPlayerFactory = NoAudioSessionFactory,
                        filesState = emptyFilesState(),
                        accountSettingsState = settingsState,
                        appConfigState = readyAndroidAppConfigState(),
                        account = Account,
                        playbackRepository = ConversionRepository,
                        sessionId = Session,
                        onFilesEvent = { true },
                        onAccountSettingsEvent = { event ->
                            if (event is AccountSettingsEvent.ChangeRequested) {
                                settingsState =
                                    readyAccountSettingsState(
                                        mutation =
                                            AccountSettingsMutation.Failed(
                                                change = event.change,
                                                failure =
                                                    AccountSettingsFailure.Unexpected(
                                                        IllegalStateException("offline"),
                                                    ),
                                                previousPreferences = DefaultAccountSettingsPreferences,
                                                operation = AccountSettingsMutation.Operation.Save,
                                            ),
                                    )
                            }
                        },
                        onPlaybackAuthenticationRequired = {},
                        onSignOut = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Show subtitles"))
        compose.onNodeWithText("Show subtitles").assertIsDisplayed().performClick()

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).assertIsDisplayed()
        compose.onNodeWithText("Couldn’t save this setting").assertIsDisplayed()
    }

    @Test
    fun tabletShellForwardsAccountAndAppSettingEvents() {
        val accountEvents = mutableListOf<AccountSettingsEvent>()
        val appConfigEvents = mutableListOf<AndroidAppConfigEvent>()
        compose.setContent {
            PutioTheme {
                Box(modifier = Modifier.requiredSize(width = 700.dp, height = 500.dp)) {
                    MobileShell(
                        playbackPlayerFactory = NoAudioSessionFactory,
                        filesState = emptyFilesState(),
                        accountSettingsState = readyAccountSettingsState(),
                        appConfigState = readyAndroidAppConfigState(),
                        account = Account,
                        playbackRepository = ConversionRepository,
                        sessionId = Session,
                        onFilesEvent = { true },
                        onAccountSettingsEvent = accountEvents::add,
                        onAppConfigEvent = appConfigEvents::add,
                        onPlaybackAuthenticationRequired = {},
                        onSignOut = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithText("Show subtitles").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Autoplay next video"))
        compose.onNodeWithText("Autoplay next video").performClick()

        assertEquals(
            listOf(
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.ShowSubtitles, enabled = false),
                ),
            ),
            accountEvents,
        )
        assertEquals(
            listOf(
                AndroidAppConfigEvent.ChangeRequested(
                    AndroidAppConfigChange.AutoplayNextVideo(enabled = true),
                ),
            ),
            appConfigEvents,
        )
    }

    @Test
    fun contentNavigationCollectsOnceAndUsesTheCurrentFilesCallback() {
        val deliveries = Channel<FilesItem>(Channel.UNLIMITED)
        var subscriptions = 0
        val navigation = flow {
            subscriptions += 1
            emitAll(deliveries.receiveAsFlow())
        }
        val oldEvents = mutableListOf<FilesBrowserEvent>()
        val currentEvents = mutableListOf<FilesBrowserEvent>()
        var useCurrentCallback by mutableStateOf(false)
        val item = (videoFilesState().current.content as FilesContent.Ready).items.single()
        compose.setContent {
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = if (useCurrentCallback) {
                        { event -> currentEvents.add(event) }
                    } else {
                        { event -> oldEvents.add(event) }
                    },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    contentNavigation = navigation,
                    onSignOut = {},
                )
            }
        }
        compose.runOnIdle {
            assertEquals(1, subscriptions)
            useCurrentCallback = true
        }
        compose.runOnIdle {
            assertEquals(1, subscriptions)
            assertTrue(deliveries.trySend(item).isSuccess)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(oldEvents.isEmpty())
            assertEquals(listOf(FilesBrowserEvent.OpenExternalItem(item)), currentEvents)
            assertEquals(1, subscriptions)
            deliveries.close()
        }
    }

    @Test
    fun rejectedSearchNavigationKeepsSearchVisibleAndPendingDeleteIntact() {
        val deliveries = Channel<FilesItem>(Channel.UNLIMITED)
        val navigation = deliveries.receiveAsFlow()
        val retained = pendingShellDeleteState()
        val events = mutableListOf<FilesBrowserEvent>()
        val resolved = shellResolvedFolder()
        compose.setContent {
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = retained,
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { event ->
                        events.add(event)
                        val transition = FilesBrowserReducer.reduce(retained, event)
                        assertEquals(retained, transition.state)
                        transition.consumed
                    },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    contentNavigation = navigation,
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsDisplayed()
        compose.runOnIdle { assertTrue(deliveries.trySend(resolved).isSuccess) }
        compose.waitUntil { events.any { it is FilesBrowserEvent.OpenExternalItem } }
        compose.onNodeWithText("This item cannot be opened right now. Check Files, then try again.").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsDisplayed()
        compose.onNode(hasText("Search") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).assertIsSelected()
        compose.onNode(hasText("Files") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(listOf(FilesBrowserEvent.OpenExternalItem(resolved)),
                events.filterIsInstance<FilesBrowserEvent.OpenExternalItem>())
            deliveries.close()
        }
    }

    @Test
    fun acceptedDefaultSortChangeInvalidatesSortOrderOnceAndNotOnFirstLoad() {
        val events = mutableListOf<FilesBrowserEvent>()
        // Starts as a server value this app does not know; saving a known sort must still invalidate.
        var settingsState by mutableStateOf(readyAccountSettingsState(preferences = DefaultAccountSettingsPreferences))
        compose.setContent {
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFilesState(),
                    accountSettingsState = settingsState,
                    appConfigState = readyAndroidAppConfigState(),
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { events += it; true },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }

        val change = AccountSettingsChange.Sort(FilesSort.DATE_ADDED_DESCENDING)
        val applied = DefaultAccountSettingsPreferences.copy(defaultSort = FilesSort.DATE_ADDED_DESCENDING)
        compose.runOnIdle {
            settingsState = readyAccountSettingsState(
                preferences = applied,
                mutation = AccountSettingsMutation.Saving(
                    requestId = AccountSettingsRequestId(3L),
                    change = change,
                    previousPreferences = DefaultAccountSettingsPreferences,
                    operation = AccountSettingsMutation.Operation.Save,
                ),
            )
        }
        compose.runOnIdle { assertEquals(emptyList<FilesBrowserEvent>(), events) }

        // Accepted write, refresh failed: the server holds the new order, so listings are stale now.
        compose.runOnIdle {
            settingsState = readyAccountSettingsState(
                preferences = applied,
                mutation = AccountSettingsMutation.Failed(
                    change = change,
                    failure = AccountSettingsFailure.Unexpected(IllegalStateException("offline")),
                    previousPreferences = DefaultAccountSettingsPreferences,
                    operation = AccountSettingsMutation.Operation.Refresh,
                ),
            )
        }
        compose.runOnIdle {
            assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.InvalidateSortOrder), events)
        }

        compose.runOnIdle { settingsState = readyAccountSettingsState(preferences = applied) }
        compose.runOnIdle {
            assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.InvalidateSortOrder), events)
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setShell(
        transferDraft: MobileTransferDraft = MobileTransferDraft(),
        contentNavigation: kotlinx.coroutines.flow.Flow<FilesItem> = kotlinx.coroutines.flow.emptyFlow(),
        filesState: FilesBrowserState = emptyFilesState(),
        onFilesEvent: (FilesBrowserEvent) -> Boolean = { true },
        onAccountSettingsEvent: (AccountSettingsEvent) -> Unit = {},
        onAppConfigEvent: (AndroidAppConfigEvent) -> Unit = {},
    ) {
        setContent {
            PutioTheme {
                MobileShell(
                    transferDraft = transferDraft,
                    contentNavigation = contentNavigation,
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = filesState,
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = onFilesEvent,
                    onAccountSettingsEvent = onAccountSettingsEvent,
                    onAppConfigEvent = onAppConfigEvent,
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileShellTransfersTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun transferFileAuthenticationFailureRejectsTheSession() {
        val events = mutableListOf<TransfersEvent>()
        var rejections = 0
        compose.setContent {
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersState = resolvingTransfersState(),
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { true },
                    onTransfersEvent = events::add,
                    resolveTransferFile = {
                        FilesRepositoryResult.Failure(
                            FilesFailure.AuthenticationRequired(PutioConfigurationException("expired")),
                        )
                    },
                    onTransferAuthenticationRequired = { rejections += 1 },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }

        compose.waitUntil { rejections == 1 }
        assertEquals(emptyList<TransfersEvent>(), events)
        compose.onAllNodesWithText("Something went wrong").assertCountEquals(0)
    }

    @Test
    fun lateTransferFileResolutionStaysScopedToItsOriginalSession() {
        var sessionId by mutableStateOf(MobileAuthSessionId(1L))
        var transfersState by mutableStateOf(resolvingTransfersState())
        val firstEvents = mutableListOf<TransfersEvent>()
        val secondEvents = mutableListOf<TransfersEvent>()
        val filesEvents = mutableListOf<FilesBrowserEvent>()
        val resolutionStarted = CompletableDeferred<Unit>()
        val releaseResolution = CompletableDeferred<Unit>()
        val resolvedItem =
            FilesItem(
                id = FilesItemId(7L),
                parentId = FilesFolder.Root.id,
                name = "resolved",
                type = PutioFileType.FOLDER,
                sizeBytes = 0L,
                createdAt = "2026-08-31T00:00:00Z",
            )
        compose.setContent {
            val events = if (sessionId == MobileAuthSessionId(1L)) firstEvents else secondEvents
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersState = transfersState,
                    transfersSessionId = sessionId,
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = filesEvents::add,
                    onTransfersEvent = events::add,
                    resolveTransferFile = {
                        withContext(NonCancellable) {
                            resolutionStarted.complete(Unit)
                            releaseResolution.await()
                        }
                        FilesRepositoryResult.Success(resolvedItem)
                    },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }

        compose.waitUntil { resolutionStarted.isCompleted }
        compose.runOnIdle {
            sessionId = MobileAuthSessionId(2L)
            transfersState = TransfersState(TransfersContent.Empty)
        }
        compose.runOnIdle { releaseResolution.complete(Unit) }

        compose.waitForIdle()
        assertEquals(emptyList<TransfersEvent>(), firstEvents)
        assertEquals(emptyList<TransfersEvent>(), secondEvents)
        assertEquals(emptyList<FilesBrowserEvent>(), filesEvents)
    }

    @Test
    fun newShellDisplaysDurableTransferNoticeUntilAcknowledged() {
        val events = mutableListOf<TransfersEvent>()
        var transfersState by mutableStateOf(
            TransfersState(TransfersContent.Empty).copy(
                notice = TransferNotice.FilePreparing(TransferId(7L), TransfersRequestId(3L)),
            ),
        )
        compose.setContent {
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersState = transfersState,
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { true },
                    onTransfersEvent = { event ->
                        events += event
                        if (event == TransfersEvent.DismissNotice(TransfersRequestId(3L))) {
                            transfersState = transfersState.copy(notice = null)
                        }
                    },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("File isn’t ready").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()

        assertEquals(listOf(TransfersEvent.DismissNotice(TransfersRequestId(3L))), events)
        compose.onAllNodesWithText("File isn’t ready").assertCountEquals(0)
    }

    @Test
    fun newShellProcessesDurableTransferNavigationAlreadyInState() {
        val events = mutableListOf<TransfersEvent>()
        val filesEvents = mutableListOf<FilesBrowserEvent>()
        val resolvedItem =
            FilesItem(
                id = FilesItemId(7L),
                parentId = FilesFolder.Root.id,
                name = "resolved",
                type = PutioFileType.FOLDER,
                sizeBytes = 0L,
                createdAt = "2026-08-31T00:00:00Z",
            )
        compose.setContent {
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersState = resolvingTransfersState(),
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = filesEvents::add,
                    onTransfersEvent = events::add,
                    resolveTransferFile = { FilesRepositoryResult.Success(resolvedItem) },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }

        compose.waitUntil(timeoutMillis = 5_000L) {
            events.contains(TransfersEvent.OpenSucceeded(TransfersRequestId(3L)))
        }
        assertEquals(listOf(FilesBrowserEvent.OpenExternalItem(resolvedItem)), filesEvents)
    }

    @Test
    fun rejectedTransferNavigationKeepsTransfersVisibleAndCanRetryAfterDeleteReconciles() {
        var files by mutableStateOf(pendingShellDeleteState())
        val retained = files
        var transfers by mutableStateOf(shellOpenableTransferState())
        val events = mutableListOf<TransfersEvent>()
        compose.setContent {
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = files,
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersState = transfers,
                    transfersSessionId = Session,
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { event ->
                        val transition = FilesBrowserReducer.reduce(files, event)
                        files = transition.state
                        transition.consumed
                    },
                    onTransfersEvent = { event ->
                        events.add(event)
                        transfers = TransfersReducer.reduce(transfers, event).state
                    },
                    resolveTransferFile = { FilesRepositoryResult.Success(shellResolvedFolder()) },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Transfers").performClick()
        compose.onNodeWithContentDescription("Open Completed transfer").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue("Expected OpenFailed; events=$events", events.any { it is TransfersEvent.OpenFailed })
        }
        compose.runOnIdle {
            assertEquals(retained, files)
            assertEquals(TransferNavigation.Failed(FilesFailure.NavigationBlocked), transfers.navigation)
            assertEquals(FilesFailure.NavigationBlocked, events.filterIsInstance<TransfersEvent.OpenFailed>().single().failure)
            assertTrue(events.none { it is TransfersEvent.OpenSucceeded })
        }
        compose.onNodeWithText("OK").performClick()
        compose.onNode(hasText("Transfers") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).assertIsSelected()
        compose.onNode(hasText("Files") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).assertIsNotSelected()
        compose.onNodeWithContentDescription("Open Completed transfer").assertIsEnabled()
        compose.runOnIdle { files = videoFilesState() }
        compose.onNodeWithContentDescription("Open Completed transfer").performClick()
        compose.runOnIdle {
            assertTrue("Expected OpenSucceeded; events=$events", events.any { it is TransfersEvent.OpenSucceeded })
        }
        compose.onNode(hasText("Files") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).assertIsSelected()
        compose.runOnIdle {
            assertEquals(1, events.count { it is TransfersEvent.OpenFailed })
            assertEquals(1, events.count { it is TransfersEvent.OpenSucceeded })
            assertEquals(2, events.count { it is TransfersEvent.Open })
            assertEquals(TransferNavigation.Idle, transfers.navigation)
        }
    }

    @Test
    fun changingTransferSessionMovesVisibilityToTheNewController() {
        val firstEvents = mutableListOf<TransfersEvent>()
        val secondEvents = mutableListOf<TransfersEvent>()
        var sessionId by mutableStateOf(MobileAuthSessionId(1L))
        compose.setContent {
            val events = if (sessionId == MobileAuthSessionId(1L)) firstEvents else secondEvents
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    transfersSessionId = sessionId,
                    account = Account,
                    playbackRepository = ConversionRepository,
                    sessionId = Session,
                    onFilesEvent = { true },
                    onTransfersEvent = events::add,
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Transfers").performClick()
        compose.waitUntil(timeoutMillis = 5_000L) {
            firstEvents.lastOrNull() == TransfersEvent.VisibilityChanged(true)
        }

        compose.runOnIdle { sessionId = MobileAuthSessionId(2L) }
        compose.waitForIdle()

        assertEquals(
            listOf(TransfersEvent.VisibilityChanged(true), TransfersEvent.VisibilityChanged(false)),
            firstEvents,
        )
        assertEquals(listOf(TransfersEvent.VisibilityChanged(true)), secondEvents)
    }

    @Test
    fun transferVisibilityStopsAndRestartsWithTheHostLifecycle() {
        val events = mutableListOf<TransfersEvent>()
        val lifecycleOwner = ShellLifecycleOwner()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                TransfersVisibilityEffect(
                    sessionId = MobileAuthSessionId(1L),
                    onEvent = events::add,
                )
            }
        }

        compose.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.STARTED) }
        compose.waitUntil { events == listOf(TransfersEvent.VisibilityChanged(true)) }

        compose.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.CREATED) }
        compose.waitUntil {
            events ==
                listOf(
                    TransfersEvent.VisibilityChanged(true),
                    TransfersEvent.VisibilityChanged(false),
                )
        }

        compose.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.STARTED) }
        compose.waitUntil {
            events ==
                listOf(
                    TransfersEvent.VisibilityChanged(true),
                    TransfersEvent.VisibilityChanged(false),
                    TransfersEvent.VisibilityChanged(true),
                )
        }
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileShellPlaybackTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun videoRowsOpenAFullScreenPlaybackStateAndNavigateBack() {
        compose.setPlaybackShell()

        compose.onNodeWithText("episode.mkv").performClick()

        compose.onNodeWithText("Video is being prepared").assertIsDisplayed()
        compose.onAllNodesWithTag(MOBILE_NAV_BAR_TAG).assertCountEquals(0)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun audioRowsOpenThePlayerInAudioMode() {
        val requestedTypes = mutableListOf<PlaybackMediaType>()
        compose.setPlaybackShell(
            filesState = mediaFilesState(),
            playbackRepository = EndingPlaybackRepository,
            playbackPlayerFactory = MobilePlayerFactory { _, mediaType ->
                requestedTypes += mediaType
                RecordingPlayer()
            },
        )

        compose.onNodeWithText("song.mp3").performClick()

        compose.onNodeWithTag(MOBILE_AUDIO_COVER_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(MOBILE_NAV_BAR_TAG).assertCountEquals(0)
        assertEquals(setOf(PlaybackMediaType.AUDIO), requestedTypes.toSet())
    }

    @Test
    fun leavingTheShellDetachesFromTheSessionOnce() {
        val session = ListenerCountingPlayer(RecordingPlayer())
        var closed = 0
        var showShell by mutableStateOf(true)
        compose.setContent {
            PutioTheme {
                if (showShell) {
                    MobileShell(
                        playbackPlayerFactory = ShellSessionFactory(session) { closed += 1 },
                        filesState = mediaFilesState(),
                        accountSettingsState = readyAccountSettingsState(),
                        appConfigState = readyAndroidAppConfigState(),
                        account = Account,
                        playbackRepository = EndingPlaybackRepository,
                        sessionId = Session,
                        onFilesEvent = { true },
                        onAccountSettingsEvent = {},
                        onPlaybackAuthenticationRequired = {},
                        onSignOut = {},
                    )
                }
            }
        }
        compose.runOnIdle {
            assertEquals(1, session.attachedListeners)
            assertEquals(0, closed)
        }

        compose.runOnIdle { showShell = false }

        compose.runOnIdle {
            assertEquals(0, session.attachedListeners)
            assertEquals(1, closed)
        }
    }

    @Test
    fun aStoppedShellHoldsNoSessionBinding() {
        val lifecycleOwner = ShellLifecycleOwner().apply { moveTo(Lifecycle.State.RESUMED) }
        val session = ListenerCountingPlayer(RecordingPlayer())
        var connections = 0
        var closes = 0
        val factory = object : MobilePlayerFactory {
            override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Media3Player =
                error("unused")

            override fun connectAudio(
                context: android.content.Context,
                onResult: (Result<Media3Player>) -> Unit,
            ): java.io.Closeable {
                connections += 1
                onResult(Result.success(session))
                return java.io.Closeable { closes += 1 }
            }
        }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner,
            ) {
                PutioTheme {
                    MobileShell(
                        playbackPlayerFactory = factory,
                        filesState = mediaFilesState(),
                        accountSettingsState = readyAccountSettingsState(),
                        appConfigState = readyAndroidAppConfigState(),
                        account = Account,
                        playbackRepository = EndingPlaybackRepository,
                        sessionId = Session,
                        onFilesEvent = { true },
                        onAccountSettingsEvent = {},
                        onPlaybackAuthenticationRequired = {},
                        onSignOut = {},
                    )
                }
            }
        }
        compose.runOnIdle {
            assertEquals(1, connections)
            assertEquals(1, session.attachedListeners)
        }

        compose.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.CREATED) }
        compose.runOnIdle {
            assertEquals(1, closes)
            assertEquals(0, session.attachedListeners)
        }

        compose.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.RESUMED) }
        compose.runOnIdle {
            assertEquals(2, connections)
            assertEquals(1, session.attachedListeners)
        }
    }

    @Test
    fun leavingTheShellBeforeTheSessionAnswersStillCloses() {
        var pending: ((Result<Media3Player>) -> Unit)? = null
        var closed = 0
        var showShell by mutableStateOf(true)
        val lateFactory = object : MobilePlayerFactory {
            override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Media3Player =
                error("unused")

            override fun connectAudio(
                context: android.content.Context,
                onResult: (Result<Media3Player>) -> Unit,
            ): java.io.Closeable {
                pending = onResult
                return java.io.Closeable { closed += 1 }
            }
        }
        compose.setContent {
            PutioTheme {
                if (showShell) {
                    MobileShell(
                        playbackPlayerFactory = lateFactory,
                        filesState = mediaFilesState(),
                        accountSettingsState = readyAccountSettingsState(),
                        appConfigState = readyAndroidAppConfigState(),
                        account = Account,
                        playbackRepository = EndingPlaybackRepository,
                        sessionId = Session,
                        onFilesEvent = { true },
                        onAccountSettingsEvent = {},
                        onPlaybackAuthenticationRequired = {},
                        onSignOut = {},
                    )
                }
            }
        }
        compose.runOnIdle { showShell = false }
        compose.runOnIdle { assertEquals(1, closed) }

        val session = ListenerCountingPlayer(RecordingPlayer())
        compose.runOnIdle { pending?.invoke(Result.success(session)) }
        compose.runOnIdle { assertEquals(0, session.attachedListeners) }
        compose.onAllNodesWithTag(MOBILE_NOW_PLAYING_TAG).assertCountEquals(0)
    }

    @Test
    fun nowPlayingBarFollowsTheAudioSession() {
        val session = RecordingPlayer()
        val factory = ShellSessionFactory(session) {}
        compose.setPlaybackShell(
            filesState = mediaFilesState(),
            playbackRepository = EndingPlaybackRepository,
            playbackPlayerFactory = factory,
        )

        compose.onAllNodesWithTag(MOBILE_NOW_PLAYING_TAG).assertCountEquals(0)

        compose.runOnIdle {
            session.setMediaItem(
                androidx.media3.common.MediaItem.Builder()
                    .setMediaId("9")
                    .setUri("https://example.com/song.mp3")
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle("song.mp3").build())
                    .build(),
            )
            session.prepare()
            session.play()
        }
        compose.onNodeWithTag(MOBILE_NOW_PLAYING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()

        compose.onNodeWithTag(MOBILE_NOW_PLAYING_TOGGLE_TAG).performClick()
        compose.runOnIdle { assertFalse(session.playWhenReady) }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()

        compose.onNodeWithTag(MOBILE_NOW_PLAYING_OPEN_TAG).performClick()
        compose.onNodeWithTag(MOBILE_AUDIO_COVER_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(MOBILE_NAV_BAR_TAG).assertCountEquals(0)
        compose.runOnIdle {
            // The screen adopts the session item; nothing is re-prepared.
            assertEquals(1, session.prepareCalls)
            assertEquals(1, session.mediaItemUpdates)
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(MOBILE_NOW_PLAYING_TAG).assertIsDisplayed()

        compose.onNodeWithTag(MOBILE_NOW_PLAYING_DISMISS_TAG).performClick()
        compose.onAllNodesWithTag(MOBILE_NOW_PLAYING_TAG).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(0, session.mediaItemCount)
            assertFalse(session.released)
            assertEquals(1, factory.audioStops)
        }
    }

    @Test
    fun mediaNotificationTapOpensTheLiveAudioPlayer() {
        var resolves = 0
        val unavailableRepository = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                resolves += 1
                return PlaybackRepositoryResult.Failure(
                    PlaybackFailure.NetworkUnavailable(IllegalStateException("offline")),
                )
            }
            override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
        }
        val session = RecordingPlayer()
        session.setMediaItem(
            androidx.media3.common.MediaItem.Builder()
                .setMediaId("9")
                .setUri("https://example.com/song.mp3")
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle("song.mp3").build())
                .build(),
        )
        session.prepare()
        session.play()
        val pending = kotlinx.coroutines.flow.MutableStateFlow(false)
        val requests = NowPlayingRequests(pending) { pending.value = false }
        compose.setPlaybackShell(
            filesState = mediaFilesState(),
            playbackRepository = unavailableRepository,
            playbackPlayerFactory = ShellSessionFactory(session) {},
            nowPlayingRequests = requests,
        )
        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertIsDisplayed()

        compose.runOnIdle { pending.value = true }

        compose.onNodeWithTag(MOBILE_AUDIO_COVER_TAG).assertIsDisplayed()
        compose.onNodeWithText("song.mp3").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, session.prepareCalls)
            assertTrue(session.playWhenReady)
            assertFalse(pending.value)
            assertEquals(0, resolves)
        }
    }

    @Test
    fun notificationLookupRaceResolvesOnlyWhenTheSessionHasDisappeared() {
        val session = RecordingPlayer()
        val pending = kotlinx.coroutines.flow.MutableStateFlow(false)
        val targets = mutableListOf<PlaybackTarget>()
        val repository = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                targets += target
                return EndingPlaybackRepository.resolve(target)
            }
            override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
        }
        val factory = object : MobilePlayerFactory {
            override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Media3Player =
                error("audio must attach to the session")

            override fun connectAudio(
                context: android.content.Context,
                onResult: (Result<Media3Player>) -> Unit,
            ): java.io.Closeable {
                onResult(Result.success(session))
                return java.io.Closeable {}
            }

            override suspend fun activeAudio(context: android.content.Context): ActiveAudio =
                ActiveAudio(FilesItemId(9L), "song.mp3")
        }
        compose.setPlaybackShell(
            filesState = mediaFilesState(),
            playbackRepository = repository,
            playbackPlayerFactory = factory,
            nowPlayingRequests = NowPlayingRequests(pending) { pending.value = false },
        )
        // Lookup found file 9, but the session is empty when the route actually connects.
        compose.runOnIdle { pending.value = true }
        compose.onNodeWithTag(MOBILE_AUDIO_COVER_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(PlaybackTarget(FilesItemId(9L), "song.mp3", PlaybackMediaType.AUDIO)), targets)
            assertEquals(1, session.prepareCalls)
            assertFalse(pending.value)
        }
    }

    @Test
    fun mediaNotificationTapReplacesAnotherItemsPlaybackRoute() {
        val session = RecordingPlayer()
        session.setMediaItem(
            androidx.media3.common.MediaItem.Builder()
                .setMediaId("9")
                .setUri("https://example.com/song.mp3")
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle("song.mp3").build())
                .build(),
        )
        session.prepare()
        session.play()
        val pending = kotlinx.coroutines.flow.MutableStateFlow(false)
        val requests = NowPlayingRequests(pending) { pending.value = false }
        compose.setPlaybackShell(
            filesState = videoAndAudioFilesState(),
            playbackRepository = VideoConvertsAudioReadyRepository,
            playbackPlayerFactory = ShellSessionFactory(session) {},
            nowPlayingRequests = requests,
        )
        compose.onNodeWithText("episode.mkv").performClick()
        compose.onNodeWithText("Video is being prepared").assertIsDisplayed()

        compose.runOnIdle { pending.value = true }

        compose.onNodeWithTag(MOBILE_AUDIO_COVER_TAG).assertIsDisplayed()
        compose.onAllNodesWithText("Video is being prepared").assertCountEquals(0)
        compose.runOnIdle { assertEquals(1, session.prepareCalls) }
        // The video route was replaced, not stacked: one Back returns to the shell.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithText("episode.mkv").assertIsDisplayed()
    }

    @Test
    fun theShellTouchesTheAudioSessionWhenItStarts() {
        var connections = 0
        var closes = 0
        val factory = object : MobilePlayerFactory {
            override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Media3Player =
                RecordingPlayer()

            override fun connectAudio(
                context: android.content.Context,
                onResult: (Result<Media3Player>) -> Unit,
            ): java.io.Closeable {
                connections += 1
                onResult(Result.failure(IllegalStateException("no session")))
                return java.io.Closeable { closes += 1 }
            }
        }
        compose.setPlaybackShell(playbackPlayerFactory = factory)

        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertIsDisplayed()
        // The now-playing observer is the shell's binding; it stays attached while composed.
        compose.runOnIdle {
            assertEquals(1, connections)
            assertEquals(0, closes)
        }
    }

    @Test
    fun terminalAutoplayRestoresTheFilesRoute() {
        lateinit var player: RecordingPlayer
        compose.setPlaybackShell(
            appConfigState =
                readyAndroidAppConfigState(
                    AndroidAppConfigPreferences(autoplayNextVideo = true),
                ),
            playbackRepository = EndingPlaybackRepository,
            playbackPlayerFactory = MobilePlayerFactory { _, _ -> RecordingPlayer().also { player = it } },
        )

        compose.onNodeWithText("episode.mkv").performClick()
        compose.onNodeWithTag(MOBILE_PLAYER_TAG).assertIsDisplayed()
        compose.runOnIdle { player.updatePlaybackState(Media3Player.STATE_ENDED) }

        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(MOBILE_PLAYER_TAG).assertCountEquals(0)
        compose.onNodeWithText("episode.mkv").assertIsDisplayed()
    }

    @Test
    fun authoritativePlaybackFailureRejectsTheSessionOnce() {
        var rejections = 0
        compose.setPlaybackShell(
            playbackRepository = AuthenticationFailureRepository,
            onPlaybackAuthenticationRequired = { rejections += 1 },
        )

        compose.onNodeWithText("episode.mkv").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { rejections == 1 }

        assertEquals(1, rejections)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setPlaybackShell(
        appConfigState: AndroidAppConfigState = readyAndroidAppConfigState(),
        playbackRepository: PlaybackRepository = ConversionRepository,
        playbackPlayerFactory: MobilePlayerFactory = NoAudioSessionFactory,
        onPlaybackAuthenticationRequired: suspend () -> Unit = {},
        filesState: FilesBrowserState = videoFilesState(),
        nowPlayingRequests: NowPlayingRequests = NowPlayingRequests.None,
    ) {
        setContent {
            PutioTheme {
                MobileShell(
                    nowPlayingRequests = nowPlayingRequests,
                    filesState = filesState,
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = appConfigState,
                    account = Account,
                    playbackRepository = playbackRepository,
                    playbackPlayerFactory = playbackPlayerFactory,
                    sessionId = Session,
                    onFilesEvent = { true },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = onPlaybackAuthenticationRequired,
                    onSignOut = {},
                )
            }
        }
    }
}

private class ShellSessionFactory(
    private val session: Media3Player,
    private val onClose: () -> Unit,
) : MobilePlayerFactory {
    var audioStops = 0

    override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Media3Player =
        error("audio must attach to the session")

    override fun stopAudio(context: android.content.Context) {
        audioStops += 1
    }

    override fun connectAudio(
        context: android.content.Context,
        onResult: (Result<Media3Player>) -> Unit,
    ): java.io.Closeable {
        onResult(Result.success(session))
        return java.io.Closeable(onClose)
    }
}

private val Account = MobileAccount(userId = 42L, username = "user", email = "user@example.com")
private val Session = MobileAuthSessionId(1L)
private val ConversionRepository =
    object : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
        ): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(
                PlaybackResolution.Conversion(PlaybackConversionState.Queued),
            )

        override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
    }
private val AuthenticationFailureRepository =
    object : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
        ): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Failure(
                PlaybackFailure.AuthenticationRequired(PutioConfigurationException("session expired")),
            )

        override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
    }
private val VideoConvertsAudioReadyRepository =
    object : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
        ): PlaybackRepositoryResult<PlaybackResolution> =
            if (target.mediaType == PlaybackMediaType.AUDIO) {
                EndingPlaybackRepository.resolve(target)
            } else {
                ConversionRepository.resolve(target)
            }

        override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
    }
private val EndingPlaybackRepository =
    object : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
        ): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(
                PlaybackResolution.Ready(
                    PlaybackSource(
                        fileId = target.fileId.value,
                        kind = PlaybackSourceKind.MP4,
                        url = credentialUrl("https://example.com/video.mp4"),
                        startFromSeconds = 0.0,
                        subtitles = PlaybackSubtitles.None,
                    ),
                ),
            )

        override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
    }

private fun credentialUrl(value: String): PutioCredentialUrl =
    PutioCredentialUrl::class.java
        .getDeclaredConstructor(String::class.java)
        .newInstance(value)

private fun emptyFilesState(): FilesBrowserState {
    val initial = FilesBrowserReducer.start()
    val requestId = (initial.effect as FilesBrowserEffect.LoadFolder).requestId
    return FilesBrowserReducer.reduce(
        initial.state,
        FilesBrowserEvent.LoadSucceeded(requestId, FilesPage(emptyList(), nextCursor = null)),
    ).state
}

private fun nestedFilesState(): FilesBrowserState {
    val initial = FilesBrowserReducer.start()
    val requestId = (initial.effect as FilesBrowserEffect.LoadFolder).requestId
    val folder = FilesItem(
        id = FilesItemId(7L),
        parentId = FilesFolder.Root.id,
        name = "Shows",
        type = PutioFileType.FOLDER,
        sizeBytes = 0L,
        createdAt = "2026-08-29T00:00:00Z",
    )
    val root = FilesBrowserReducer.reduce(
        initial.state,
        FilesBrowserEvent.LoadSucceeded(requestId, FilesPage(listOf(folder), nextCursor = null)),
    ).state
    val nested = FilesBrowserReducer.reduce(root, FilesBrowserEvent.OpenFolder(folder.id)).state
    check(nested.current.content is FilesContent.Loading)
    return nested
}

private fun readyFilesState(sort: FilesSort): FilesBrowserState {
    val initial = FilesBrowserReducer.start()
    val requestId = (initial.effect as FilesBrowserEffect.LoadFolder).requestId
    return FilesBrowserReducer.reduce(
        initial.state,
        FilesBrowserEvent.LoadSucceeded(
            requestId,
            FilesPage(
                items = listOf(
                    FilesItem(
                        id = FilesItemId(9L),
                        parentId = FilesFolder.Root.id,
                        name = "movie.mkv",
                        type = PutioFileType.VIDEO,
                        sizeBytes = 42L,
                        createdAt = "2026-08-29T00:00:00Z",
                    ),
                ),
                nextCursor = null,
                sort = sort,
            ),
        ),
    ).state
}

private class ShellLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

private fun resolvingTransfersState(): TransfersState =
    TransfersState(
        content = TransfersContent.Empty,
        navigation = TransferNavigation.Resolving(TransferFileId(7L), TransfersRequestId(3L)),
    )

private fun videoFilesState(): FilesBrowserState {
    val initial = FilesBrowserReducer.start()
    val requestId = (initial.effect as FilesBrowserEffect.LoadFolder).requestId
    val video = FilesItem(
        id = FilesItemId(8L),
        parentId = FilesFolder.Root.id,
        name = "episode.mkv",
        type = PutioFileType.VIDEO,
        sizeBytes = 1L,
        createdAt = "2026-08-29T00:00:00Z",
    )
    return FilesBrowserReducer.reduce(
        initial.state,
        FilesBrowserEvent.LoadSucceeded(requestId, FilesPage(listOf(video), nextCursor = null)),
    ).state
}

private fun mediaFilesState(): FilesBrowserState {
    val initial = FilesBrowserReducer.start()
    val requestId = (initial.effect as FilesBrowserEffect.LoadFolder).requestId
    val audio = FilesItem(
        id = FilesItemId(9L),
        parentId = FilesFolder.Root.id,
        name = "song.mp3",
        type = PutioFileType.AUDIO,
        sizeBytes = 1L,
        createdAt = "2026-08-29T00:00:00Z",
    )
    return FilesBrowserReducer.reduce(
        initial.state,
        FilesBrowserEvent.LoadSucceeded(requestId, FilesPage(listOf(audio), nextCursor = null)),
    ).state
}

private fun videoAndAudioFilesState(): FilesBrowserState {
    val initial = FilesBrowserReducer.start()
    val requestId = (initial.effect as FilesBrowserEffect.LoadFolder).requestId
    val video = FilesItem(FilesItemId(8L), FilesFolder.Root.id, "episode.mkv", PutioFileType.VIDEO, 1L, "2026-08-29T00:00:00Z")
    val audio = FilesItem(FilesItemId(9L), FilesFolder.Root.id, "song.mp3", PutioFileType.AUDIO, 1L, "2026-08-29T00:00:00Z")
    return FilesBrowserReducer.reduce(
        initial.state,
        FilesBrowserEvent.LoadSucceeded(requestId, FilesPage(listOf(video, audio), nextCursor = null)),
    ).state
}

private fun pendingShellDeleteState(): FilesBrowserState = FilesBrowserReducer.reduce(
    videoFilesState(), FilesBrowserEvent.Delete(FilesFolder.Root.id, FilesItemId(8L), FilesDeleteMode.PERMANENT),
).state

private fun shellResolvedFolder(): FilesItem = FilesItem(
    FilesItemId(7L), FilesFolder.Root.id, "resolved folder", PutioFileType.FOLDER, 0L, "2026-09-06",
)

private fun shellOpenableTransferState(): TransfersState = TransfersState(
    TransfersContent.Ready(listOf(TransferItem(
        id = TransferId(7L), name = "Completed transfer", status = AppTransferStatus.Completed,
        fileId = TransferFileId(7L), sizeBytes = 1.0, percentDone = 100.0,
        downloadSpeedBytesPerSecond = null, uploadSpeedBytesPerSecond = null,
        estimatedSecondsRemaining = null, availability = null, hasError = false,
        createdAt = "2026-09-06", userFileExists = true,
    )), TransfersPaging.Complete),
)
