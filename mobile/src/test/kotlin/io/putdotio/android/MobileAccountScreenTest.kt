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
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.AccountStorage
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesSort
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AppDiagnostics
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.DefaultAccountSettingsPreferences
import io.putdotio.android.settings.DefaultAndroidAppConfigPreferences
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.TunnelRouteName
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.PutioFailure
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigRequestId
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.settings.accountSettingsState
import io.putdotio.android.settings.androidAppConfigState
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.putdotio.android.account.MOBILE_ABOUT_COPIED_TAG
import io.putdotio.android.account.MOBILE_ABOUT_COPY_TAG
import io.putdotio.android.account.MOBILE_ABOUT_DIALOG_TAG
import io.putdotio.android.account.MOBILE_ABOUT_ROW_TAG
import io.putdotio.android.account.MOBILE_ACCOUNT_AVATAR_FALLBACK_TAG
import io.putdotio.android.account.MOBILE_ACCOUNT_LIST_TAG
import io.putdotio.android.account.MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG
import io.putdotio.android.account.MOBILE_DEFAULT_SORT_DIALOG_TAG
import io.putdotio.android.account.MOBILE_DEFAULT_SORT_ROW_TAG
import io.putdotio.android.account.MOBILE_PRIVACY_CONTROLS_ROW_TAG
import io.putdotio.android.account.MOBILE_TUNNEL_ROUTE_RETRY_TAG
import io.putdotio.android.account.MOBILE_TUNNEL_ROUTE_ROW_TAG
import io.putdotio.android.account.MobileAccountScreen

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileAccountScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsSectionsSupportHeadingNavigation() {
        compose.setAccountContent()
        listOf("Files", "Playback", "About").forEach { title ->
            compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
    }

    @Test
    fun accountIdentitySettingsAndSignOutAreAvailable() {
        var signedOut = false
        val events = mutableListOf<AccountSettingsEvent>()
        compose.setAccountContent(
            events = events,
            options = AccountScreenOptions(onSignOut = { signedOut = true }),
        )

        compose.onNodeWithText("putio-user").assertIsDisplayed()
        compose.onNodeWithText("user@example.com").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedStorage =
            "${Formatter.formatShortFileSize(context, GIBIBYTE)} of " +
                "${Formatter.formatShortFileSize(context, 4 * GIBIBYTE)} used"
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
    fun quotaStatesWhatIsFreeWhenTheAccountShowsOptimisticUsage() {
        compose.setAccountContent(
            account = { Account.copy(storage = Account.storage.copy(showOptimisticUsage = true)) },
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithText(
            "${Formatter.formatShortFileSize(context, 3 * GIBIBYTE)} of " +
                "${Formatter.formatShortFileSize(context, 4 * GIBIBYTE)} free",
        ).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG)
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.25f, 0f..1f))
    }

    @Test
    fun malformedHttpsAvatarUsesDeterministicFallback() {
        compose.setAccountContent(
            account = { Account.copy(avatarUrl = "https://example.com:invalid/avatar.png") },
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
            Account.copy(storage = AccountStorage(availableBytes = -1, sizeBytes = 0, usedBytes = 10)),
        )
        compose.setAccountContent(account = { account })

        compose.onNodeWithTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG)
            .assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        compose.runOnIdle {
            account = account.copy(storage = AccountStorage(availableBytes = 5, sizeBytes = 5, usedBytes = -1))
        }
        compose.onNodeWithTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG)
            .assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        compose.runOnIdle {
            account = account.copy(storage = AccountStorage(availableBytes = 0, sizeBytes = 5, usedBytes = 10))
        }
        compose.onNodeWithTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG)
            .assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h240dp")
    fun compactAccountIdentityRemainsVisible() {
        compose.setAccountContent()

        compose.onNodeWithText("putio-user").assertIsDisplayed()
        compose.onNodeWithText("user@example.com").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG).assertIsDisplayed()
    }

    @Test
    fun privacyControlsAreOneRowThatShowsTheirStateAndOpensThePage() {
        val events = mutableListOf<AccountSettingsEvent>()
        var opened = 0
        var state by mutableStateOf(readyAccountSettingsState())
        compose.setAccountContent(
            state = { state },
            events = events,
            options = AccountScreenOptions(onPrivacyControls = { opened += 1 }),
        )

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_PRIVACY_CONTROLS_ROW_TAG))
        compose.onNodeWithTag(MOBILE_PRIVACY_CONTROLS_ROW_TAG)
            .assertTextContains("Privacy controls")
            .assertTextContains("Diagnostics on · Product analytics off")
            .performClick()
        compose.onAllNodesWithText("Strictly necessary").assertCountEquals(0)
        compose.onAllNodesWithText("Diagnostics").assertCountEquals(0)
        compose.onAllNodesWithText("Product analytics").assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(1, opened)
            assertTrue(events.isEmpty())
            state = readyAccountSettingsState(
                DefaultAccountSettingsPreferences.copy(diagnosticsEnabled = false, productAnalyticsEnabled = true),
            )
        }
        compose.onNodeWithTag(MOBILE_PRIVACY_CONTROLS_ROW_TAG)
            .assertTextContains("Diagnostics off · Product analytics on")
    }

    @Test
    fun loadingAndFailuresRemainRecoverable() {
        val events = mutableListOf<AccountSettingsEvent>()
        var state by mutableStateOf(
            accountSettingsState(
                content = AccountSettingsContent.Loading(AccountSettingsRequestId(1L)),
                mutation = AccountSettingsMutation.Idle,
                nextRequestValue = 2L,
            ),
        )
        compose.setAccountContent(state = { state }, events = events)
        compose.onNodeWithText("Loading account settings").assertIsDisplayed()

        compose.runOnIdle {
            state =
                accountSettingsState(
                    content =
                        AccountSettingsContent.Failed(
                            AccountSettingsFailure.Putio(
                                PutioFailure.AccessDenied(PutioConfigurationException("restricted")),
                            ),
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
        compose.setAccountContent(
            state = {
                accountSettingsState(
                    content = AccountSettingsContent.Failed(ExpiredSessionFailure),
                    mutation = AccountSettingsMutation.Idle,
                    nextRequestValue = 2L,
                )
            },
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
        compose.setAccountContent(
            state = { readyAccountSettingsState(mutation = failedChange(SubtitlesOff)) },
            events = events,
        )

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Couldn’t save this setting"))
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assertExists()
        compose.onNodeWithText("Couldn’t save this setting").assertIsDisplayed()
        compose.onNodeWithText("Show subtitles").assertIsEnabled().performClick()

        assertEquals(AccountSettingsEvent.ChangeRequested(SubtitlesOff), events.single())
    }

    @Test
    fun authoritativeMutationFailureDisablesRowsAndHidesRetry() {
        val events = mutableListOf<AccountSettingsEvent>()
        compose.setAccountContent(
            state = { readyAccountSettingsState(mutation = failedChange(SubtitlesOff, ExpiredSessionFailure)) },
            events = events,
        )

        compose.onNodeWithText("Show subtitles").assertIsNotEnabled()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
        assertTrue(events.isEmpty())
    }

    @Test
    fun refreshFailureUsesConfirmationCopyAndRetriesTheRefresh() {
        val events = mutableListOf<AccountSettingsEvent>()
        compose.setAccountContent(
            state = {
                readyAccountSettingsState(
                    preferences = DefaultAccountSettingsPreferences.copy(showSubtitles = false),
                    mutation = failedChange(SubtitlesOff, operation = AccountSettingsMutation.Operation.Refresh),
                )
            },
            events = events,
        )

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG)
            .performScrollToNode(hasText("Saved, but couldn’t confirm this setting"))
        compose.onNodeWithText("Saved, but couldn’t confirm this setting").assertIsDisplayed()
        compose.onAllNodesWithText("Couldn’t save this setting").assertCountEquals(0)
        compose.onNodeWithText("Try again").performClick()

        assertEquals(AccountSettingsEvent.RetryChange, events.single())
    }

    @Test
    fun trashConfirmationDoesNotSurviveASessionReplacement() {
        var sessionId by mutableStateOf(SessionOne)
        compose.setAccountContent(options = AccountScreenOptions(sessionId = { sessionId }))

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Move deleted files to Trash"))
        compose.onNodeWithText("Move deleted files to Trash").performClick()
        compose.onNodeWithText("Turn off Trash?").assertIsDisplayed()

        compose.runOnIdle { sessionId = SessionTwo }

        compose.onAllNodesWithText("Turn off Trash?").assertCountEquals(0)
    }

    @Test
    fun defaultSortPickerShowsTheCurrentOrderAndDispatchesTheChosenOne() {
        val events = mutableListOf<AccountSettingsEvent>()
        compose.setAccountContent(
            state = {
                readyAccountSettingsState(
                    preferences = DefaultAccountSettingsPreferences.copy(defaultSort = FilesSort.NAME_ASCENDING),
                )
            },
            events = events,
        )
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_DEFAULT_SORT_ROW_TAG))
        compose.onNodeWithTag(MOBILE_DEFAULT_SORT_ROW_TAG).assertTextContains("Name, A–Z").performClick()
        compose.onNodeWithTag(MOBILE_DEFAULT_SORT_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithText("Date added, newest first").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf<AccountSettingsEvent>(
                    AccountSettingsEvent.ChangeRequested(AccountSettingsChange.Sort(FilesSort.DATE_ADDED_DESCENDING)),
                ),
                events,
            )
        }
        compose.onNodeWithTag(MOBILE_DEFAULT_SORT_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun unknownDefaultSortIsShownAsNotSetAndFailedSortSavesRetryOnTheirRow() {
        val events = mutableListOf<AccountSettingsEvent>()
        compose.setAccountContent(
            state = {
                readyAccountSettingsState(
                    mutation = failedChange(AccountSettingsChange.Sort(FilesSort.SIZE_DESCENDING)),
                )
            },
            events = events,
        )
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_DEFAULT_SORT_ROW_TAG))
        compose.onNodeWithTag(MOBILE_DEFAULT_SORT_ROW_TAG).assertTextContains("Not set")
        compose.onAllNodesWithText("Couldn’t save this setting").assertCountEquals(1)
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(listOf<AccountSettingsEvent>(AccountSettingsEvent.RetryChange), events) }
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileAccountPlaybackSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun playbackSettingsChooseFormatAndToggleAutoplay() {
        val appConfigEvents = mutableListOf<AndroidAppConfigEvent>()
        compose.setAccountContent(
            options = AccountScreenOptions(onAppConfigEvent = appConfigEvents::add),
        )

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Video playback"))
        compose.onNodeWithText("Video playback").performClick()
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
            useUnmergedTree = true,
        ).assertExists()
        compose.onAllNodesWithText("HLS stream").assertCountEquals(2)
        compose.onNodeWithText("Direct MP4")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Role,
                    androidx.compose.ui.semantics.Role.RadioButton,
                ),
            )
            .performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Autoplay next video"))
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
            androidAppConfigState(
                content = AndroidAppConfigContent.Loading(AndroidAppConfigRequestId(1L)),
                mutation = AndroidAppConfigMutation.Idle,
                nextRequestValue = 2L,
            ),
        )
        compose.setAccountContent(
            appConfigState = { appConfigState },
            options = AccountScreenOptions(onAppConfigEvent = appConfigEvents::add),
        )

        compose.onNodeWithText("Show subtitles").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Loading playback settings"))
        compose.onNodeWithText("Loading playback settings").assertIsDisplayed()
        compose.runOnIdle {
            appConfigState =
                androidAppConfigState(
                    content =
                        AndroidAppConfigContent.Failed(
                            PutioFailure.AccessDenied(
                                PutioConfigurationException("restricted"),
                            ),
                        ),
                    mutation = AndroidAppConfigMutation.Idle,
                    nextRequestValue = 2L,
                )
        }
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG)
            .performScrollToNode(hasText("This app doesn’t have access to playback settings."))
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
            failure = PutioFailure.Unexpected(IllegalStateException("offline")),
            previousPreferences = DefaultAndroidAppConfigPreferences,
            operation = AndroidAppConfigMutation.Operation.Save,
        )
        var appConfigState by mutableStateOf(readyAndroidAppConfigState(mutation = saveFailure))
        compose.setAccountContent(
            appConfigState = { appConfigState },
            options = AccountScreenOptions(onAppConfigEvent = appConfigEvents::add),
        )

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Couldn’t save this setting"))
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
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG)
            .performScrollToNode(hasText("Saved, but couldn’t confirm this setting"))
        compose.onNodeWithText("Saved, but couldn’t confirm this setting").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf(AndroidAppConfigEvent.RetryChange), appConfigEvents)

        compose.runOnIdle {
            appConfigState =
                readyAndroidAppConfigState(
                    mutation =
                        saveFailure.copy(
                            failure =
                                PutioFailure.AuthenticationRequired(
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
        compose.setAccountContent(
            events = events,
            options = AccountScreenOptions(
                loadTunnelRoutes = {
                    attempts += 1
                    if (attempts == 1) {
                        AccountSettingsRepositoryResult.Failure(
                            AccountSettingsFailure.Putio(PutioFailure.Unexpected(IllegalStateException("offline"))),
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
            ),
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
        compose.setAccountContent(
            state = {
                readyAccountSettingsState(
                    preferences = DefaultAccountSettingsPreferences,
                    mutation = AccountSettingsMutation.Failed(
                        change = AccountSettingsChange.Route(TunnelRouteName("cdn77")),
                        failure = failure,
                        previousPreferences = DefaultAccountSettingsPreferences,
                        operation = AccountSettingsMutation.Operation.Save,
                    ),
                )
            },
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
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileAccountAboutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aboutRowOpensAppInfoAndCopiesSupportTextToTheClipboard() {
        compose.setAccountContent()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_ABOUT_ROW_TAG))
        compose.onNodeWithTag(MOBILE_ABOUT_ROW_TAG).assertTextContains(BuildConfig.VERSION_NAME).performClick()
        compose.onNodeWithTag(MOBILE_ABOUT_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithText("Android API level").assertIsDisplayed()
        compose.onNodeWithText("Phone").assertIsDisplayed()
        compose.onNodeWithText("media3/hls").assertIsDisplayed()
        compose.onAllNodesWithTag(MOBILE_ABOUT_COPIED_TAG).assertCountEquals(0)

        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(android.content.ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
        compose.onNodeWithTag(MOBILE_ABOUT_COPY_TAG).performClick()
        compose.waitUntil(2_000L) {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.startsWith("app: android") == true
        }
        // The confirmation replaces the button label, so it is visible without scrolling.
        // The button merges its descendants; read the label through the unmerged tree.
        compose.onNodeWithTag(MOBILE_ABOUT_COPY_TAG).assertIsDisplayed().assertTextEquals("Copied to clipboard")
        compose.onNodeWithTag(MOBILE_ABOUT_COPIED_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val pasted = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(pasted, pasted.startsWith("app: android\napp_version: ${BuildConfig.VERSION_NAME}\n"))
        assertTrue(pasted, pasted.contains("device_class: phone"))
        assertTrue(pasted, pasted.contains("player: media3/hls"))
        assertFalse(pasted, pasted.contains(Account.username))

        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag(MOBILE_ABOUT_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun copiedConfirmationResetsWhenTheSupportTextChanges() {
        var appConfigState by mutableStateOf(readyAndroidAppConfigState())
        compose.setAccountContent(appConfigState = { appConfigState })
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_ABOUT_ROW_TAG))
        compose.onNodeWithTag(MOBILE_ABOUT_ROW_TAG).performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(android.content.ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
        compose.onNodeWithTag(MOBILE_ABOUT_COPY_TAG).performClick()
        compose.waitUntil(2_000L) {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.contains("player: media3/hls") == true
        }
        compose.onNodeWithTag(MOBILE_ABOUT_COPY_TAG).assertTextEquals("Copied to clipboard")

        // Config settles to MP4 after the copy: the clipboard still says hls, so the label must revert.
        compose.runOnIdle {
            appConfigState = readyAndroidAppConfigState(
                preferences = DefaultAndroidAppConfigPreferences.copy(videoPlaybackType = VideoPlaybackType.Mp4),
            )
        }
        compose.onNodeWithText("media3/mp4").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_ABOUT_COPY_TAG).assertTextEquals("Copy for support")
        assertTrue(clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().contains("player: media3/hls"))
    }

    @Test
    fun aboutDialogReportsTheEffectivePlayerNotAnUnconfirmedSave() {
        val mp4 = DefaultAndroidAppConfigPreferences.copy(videoPlaybackType = VideoPlaybackType.Mp4)
        compose.setAccountContent(
            // Optimistic Ready shows MP4, but the save is still pending: playback keeps HLS.
            appConfigState = {
                androidAppConfigState(
                    content = AndroidAppConfigContent.Ready(mp4),
                    mutation = AndroidAppConfigMutation.Saving(
                        requestId = AndroidAppConfigRequestId(3L),
                        change = AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4),
                        previousPreferences = DefaultAndroidAppConfigPreferences,
                        operation = AndroidAppConfigMutation.Operation.Save,
                    ),
                    nextRequestValue = 4L,
                    confirmedPreferences = DefaultAndroidAppConfigPreferences,
                )
            },
        )
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_ABOUT_ROW_TAG))
        compose.onNodeWithTag(MOBILE_ABOUT_ROW_TAG).performClick()
        compose.onNodeWithText("media3/hls").assertIsDisplayed()
    }

    @Test
    fun aboutDialogReportsTheDeviceClassTheShellPassesIn() {
        compose.setAccountContent(options = AccountScreenOptions(deviceClass = AppDiagnostics.DeviceClass.Tablet))
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_ABOUT_ROW_TAG))
        compose.onNodeWithTag(MOBILE_ABOUT_ROW_TAG).performClick()
        compose.onNodeWithText("Tablet").assertIsDisplayed()
    }
}

private class AccountScreenOptions(
    val onAppConfigEvent: (AndroidAppConfigEvent) -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onPrivacyControls: () -> Unit = {},
    val loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
        AccountSettingsRepositoryResult.Success(emptyList())
    },
    val deviceClass: AppDiagnostics.DeviceClass = AppDiagnostics.DeviceClass.Phone,
    val sessionId: () -> MobileAuthSessionId = { SessionOne },
)

// State arguments are read during composition, so tests can drive them from mutableStateOf.
private fun ComposeContentTestRule.setAccountContent(
    state: () -> AccountSettingsState = { readyAccountSettingsState() },
    events: MutableList<AccountSettingsEvent> = mutableListOf(),
    account: () -> MobileAccount = { Account },
    appConfigState: () -> AndroidAppConfigState = { readyAndroidAppConfigState() },
    options: AccountScreenOptions = AccountScreenOptions(),
) {
    setContent {
        PutioTheme {
            MobileAccountScreen(
                account = account(),
                sessionId = options.sessionId(),
                settingsState = state(),
                appConfigState = appConfigState(),
                onSettingsEvent = events::add,
                onAppConfigEvent = options.onAppConfigEvent,
                onSignOut = options.onSignOut,
                onPrivacyControls = options.onPrivacyControls,
                loadTunnelRoutes = options.loadTunnelRoutes,
                deviceClass = options.deviceClass,
            )
        }
    }
}

private fun failedChange(
    change: AccountSettingsChange,
    failure: AccountSettingsFailure = OfflineFailure,
    operation: AccountSettingsMutation.Operation = AccountSettingsMutation.Operation.Save,
): AccountSettingsMutation.Failed =
    AccountSettingsMutation.Failed(
        change = change,
        failure = failure,
        previousPreferences = DefaultAccountSettingsPreferences,
        operation = operation,
    )

private val OfflineFailure = AccountSettingsFailure.Putio(PutioFailure.Unexpected(IllegalStateException("offline")))
private val ExpiredSessionFailure =
    AccountSettingsFailure.Putio(PutioFailure.AuthenticationRequired(PutioConfigurationException("invalid token")))
private val SubtitlesOff = AccountSettingsChange(AccountSettingsKey.ShowSubtitles, enabled = false)
private val Account =
    MobileAccount(
        userId = 42L,
        username = "putio-user",
        email = "user@example.com",
        avatarUrl = null,
        storage =
            AccountStorage(
                availableBytes = 3 * GIBIBYTE,
                sizeBytes = 4 * GIBIBYTE,
                usedBytes = GIBIBYTE,
            ),
    )
private val SessionOne = MobileAuthSessionId(1L)
private val SessionTwo = MobileAuthSessionId(2L)
private const val GIBIBYTE = 1_073_741_824L
