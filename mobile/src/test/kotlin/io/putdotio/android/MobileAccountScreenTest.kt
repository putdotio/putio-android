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
import io.putdotio.android.account.MOBILE_APP_INFO_TAG
import io.putdotio.android.account.MOBILE_ABOUT_ROW_TAG
import io.putdotio.android.account.MOBILE_ACCOUNT_AVATAR_FALLBACK_TAG
import io.putdotio.android.account.MOBILE_ACCOUNT_LIST_TAG
import io.putdotio.android.account.MOBILE_ACCOUNT_SIGN_OUT_TAG
import io.putdotio.android.account.MOBILE_CHOICE_SAVE_TAG
import io.putdotio.android.account.MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG
import io.putdotio.android.account.MOBILE_DEFAULT_SORT_DIALOG_TAG
import io.putdotio.android.account.MOBILE_DEFAULT_SORT_ROW_TAG
import io.putdotio.android.account.MOBILE_MANAGE_PUBLIC_LINKS_TAG
import io.putdotio.android.account.MOBILE_PRIVACY_CONTROLS_ROW_TAG
import io.putdotio.android.account.MOBILE_TUNNEL_ROUTE_RETRY_TAG
import io.putdotio.android.account.MOBILE_TUNNEL_ROUTE_ROW_TAG
import io.putdotio.android.account.MOBILE_SIGN_OUT_DIALOG_TAG
import io.putdotio.android.account.MOBILE_SIGN_OUT_REMOVE_DOWNLOADS_TAG
import io.putdotio.android.account.MobileAccountLibrary
import io.putdotio.android.account.MobileAccountScreen
import io.putdotio.android.account.MobileAppInfoScreen
import io.putdotio.android.account.mobileAppDiagnostics
import io.putdotio.android.playback.playbackPreference
import io.putdotio.android.trash.MOBILE_MANAGE_TRASH_TAG
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileAccountScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsSectionsSupportHeadingNavigation() {
        compose.setAccountContent()
        listOf("Library", "Files", "Playback", "Subtitles", "Privacy", "About").forEach { title ->
            compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
    }

    @Test
    fun accountIdentitySettingsAndSignOutAreAvailable() {
        var signedOut: Boolean? = null
        val events = mutableListOf<AccountSettingsEvent>()
        compose.setAccountContent(
            events = events,
            options = AccountScreenOptions(onSignOut = { signedOut = it }),
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
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Show subtitles"))
        compose.onNodeWithText("Show subtitles").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Resume where you left off"))
        compose.onNodeWithText("Resume where you left off").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_ACCOUNT_SIGN_OUT_TAG))
        compose.onNodeWithTag(MOBILE_ACCOUNT_SIGN_OUT_TAG).performClick()
        compose.onNode(hasText("Sign out") and hasAnyAncestor(hasTestTag(MOBILE_SIGN_OUT_DIALOG_TAG))).performClick()

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
        assertEquals(false, signedOut)
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
            options = AccountScreenOptions(pages = AccountPages(onPrivacyControls = { opened += 1 })),
        )

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_PRIVACY_CONTROLS_ROW_TAG))
        compose.onNodeWithTag(MOBILE_PRIVACY_CONTROLS_ROW_TAG)
            .assertTextContains("Privacy controls")
            .assertTextContains("Diagnostics on · Analytics off")
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
            .assertTextContains("Diagnostics off · Analytics on")
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

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Show subtitles"))
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
        compose.runOnIdle { assertTrue("Picking a row only marks it", events.isEmpty()) }
        compose.onNodeWithTag(MOBILE_CHOICE_SAVE_TAG).performClick()
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
        compose.onNodeWithTag(MOBILE_CHOICE_SAVE_TAG).performClick()
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

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText("Show subtitles"))
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
        // Direct reads put.io's own line, not the route's API description; other routes keep theirs.
        compose.onNodeWithText("Straight from put.io, no proxy.", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Amsterdam (Direct)", substring = true).assertCountEquals(0)
        compose.onNodeWithText("cdn77", substring = true).assertTextContains("CDN").performClick()
        compose.onNodeWithTag(MOBILE_CHOICE_SAVE_TAG).performClick()
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(
                listOf<AccountSettingsEvent>(
                    AccountSettingsEvent.ChangeRequested(AccountSettingsChange.Route(TunnelRouteName("cdn77"))),
                ),
                events,
            )
        }
        compose.onAllNodesWithText("Straight from put.io, no proxy.", substring = true).assertCountEquals(0)
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
        val unavailable = "That proxy isn’t available for your account. Choose another one."
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText(unavailable))
        compose.onNodeWithText(unavailable).assertIsDisplayed()
        // The failure sits under the Proxy row it belongs to.
        val proxy = compose.onNodeWithTag(MOBILE_TUNNEL_ROUTE_ROW_TAG).fetchSemanticsNode().boundsInRoot
        val failureTop = compose.onNodeWithText(unavailable).fetchSemanticsNode().boundsInRoot.top
        assertTrue(failureTop >= proxy.bottom && failureTop - proxy.bottom < 1f)
        compose.onNodeWithText("Try again").performClick()
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
    fun appInfoRowShowsTheVersionAndOpensTheSubpage() {
        var opened = 0
        compose.setAccountContent(options = AccountScreenOptions(pages = AccountPages(onAppInfo = { opened += 1 })))
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_ABOUT_ROW_TAG))
        compose.onNodeWithTag(MOBILE_ABOUT_ROW_TAG).assertTextContains(BuildConfig.VERSION_NAME).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun appInfoCopiesSupportTextToTheClipboard() {
        compose.setContent { PutioTheme { MobileAppInfoScreen(diagnostics(readyAndroidAppConfigState())) } }
        compose.onNodeWithTag(MOBILE_APP_INFO_TAG).assertIsDisplayed()
        compose.onNodeWithText("Android API level").assertIsDisplayed()
        compose.onNodeWithText("Phone").assertIsDisplayed()
        compose.onNodeWithText("Media3").assertIsDisplayed()
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
    }

    @Test
    fun copiedConfirmationResetsWhenTheSupportTextChanges() {
        var appConfigState by mutableStateOf(readyAndroidAppConfigState())
        compose.setContent { PutioTheme { MobileAppInfoScreen(diagnostics(appConfigState)) } }
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
        compose.onNodeWithTag(MOBILE_ABOUT_COPY_TAG).assertTextEquals("Copy for support")
        assertTrue(clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().contains("player: media3/hls"))
    }

    @Test
    fun appInfoReportsTheEffectivePlayerNotAnUnconfirmedSave() {
        val mp4 = DefaultAndroidAppConfigPreferences.copy(videoPlaybackType = VideoPlaybackType.Mp4)
        // Optimistic Ready shows MP4, but the save is still pending: playback keeps HLS.
        val pending = androidAppConfigState(
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
        assertEquals("media3/hls", diagnostics(pending).player)
    }

    @Test
    fun appInfoReportsTheDeviceClassTheShellPassesIn() {
        compose.setContent {
            PutioTheme {
                MobileAppInfoScreen(diagnostics(readyAndroidAppConfigState(), AppDiagnostics.DeviceClass.Tablet))
            }
        }
        compose.onNodeWithText("Tablet").assertIsDisplayed()
    }

    private fun diagnostics(
        state: AndroidAppConfigState,
        deviceClass: AppDiagnostics.DeviceClass = AppDiagnostics.DeviceClass.Phone,
    ) = mobileAppDiagnostics(state.playbackPreference(), deviceClass)
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileAccountSignOutAndLibraryTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun signOutConfirmsAndCancelKeepsTheSession() {
        val signOuts = mutableListOf<Boolean>()
        compose.setAccountContent(options = AccountScreenOptions(onSignOut = signOuts::add))
        openSignOut()
        compose.onNodeWithText("Sign out of put.io?").assertIsDisplayed()
        compose.onNodeWithText("Downloads stay on this device and come back when you sign in again.")
            .assertIsDisplayed()
        // No downloads on the device: nothing to remove, so no checkbox.
        compose.onAllNodesWithTag(MOBILE_SIGN_OUT_REMOVE_DOWNLOADS_TAG).assertCountEquals(0)
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodesWithTag(MOBILE_SIGN_OUT_DIALOG_TAG).assertCountEquals(0)
        compose.runOnIdle { assertTrue(signOuts.isEmpty()) }
    }

    @Test
    fun removeDownloadsIsOffByDefaultAndShowsTheirSize() {
        val signOuts = mutableListOf<Boolean>()
        compose.setAccountContent(
            options = AccountScreenOptions(
                onSignOut = signOuts::add,
                pages = AccountPages(downloadsOnDeviceBytes = 4 * GIBIBYTE),
            ),
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        openSignOut()
        compose.onNodeWithTag(MOBILE_SIGN_OUT_REMOVE_DOWNLOADS_TAG)
            .assertTextContains("Remove downloads from this device", substring = true)
            .assertTextContains("(${Formatter.formatShortFileSize(context, 4 * GIBIBYTE)})", substring = true)
            .assertIsOff()
        confirmSignOut()
        compose.runOnIdle { assertEquals(listOf(false), signOuts) }

        openSignOut()
        compose.onNodeWithTag(MOBILE_SIGN_OUT_REMOVE_DOWNLOADS_TAG).assertIsOff().performClick().assertIsOn()
        confirmSignOut()
        compose.runOnIdle { assertEquals(listOf(false, true), signOuts) }
    }

    @Test
    fun libraryRowsShowWhatTheAppAlreadyKnowsAndOpenTheirPages() {
        var links = 0
        var trash = 0
        var library by mutableStateOf(MobileAccountLibrary())
        compose.setAccountContent(
            options = AccountScreenOptions(
                pages = AccountPages(
                    onManagePublicLinks = { links += 1 },
                    onManageTrash = { trash += 1 },
                    library = { library },
                ),
            ),
        )
        compose.onNodeWithTag(MOBILE_MANAGE_PUBLIC_LINKS_TAG).assertTextEquals("Exclusive links")
        compose.onNodeWithTag(MOBILE_MANAGE_TRASH_TAG).assertTextEquals("Trash")

        compose.runOnIdle { library = MobileAccountLibrary(exclusiveLinkCount = 2, trashSizeBytes = GIBIBYTE) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithTag(MOBILE_MANAGE_PUBLIC_LINKS_TAG).assertTextEquals("Exclusive links", "2").performClick()
        compose.onNodeWithTag(MOBILE_MANAGE_TRASH_TAG)
            .assertTextEquals("Trash", Formatter.formatShortFileSize(context, GIBIBYTE))
            .performClick()
        compose.runOnIdle {
            assertEquals(1, links)
            assertEquals(1, trash)
        }
    }

    @Test
    fun fullStorageSaysWhatWaitsAndWhatFreesSpace() {
        val full = AccountStorage(availableBytes = 0, sizeBytes = 4 * GIBIBYTE, usedBytes = 4 * GIBIBYTE)
        var account by mutableStateOf(Account)
        compose.setAccountContent(account = { account })
        val fullLine = "Full. New transfers wait until you free up space. Empty Trash or delete files to make room."
        compose.onAllNodesWithText(fullLine, substring = true).assertCountEquals(0)

        compose.runOnIdle { account = Account.copy(storage = full) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val size = Formatter.formatShortFileSize(context, 4 * GIBIBYTE)
        compose.onNodeWithText(fullLine, substring = true).assertIsDisplayed()
        compose.onNodeWithText("$size of $size").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG)
            .assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
    }

    private fun openSignOut() {
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_ACCOUNT_SIGN_OUT_TAG))
        compose.onNodeWithTag(MOBILE_ACCOUNT_SIGN_OUT_TAG).performClick()
    }

    private fun confirmSignOut() {
        compose.onNode(hasText("Sign out") and hasAnyAncestor(hasTestTag(MOBILE_SIGN_OUT_DIALOG_TAG))).performClick()
        compose.onAllNodesWithTag(MOBILE_SIGN_OUT_DIALOG_TAG).assertCountEquals(0)
    }
}

private class AccountScreenOptions(
    val onAppConfigEvent: (AndroidAppConfigEvent) -> Unit = {},
    val onSignOut: (Boolean) -> Unit = {},
    val pages: AccountPages = AccountPages(),
    val loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
        AccountSettingsRepositoryResult.Success(emptyList())
    },
    val sessionId: () -> MobileAuthSessionId = { SessionOne },
)

/** Account's ways out to its subpages, and what Library and the sign-out confirm read from outside. */
private class AccountPages(
    val onPrivacyControls: () -> Unit = {},
    val onAppInfo: () -> Unit = {},
    val onManagePublicLinks: (() -> Unit)? = null,
    val onManageTrash: () -> Unit = {},
    val library: () -> MobileAccountLibrary = { MobileAccountLibrary() },
    val downloadsOnDeviceBytes: Long? = null,
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
                onPrivacyControls = options.pages.onPrivacyControls,
                onAppInfo = options.pages.onAppInfo,
                onManagePublicLinks = options.pages.onManagePublicLinks,
                onManageTrash = options.pages.onManageTrash,
                library = options.pages.library(),
                downloadsOnDeviceBytes = options.pages.downloadsOnDeviceBytes,
                loadTunnelRoutes = options.loadTunnelRoutes,
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
