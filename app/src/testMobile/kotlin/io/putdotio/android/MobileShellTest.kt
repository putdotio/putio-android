package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
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
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.sdk.files.PutioFileType
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
                        sessionId = Session,
                        onFilesEvent = {},
                        onAccountSettingsEvent = {},
                        onSignOut = {},
                    )
                }
            }
        }

        compose.onNodeWithTag(MOBILE_NAV_RAIL_TAG).assertExists()
        compose.onAllNodesWithTag(MOBILE_NAV_BAR_TAG).assertCountEquals(0)
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
        accountSettingsState: AccountSettingsState = readyAccountSettingsState(),
        onFilesEvent: (FilesBrowserEvent) -> Unit = {},
        onAccountSettingsEvent: (AccountSettingsEvent) -> Unit = {},
    ) {
        setContent {
            PutioTheme {
                MobileShell(
                    filesState = filesState,
                    accountSettingsState = accountSettingsState,
                    account = Account,
                    sessionId = Session,
                    onFilesEvent = onFilesEvent,
                    onAccountSettingsEvent = onAccountSettingsEvent,
                    onSignOut = {},
                )
            }
        }
    }

    private companion object {
        val Account = MobileAccount(userId = 42L, username = "user", email = "user@example.com")
        val Session = MobileAuthSessionId(1L)
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
