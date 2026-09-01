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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.FilesSort
<<<<<<< HEAD
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PlaybackConversionState
=======
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.transfers.TransferFileId
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferNavigation
import io.putdotio.android.transfers.TransferNotice
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersRequestId
import io.putdotio.android.transfers.TransfersState
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
>>>>>>> origin/main
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
                        filesState = emptyFilesState(),
                        accountSettingsState = readyAccountSettingsState(),
                        account = Account,
<<<<<<< HEAD
                        playbackRepository = ConversionRepository,
                        onFilesEvent = {},
                        onPlaybackAuthenticationRequired = {},
=======
                        sessionId = Session,
                        onFilesEvent = {},
                        onAccountSettingsEvent = {},
>>>>>>> origin/main
                        onSignOut = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(MOBILE_NAV_RAIL_TAG).assertExists()
        compose.onAllNodesWithTag(MOBILE_NAV_BAR_TAG).assertCountEquals(0)
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
                    filesState = filesState,
                    accountSettingsState = readyAccountSettingsState(),
                    account = Account,
<<<<<<< HEAD
                    playbackRepository = ConversionRepository,
                    onFilesEvent = events::add,
                    onPlaybackAuthenticationRequired = {},
=======
                    sessionId = Session,
                    onFilesEvent = events::add,
                    onAccountSettingsEvent = {},
>>>>>>> origin/main
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
    fun sortMenuVisibilityClosesSynchronouslyWhenDisabled() {
        assertTrue(shouldShowFilesSortMenu(expanded = true, enabled = true))
        assertFalse(shouldShowFilesSortMenu(expanded = true, enabled = false))
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
<<<<<<< HEAD
    fun videoRowsOpenAFullScreenPlaybackStateAndNavigateBack() {
        compose.setShell(filesState = videoFilesState())

        compose.onNodeWithText("episode.mkv").performClick()

        compose.onNodeWithText("Video is being prepared").assertIsDisplayed()
        compose.onAllNodesWithTag(MOBILE_NAV_BAR_TAG).assertCountEquals(0)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun authoritativePlaybackFailureRejectsTheSessionOnce() {
        var rejections = 0
        compose.setShell(
            filesState = videoFilesState(),
            playbackRepository = AuthenticationFailureRepository,
            onPlaybackAuthenticationRequired = { rejections += 1 },
        )

        compose.onNodeWithText("episode.mkv").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { rejections == 1 }

        assertEquals(1, rejections)
=======
    fun phoneShellForwardsAccountSettingEvents() {
        val events = mutableListOf<AccountSettingsEvent>()
        compose.setShell(onAccountSettingsEvent = events::add)

        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithText("Show subtitles").performClick()

        assertEquals(
            listOf(
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.ShowSubtitles, enabled = false),
                ),
            ),
            events,
        )
    }

    @Test
    fun compactPhoneShellBringsRecoverableFailureIntoView() {
        var settingsState by mutableStateOf(readyAccountSettingsState())
        compose.setContent {
            PutioTheme {
                Box(modifier = Modifier.requiredSize(width = 360.dp, height = 240.dp)) {
                    MobileShell(
                        filesState = emptyFilesState(),
                        accountSettingsState = settingsState,
                        account = Account,
                        sessionId = Session,
                        onFilesEvent = {},
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
                        onSignOut = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToIndex(3)
        compose.onNodeWithText("Show subtitles").assertIsDisplayed().performClick()

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).assertIsDisplayed()
        compose.onNodeWithText("Couldn’t save this setting").assertIsDisplayed()
    }

    @Test
    fun tabletShellForwardsAccountSettingEvents() {
        val events = mutableListOf<AccountSettingsEvent>()
        compose.setContent {
            PutioTheme {
                Box(modifier = Modifier.requiredSize(width = 700.dp, height = 500.dp)) {
                    MobileShell(
                        filesState = emptyFilesState(),
                        accountSettingsState = readyAccountSettingsState(),
                        account = Account,
                        sessionId = Session,
                        onFilesEvent = {},
                        onAccountSettingsEvent = events::add,
                        onSignOut = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithText("Show subtitles").performClick()

        assertEquals(
            listOf(
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.ShowSubtitles, enabled = false),
                ),
            ),
            events,
        )
    }

    @Test
    fun transferFileAuthenticationFailureRejectsTheSession() {
        val events = mutableListOf<TransfersEvent>()
        var rejections = 0
        compose.setContent {
            PutioTheme {
                MobileShell(
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    transfersState = resolvingTransfersState(),
                    account = Account,
                    sessionId = Session,
                    onFilesEvent = {},
                    onTransfersEvent = events::add,
                    resolveTransferFile = {
                        FilesRepositoryResult.Failure(
                            FilesFailure.AuthenticationRequired(PutioConfigurationException("expired")),
                        )
                    },
                    onTransferAuthenticationRequired = { rejections += 1 },
                    onAccountSettingsEvent = {},
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
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    transfersState = transfersState,
                    transfersSessionId = sessionId,
                    account = Account,
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
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    transfersState = transfersState,
                    account = Account,
                    sessionId = Session,
                    onFilesEvent = {},
                    onTransfersEvent = { event ->
                        events += event
                        if (event == TransfersEvent.DismissNotice(TransfersRequestId(3L))) {
                            transfersState = transfersState.copy(notice = null)
                        }
                    },
                    onAccountSettingsEvent = {},
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
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    transfersState = resolvingTransfersState(),
                    account = Account,
                    sessionId = Session,
                    onFilesEvent = filesEvents::add,
                    onTransfersEvent = events::add,
                    resolveTransferFile = { FilesRepositoryResult.Success(resolvedItem) },
                    onAccountSettingsEvent = {},
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
    fun changingTransferSessionMovesVisibilityToTheNewController() {
        val firstEvents = mutableListOf<TransfersEvent>()
        val secondEvents = mutableListOf<TransfersEvent>()
        var sessionId by mutableStateOf(MobileAuthSessionId(1L))
        compose.setContent {
            val events = if (sessionId == MobileAuthSessionId(1L)) firstEvents else secondEvents
            PutioTheme {
                MobileShell(
                    filesState = emptyFilesState(),
                    accountSettingsState = readyAccountSettingsState(),
                    transfersSessionId = sessionId,
                    account = Account,
                    sessionId = Session,
                    onFilesEvent = {},
                    onTransfersEvent = events::add,
                    onAccountSettingsEvent = {},
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
>>>>>>> origin/main
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setShell(
        filesState: FilesBrowserState = emptyFilesState(),
<<<<<<< HEAD
        playbackRepository: PlaybackRepository = ConversionRepository,
        onFilesEvent: (FilesBrowserEvent) -> Unit = {},
        onPlaybackAuthenticationRequired: suspend () -> Unit = {},
=======
        accountSettingsState: AccountSettingsState = readyAccountSettingsState(),
        onFilesEvent: (FilesBrowserEvent) -> Unit = {},
        onAccountSettingsEvent: (AccountSettingsEvent) -> Unit = {},
>>>>>>> origin/main
    ) {
        setContent {
            PutioTheme {
                MobileShell(
                    filesState = filesState,
                    accountSettingsState = accountSettingsState,
                    account = Account,
<<<<<<< HEAD
                    playbackRepository = playbackRepository,
                    onFilesEvent = onFilesEvent,
                    onPlaybackAuthenticationRequired = onPlaybackAuthenticationRequired,
=======
                    sessionId = Session,
                    onFilesEvent = onFilesEvent,
                    onAccountSettingsEvent = onAccountSettingsEvent,
>>>>>>> origin/main
                    onSignOut = {},
                )
            }
        }
    }

    private companion object {
        val Account = MobileAccount(userId = 42L, username = "user", email = "user@example.com")
<<<<<<< HEAD
        val ConversionRepository =
            object : PlaybackRepository {
                override suspend fun resolve(
                    target: PlaybackTarget,
                ): PlaybackRepositoryResult<PlaybackResolution> =
                    PlaybackRepositoryResult.Success(
                        PlaybackResolution.Conversion(PlaybackConversionState.Queued),
                    )
            }
        val AuthenticationFailureRepository =
            object : PlaybackRepository {
                override suspend fun resolve(
                    target: PlaybackTarget,
                ): PlaybackRepositoryResult<PlaybackResolution> =
                    PlaybackRepositoryResult.Failure(
                        PlaybackFailure.AuthenticationRequired(PutioConfigurationException("session expired")),
                    )
            }
=======
        val Session = MobileAuthSessionId(1L)
>>>>>>> origin/main
    }
}

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
