package io.putdotio.android

import android.content.Context
import android.text.format.Formatter
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAccountStorage
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.TunnelRouteName
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigFailure
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigRequestId
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileAccountScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun accountIdentitySettingsAndSignOutAreAvailable() {
        var signedOut = false
        val events = mutableListOf<AccountSettingsEvent>()
        setAccountContent(
            state = readyAccountSettingsState(),
            events = events,
            onSignOut = { signedOut = true },
        )

        compose.onNodeWithText("putio-user").assertIsDisplayed()
        compose.onNodeWithText("user@example.com").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedStorage =
            "${Formatter.formatShortFileSize(context, GIBIBYTE)} used · " +
                "${Formatter.formatShortFileSize(context, 3 * GIBIBYTE)} free"
        compose.onNodeWithText(expectedStorage).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG)
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.25f, 0f..1f))
        compose.onNodeWithText("Show subtitles").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Resume where you left off"))
        compose.onNodeWithText("Resume where you left off").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Sign out"))
        compose.onNodeWithText("Sign out").performClick()

        assertEquals(
            listOf(
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.ShowSubtitles, enabled = false),
                ),
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.ResumePlayback, enabled = false),
                ),
            ),
            events,
        )
        assertTrue(signedOut)
    }

    @Test
    fun malformedHttpsAvatarUsesDeterministicFallback() {
        setAccountContent(
            state = readyAccountSettingsState(),
            events = mutableListOf(),
            account = Account.copy(avatarUrl = "https://example.com:invalid/avatar.png"),
        )

        compose.onNodeWithTag(
            MOBILE_ACCOUNT_AVATAR_FALLBACK_TAG,
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun avatarUrlPolicyAcceptsOnlyWellFormedHttpsUrls() {
        assertTrue("https://example.com/avatar.png".isSupportedAvatarUrl())
        listOf(
            "http://example.com/avatar.png",
            "https:///avatar.png",
            "https://user@example.com/avatar.png",
            "https://example.com:invalid/avatar.png",
            "https://example.com:/avatar.png",
            "https://example.com:0/avatar.png",
            "https://example.com:65536/avatar.png",
        ).forEach { url ->
            assertFalse("Expected avatar URL to be rejected: $url", url.isSupportedAvatarUrl())
        }
    }

    @Test
    fun quotaProgressClampsInvalidDiskValues() {
        var account by mutableStateOf(
            Account.copy(storage = MobileAccountStorage(availableBytes = -1, sizeBytes = 0, usedBytes = 10)),
        )
        compose.setContent {
            PutioTheme {
                MobileAccountScreen(
                    account = account,
                    sessionId = SessionOne,
                    settingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    onSettingsEvent = {},
                    onAppConfigEvent = {},
                    onSignOut = {},
                )
            }
        }

        compose.onNodeWithTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG)
            .assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        compose.runOnIdle {
            account = account.copy(storage = MobileAccountStorage(availableBytes = 5, sizeBytes = 5, usedBytes = -1))
        }
        compose.onNodeWithTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG)
            .assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        compose.runOnIdle {
            account = account.copy(storage = MobileAccountStorage(availableBytes = 0, sizeBytes = 5, usedBytes = 10))
        }
        compose.onNodeWithTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG)
            .assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h240dp")
    fun compactAccountIdentityRemainsVisible() {
        setAccountContent(
            state = readyAccountSettingsState(),
            events = mutableListOf(),
        )

        compose.onNodeWithText("putio-user").assertIsDisplayed()
        compose.onNodeWithText("user@example.com").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG).assertIsDisplayed()
    }

    @Test
    fun turningOffTrashRequiresConfirmation() {
        val events = mutableListOf<AccountSettingsEvent>()
        setAccountContent(
            state = readyAccountSettingsState(),
            events = events,
        )

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToIndex(7)
        compose.onNodeWithText("Move deleted files to Trash").performClick()
        compose.onNodeWithText("Turn off Trash?").assertIsDisplayed()
        assertTrue(events.isEmpty())

        compose.onNodeWithText("Turn off").performClick()

        assertEquals(
            AccountSettingsEvent.ChangeRequested(
                AccountSettingsChange(AccountSettingsKey.Trash, enabled = false),
            ),
            events.single(),
        )
    }

    @Test
    fun loadingAndFailuresRemainRecoverable() {
        val events = mutableListOf<AccountSettingsEvent>()
        var state by mutableStateOf(
            AccountSettingsState(
                content = AccountSettingsContent.Loading(AccountSettingsRequestId(1L)),
                mutation = AccountSettingsMutation.Idle,
                nextRequestValue = 2L,
            ),
        )
        compose.setContent {
            PutioTheme {
                MobileAccountScreen(
                    account = Account,
                    sessionId = SessionOne,
                    settingsState = state,
                    appConfigState = readyAndroidAppConfigState(),
                    onSettingsEvent = events::add,
                    onAppConfigEvent = {},
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Loading account settings").assertIsDisplayed()

        compose.runOnIdle {
            state =
                AccountSettingsState(
                    content =
                        AccountSettingsContent.Failed(
                            AccountSettingsFailure.AccessDenied(PutioConfigurationException("restricted")),
                        ),
                    mutation = AccountSettingsMutation.Idle,
                    nextRequestValue = 2L,
                )
        }
        compose.onNodeWithText("This app doesn’t have access to account settings.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(AccountSettingsEvent.RetryLoad, events.single())
    }

    @Test
    fun authenticationLoadFailureHasNoRetryAction() {
        val events = mutableListOf<AccountSettingsEvent>()
        setAccountContent(
            state =
                AccountSettingsState(
                    content =
                        AccountSettingsContent.Failed(
                            AccountSettingsFailure.AuthenticationRequired(
                                PutioConfigurationException("invalid token"),
                            ),
                        ),
                    mutation = AccountSettingsMutation.Idle,
                    nextRequestValue = 2L,
                ),
            events = events,
        )

        compose.onNodeWithText("Your session has expired. Sign in again.").assertIsDisplayed()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
        assertTrue(events.isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h240dp")
    fun recoverableMutationFailureStaysVisibleWithoutDisablingRows() {
        val events = mutableListOf<AccountSettingsEvent>()
        val failure = AccountSettingsFailure.Unexpected(IllegalStateException("offline"))
        setAccountContent(
            state =
                readyAccountSettingsState(
                    mutation =
                        AccountSettingsMutation.Failed(
                            change =
                                AccountSettingsChange(
                                    AccountSettingsKey.ShowSubtitles,
                                    enabled = false,
                                ),
                            failure = failure,
                            previousPreferences = DefaultAccountSettingsPreferences,
                            operation = AccountSettingsMutation.Operation.Save,
                        ),
                ),
            events = events,
        )

        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ),
        ).assertExists()
        compose.onNodeWithText("Couldn’t save this setting").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Show subtitles").assertIsEnabled().performClick()

        assertEquals(
            AccountSettingsEvent.ChangeRequested(
                AccountSettingsChange(AccountSettingsKey.ShowSubtitles, enabled = false),
            ),
            events.single(),
        )
    }

    @Test
    fun authoritativeMutationFailureDisablesRowsAndHidesRetry() {
        val events = mutableListOf<AccountSettingsEvent>()
        val failure =
            AccountSettingsFailure.AuthenticationRequired(
                PutioConfigurationException("invalid token"),
            )
        setAccountContent(
            state =
                readyAccountSettingsState(
                    mutation =
                        AccountSettingsMutation.Failed(
                            change =
                                AccountSettingsChange(
                                    AccountSettingsKey.ShowSubtitles,
                                    enabled = false,
                                ),
                            failure = failure,
                            previousPreferences = DefaultAccountSettingsPreferences,
                            operation = AccountSettingsMutation.Operation.Save,
                        ),
                ),
            events = events,
        )

        compose.onNodeWithText("Show subtitles").assertIsNotEnabled()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
        assertTrue(events.isEmpty())
    }

    @Test
    fun refreshFailureUsesConfirmationCopyAndRetriesTheRefresh() {
        val events = mutableListOf<AccountSettingsEvent>()
        val failure = AccountSettingsFailure.Unexpected(IllegalStateException("offline"))
        setAccountContent(
            state =
                readyAccountSettingsState(
                    preferences = DefaultAccountSettingsPreferences.copy(showSubtitles = false),
                    mutation =
                        AccountSettingsMutation.Failed(
                            change =
                                AccountSettingsChange(
                                    AccountSettingsKey.ShowSubtitles,
                                    enabled = false,
                                ),
                            failure = failure,
                            previousPreferences = DefaultAccountSettingsPreferences,
                            operation = AccountSettingsMutation.Operation.Refresh,
                        ),
                ),
            events = events,
        )

        compose.onNodeWithText("Saved, but couldn’t confirm this setting").assertIsDisplayed()
        compose.onAllNodesWithText("Couldn’t save this setting").assertCountEquals(0)
        compose.onNodeWithText("Try again").performClick()

        assertEquals(AccountSettingsEvent.RetryChange, events.single())
    }

    @Test
    fun trashConfirmationDoesNotSurviveASessionReplacement() {
        var sessionId by mutableStateOf(SessionOne)
        compose.setContent {
            PutioTheme {
                MobileAccountScreen(
                    account = Account,
                    sessionId = sessionId,
                    settingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    onSettingsEvent = {},
                    onAppConfigEvent = {},
                    onSignOut = {},
                )
            }
        }

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToIndex(7)
        compose.onNodeWithText("Move deleted files to Trash").performClick()
        compose.onNodeWithText("Turn off Trash?").assertIsDisplayed()

        compose.runOnIdle { sessionId = SessionTwo }

        compose.onAllNodesWithText("Turn off Trash?").assertCountEquals(0)
    }

    @Test
    fun playbackSettingsChooseFormatAndToggleAutoplay() {
        val appConfigEvents = mutableListOf<AndroidAppConfigEvent>()
        setAccountContent(
            state = readyAccountSettingsState(),
            events = mutableListOf(),
            appConfigEvents = appConfigEvents,
        )

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToIndex(9)
        compose.onNodeWithText("Video playback").performClick()
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
            useUnmergedTree = true,
        ).assertExists()
        compose.onAllNodesWithText("Adaptive (HLS)").assertCountEquals(2)
        compose.onNodeWithText("Direct MP4")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Role,
                    androidx.compose.ui.semantics.Role.RadioButton,
                ),
            )
            .performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToIndex(10)
        compose.onNodeWithText("Autoplay next video").performClick()

        assertEquals(
            listOf(
                AndroidAppConfigEvent.ChangeRequested(
                    AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4),
                ),
                AndroidAppConfigEvent.ChangeRequested(
                    AndroidAppConfigChange.AutoplayNextVideo(enabled = true),
                ),
            ),
            appConfigEvents,
        )
    }

    @Test
    fun playbackLoadingAndFailureStaySectionLocalAndRetry() {
        val appConfigEvents = mutableListOf<AndroidAppConfigEvent>()
        var appConfigState by mutableStateOf(
            AndroidAppConfigState(
                content = AndroidAppConfigContent.Loading(AndroidAppConfigRequestId(1L)),
                mutation = AndroidAppConfigMutation.Idle,
                nextRequestValue = 2L,
            ),
        )
        compose.setContent {
            PutioTheme {
                MobileAccountScreen(
                    account = Account,
                    sessionId = SessionOne,
                    settingsState = readyAccountSettingsState(),
                    appConfigState = appConfigState,
                    onSettingsEvent = {},
                    onAppConfigEvent = appConfigEvents::add,
                    onSignOut = {},
                )
            }
        }

        compose.onNodeWithText("Show subtitles").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToIndex(9)
        compose.onNodeWithText("Loading playback settings").assertIsDisplayed()
        compose.runOnIdle {
            appConfigState =
                AndroidAppConfigState(
                    content =
                        AndroidAppConfigContent.Failed(
                            AndroidAppConfigFailure.AccessDenied(
                                PutioConfigurationException("restricted"),
                            ),
                        ),
                    mutation = AndroidAppConfigMutation.Idle,
                    nextRequestValue = 2L,
                )
        }
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToIndex(9)
        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithText("This app doesn’t have access to playback settings.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(listOf(AndroidAppConfigEvent.RetryLoad), appConfigEvents)
    }

    @Test
    fun playbackMutationFailuresRetryAndAuthenticationFailureDisablesControls() {
        val appConfigEvents = mutableListOf<AndroidAppConfigEvent>()
        val change = AndroidAppConfigChange.AutoplayNextVideo(enabled = true)
        val saveFailure = AndroidAppConfigMutation.Failed(
            change = change,
            failure = AndroidAppConfigFailure.Unexpected(IllegalStateException("offline")),
            previousPreferences = DefaultAndroidAppConfigPreferences,
            operation = AndroidAppConfigMutation.Operation.Save,
        )
        var appConfigState by mutableStateOf(readyAndroidAppConfigState(mutation = saveFailure))
        compose.setContent {
            PutioTheme {
                MobileAccountScreen(
                    account = Account,
                    sessionId = SessionOne,
                    settingsState = readyAccountSettingsState(),
                    appConfigState = appConfigState,
                    onSettingsEvent = {},
                    onAppConfigEvent = appConfigEvents::add,
                    onSignOut = {},
                )
            }
        }

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToIndex(10)
        compose.onNodeWithText("Couldn’t save this setting").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf(AndroidAppConfigEvent.RetryChange), appConfigEvents)

        compose.runOnIdle {
            appConfigEvents.clear()
            appConfigState =
                readyAndroidAppConfigState(
                    mutation = saveFailure.copy(operation = AndroidAppConfigMutation.Operation.Refresh),
                )
        }
        compose.onNodeWithText("Saved, but couldn’t confirm this setting").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf(AndroidAppConfigEvent.RetryChange), appConfigEvents)

        compose.runOnIdle {
            appConfigState =
                readyAndroidAppConfigState(
                    mutation =
                        saveFailure.copy(
                            failure =
                                AndroidAppConfigFailure.AuthenticationRequired(
                                    PutioConfigurationException("invalid token"),
                                ),
                        ),
                )
        }
        compose.onNodeWithText("Autoplay next video").assertIsNotEnabled()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
    }

    @Test
    fun tunnelRoutePickerLoadsOnOpenRetriesAndDispatchesTheChosenRoute() {
        val events = mutableListOf<AccountSettingsEvent>()
        var attempts = 0
        setAccountContent(
            state = readyAccountSettingsState(),
            events = events,
            loadTunnelRoutes = {
                attempts += 1
                if (attempts == 1) {
                    AccountSettingsRepositoryResult.Failure(
                        AccountSettingsFailure.Unexpected(IllegalStateException("offline")),
                    )
                } else {
                    AccountSettingsRepositoryResult.Success(
                        listOf(
                            TunnelRouteOption(TunnelRouteName.DEFAULT, "Amsterdam (Direct)"),
                            TunnelRouteOption(TunnelRouteName("cdn77"), "CDN"),
                        ),
                    )
                }
            },
        )
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TUNNEL_ROUTE_ROW_TAG))
        compose.onNodeWithTag(MOBILE_TUNNEL_ROUTE_ROW_TAG).assertTextContains("Direct").performClick()
        compose.onNodeWithText("Couldn’t load proxies").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TUNNEL_ROUTE_RETRY_TAG).performClick()
        compose.onNodeWithText("Amsterdam (Direct)").assertIsDisplayed()
        compose.onNodeWithText("cdn77").performClick()
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(
                listOf<AccountSettingsEvent>(
                    AccountSettingsEvent.ChangeRequested(AccountSettingsChange.Route(TunnelRouteName("cdn77"))),
                ),
                events,
            )
        }
        compose.onNodeWithText("Amsterdam (Direct)").assertDoesNotExist()
    }

    @Test
    fun rejectedRouteSaveShowsOnTheProxyRowOnlyAndRetriesTheExactRoute() {
        val events = mutableListOf<AccountSettingsEvent>()
        val failure = AccountSettingsFailure.RouteUnavailable(PutioConfigurationException("UNAVAILABLE_VALUE"))
        setAccountContent(
            state = readyAccountSettingsState(
                preferences = DefaultAccountSettingsPreferences,
                mutation = AccountSettingsMutation.Failed(
                    change = AccountSettingsChange.Route(TunnelRouteName("cdn77")),
                    failure = failure,
                    previousPreferences = DefaultAccountSettingsPreferences,
                    operation = AccountSettingsMutation.Operation.Save,
                ),
            ),
            events = events,
        )
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TUNNEL_ROUTE_ROW_TAG))
        compose.onNodeWithTag(MOBILE_TUNNEL_ROUTE_ROW_TAG).assertTextContains("Direct")
        compose.onAllNodesWithText("Couldn’t save this setting").assertCountEquals(1)
        compose.onNodeWithText("That proxy isn’t available for your account. Choose another one.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Show subtitles"))
        compose.onNodeWithText("Show subtitles").assertIsDisplayed()
        compose.onAllNodesWithText("Couldn’t save this setting").assertCountEquals(0)
        compose.runOnIdle { assertEquals(listOf<AccountSettingsEvent>(AccountSettingsEvent.RetryChange), events) }
    }

    private fun setAccountContent(
        state: AccountSettingsState,
        events: MutableList<AccountSettingsEvent>,
        onSignOut: () -> Unit = {},
        account: MobileAccount = Account,
        appConfigEvents: MutableList<AndroidAppConfigEvent> = mutableListOf(),
        loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
            AccountSettingsRepositoryResult.Success(emptyList())
        },
    ) {
        compose.setContent {
            PutioTheme {
                MobileAccountScreen(
                    account = account,
                    sessionId = SessionOne,
                    settingsState = state,
                    appConfigState = readyAndroidAppConfigState(),
                    onSettingsEvent = events::add,
                    onAppConfigEvent = appConfigEvents::add,
                    onSignOut = onSignOut,
                    loadTunnelRoutes = loadTunnelRoutes,
                )
            }
        }
    }

    private companion object {
        val Account =
            MobileAccount(
                userId = 42L,
                username = "putio-user",
                email = "user@example.com",
                avatarUrl = null,
                storage =
                    MobileAccountStorage(
                        availableBytes = 3 * GIBIBYTE,
                        sizeBytes = 4 * GIBIBYTE,
                        usedBytes = GIBIBYTE,
                    ),
            )
        val SessionOne = MobileAuthSessionId(1L)
        val SessionTwo = MobileAuthSessionId(2L)
        const val GIBIBYTE = 1_073_741_824L
    }
}
