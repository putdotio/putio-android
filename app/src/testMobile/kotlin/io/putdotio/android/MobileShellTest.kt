package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileSignedOutReason
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult
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
import org.junit.Assert.assertEquals
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
                        account = Account,
                        onFilesEvent = {},
                        onSignOut = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(MOBILE_NAV_RAIL_TAG).assertExists()
        compose.onAllNodesWithTag(MOBILE_NAV_BAR_TAG).assertCountEquals(0)
    }

    @Test
    fun transferFileAuthenticationFailureRejectsTheSession() {
        val events = mutableListOf<TransfersEvent>()
        var rejections = 0
        compose.setContent {
            PutioTheme {
                MobileShell(
                    filesState = emptyFilesState(),
                    transfersState = resolvingTransfersState(),
                    account = Account,
                    onFilesEvent = {},
                    onTransfersEvent = events::add,
                    resolveTransferFile = {
                        FilesRepositoryResult.Failure(
                            FilesFailure.AuthenticationRequired(PutioConfigurationException("expired")),
                        )
                    },
                    onTransferAuthenticationRequired = { rejections += 1 },
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
                    transfersState = transfersState,
                    transfersSessionId = sessionId,
                    account = Account,
                    onFilesEvent = filesEvents::add,
                    onTransfersEvent = events::add,
                    resolveTransferFile = {
                        withContext(NonCancellable) {
                            resolutionStarted.complete(Unit)
                            releaseResolution.await()
                        }
                        FilesRepositoryResult.Success(resolvedItem)
                    },
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
                    transfersState = transfersState,
                    account = Account,
                    onFilesEvent = {},
                    onTransfersEvent = { event ->
                        events += event
                        if (event == TransfersEvent.DismissNotice(TransfersRequestId(3L))) {
                            transfersState = transfersState.copy(notice = null)
                        }
                    },
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
                    transfersState = resolvingTransfersState(),
                    account = Account,
                    onFilesEvent = filesEvents::add,
                    onTransfersEvent = events::add,
                    resolveTransferFile = { FilesRepositoryResult.Success(resolvedItem) },
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
                    transfersSessionId = sessionId,
                    account = Account,
                    onFilesEvent = {},
                    onTransfersEvent = events::add,
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

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setShell(
        filesState: FilesBrowserState = emptyFilesState(),
        onFilesEvent: (FilesBrowserEvent) -> Unit = {},
    ) {
        setContent {
            PutioTheme {
                MobileShell(
                    filesState = filesState,
                    account = Account,
                    onFilesEvent = onFilesEvent,
                    onSignOut = {},
                )
            }
        }
    }

    private companion object {
        val Account = MobileAccount(userId = 42L, username = "user", email = "user@example.com")
    }
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
