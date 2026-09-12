package io.putdotio.android.tv.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigReducer
import io.putdotio.android.settings.AndroidAppConfigRequestId
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.TunnelRouteName
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAccountStorage
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvAccountScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val settingsEvents = mutableListOf<AccountSettingsEvent>()
    private val appConfigEvents = mutableListOf<AndroidAppConfigEvent>()
    private val account = TvAccount(
        userId = 1,
        username = "devs-auto",
        email = "devs@example.com",
        storage = TvAccountStorage(availableBytes = 750_000_000_000, sizeBytes = 1_000_000_000_000, usedBytes = 250_000_000_000),
    )
    private val preferences = AccountSettingsPreferences(
        historyEnabled = true,
        trashEnabled = true,
        showSubtitles = true,
        autoSelectSubtitles = false,
        tunnelRoute = TunnelRouteName("cdn77"),
    )

    @Test
    fun headerShowsQuotaAndSignOutHoldsFocusUntilSettingsArrive() {
        var settings by mutableStateOf(AccountSettingsReducer.start().state)
        show(settingsState = { settings })

        compose.onNodeWithText("750 GB of 1.0 TB free").assertIsDisplayed()
        compose.onNodeWithText("Loading account settings").assertIsDisplayed()
        compose.onAllNodesWithText("Sign out")[0].assertIsFocused()

        compose.runOnIdle { settings = ready(preferences) }
        compose.onNodeWithText("Choose your proxy").assertIsFocused()
        compose.onNodeWithText("cdn77").assertIsDisplayed()
    }

    @Test
    fun switchesReportTheAccountKeyAndSubtitleAutoSelectIsInverted() {
        show(settingsState = { ready(preferences) })

        compose.onNodeWithText("Choose your proxy").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Remember current place in video files").assertIsFocused().assertIsOn().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Show subtitles").assertIsOn()
        compose.onNodeWithText("Do not select subtitles by default").assertIsOn().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithText("Do not select subtitles by default").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(
            listOf<AccountSettingsEvent>(
                AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.ResumePlayback, false)),
                AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.AutoSelectSubtitles, true)),
            ),
            settingsEvents,
        )
    }

    @Test
    fun playbackTypeOpensAChoiceDialogFocusedOnTheCurrentValue() {
        show(settingsState = { ready(preferences) }, appConfigState = { readyConfig(VideoPlaybackType.Hls) })

        compose.onNodeWithText("Choose your proxy").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithText("Video playback type").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onAllNodesWithText("HLS (default)")[1].assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        // MP4 sits above the default, as the oracle lists them.
        compose.onNodeWithText("MP4").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(
            listOf<AndroidAppConfigEvent>(
                AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4)),
            ),
            appConfigEvents,
        )
        compose.onAllNodesWithText("MP4").assertCountEquals(0)
        compose.onNodeWithText("Video playback type").assertIsFocused()
    }

    @Test
    fun proxyDialogListsLoadedRoutesWithTheDirectOneNamed() {
        show(
            settingsState = { ready(preferences) },
            loadTunnelRoutes = {
                AccountSettingsRepositoryResult.Success(
                    listOf(
                        TunnelRouteOption(TunnelRouteName.DEFAULT, "Amsterdam"),
                        TunnelRouteOption(TunnelRouteName("cdn77"), "cdn77"),
                        TunnelRouteOption(TunnelRouteName("lon"), "London"),
                    ),
                )
            },
        )

        compose.onNodeWithText("Choose your proxy").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Amsterdam (Direct)").assertIsDisplayed()
        compose.onAllNodesWithText("cdn77")[1].assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("London").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(
            listOf<AccountSettingsEvent>(
                AccountSettingsEvent.ChangeRequested(AccountSettingsChange.Route(TunnelRouteName("lon"))),
            ),
            settingsEvents,
        )
    }

    @Test
    fun turningTrashOffConfirmsFirstWithCancelFocused() {
        show(settingsState = { ready(preferences) })

        compose.onNodeWithText("Choose your proxy").assertIsFocused().performKeyInput {
            repeat(6) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithText("Move deleted files to trash").assertIsFocused().assertIsOn().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Turn off trash?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Turn off").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(
            listOf<AccountSettingsEvent>(
                AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.Trash, false)),
            ),
            settingsEvents,
        )
        compose.onNodeWithText("Move deleted files to trash").assertIsFocused()
    }

    @Test
    fun aFailedSaveKeepsTheRowOnTheServerValueAndOffersRetry() {
        val failure = AccountSettingsFailure.NetworkUnavailable(PutioConfigurationException("offline"))
        val requested = AccountSettingsReducer.reduce(
            ready(preferences),
            AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.History, false)),
        ).state
        val failed = AccountSettingsReducer.reduce(
            requested,
            AccountSettingsEvent.SaveFailed(AccountSettingsRequestId(2), failure),
        ).state
        show(settingsState = { failed })

        compose.onNodeWithText("Couldn’t save this setting. Check the network and try again.").assertIsDisplayed()
        compose.onNodeWithText("Keep account history").assertIsOn()
        compose.onNode(hasText("Try again") and hasClickAction()).requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<AccountSettingsEvent>(AccountSettingsEvent.RetryChange), settingsEvents)
    }

    @Test
    fun aFailedLoadExplainsAndOffersRetryLoadWhileSignOutHoldsFocus() {
        val failure = AccountSettingsFailure.NetworkUnavailable(PutioConfigurationException("offline"))
        val failed = AccountSettingsReducer.reduce(
            AccountSettingsReducer.start().state,
            AccountSettingsEvent.LoadFailed(AccountSettingsRequestId(1), failure),
        ).state
        show(settingsState = { failed })

        compose.onNodeWithText("Couldn’t load account settings. Check the network and try again.").assertIsDisplayed()
        compose.onAllNodesWithText("Sign out")[0].assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<AccountSettingsEvent>(AccountSettingsEvent.RetryLoad), settingsEvents)
    }

    @Test
    fun diagnosticsOpensTheSupportFactsAndSignOutIsTheLastRow() {
        var signOuts = 0
        show(settingsState = { ready(preferences) }, onSignOut = { signOuts += 1 })

        compose.onNodeWithText("Diagnostics").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Device class: TV").assertIsDisplayed()
        compose.onNodeWithText("OK").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Diagnostics").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onAllNodesWithText("Sign out")[1].assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(1, signOuts)
    }

    private fun ready(preferences: AccountSettingsPreferences): AccountSettingsState =
        AccountSettingsReducer.reduce(
            AccountSettingsReducer.start().state,
            AccountSettingsEvent.LoadSucceeded(AccountSettingsRequestId(1), preferences),
        ).state

    private fun readyConfig(type: VideoPlaybackType): AndroidAppConfigState =
        AndroidAppConfigReducer.reduce(
            AndroidAppConfigReducer.start().state,
            AndroidAppConfigEvent.LoadSucceeded(AndroidAppConfigRequestId(1), AndroidAppConfigPreferences(videoPlaybackType = type)),
        ).state

    private fun show(
        settingsState: () -> AccountSettingsState,
        appConfigState: () -> AndroidAppConfigState = { readyConfig(VideoPlaybackType.Hls) },
        onSignOut: () -> Unit = {},
        loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
            AccountSettingsRepositoryResult.Failure(AccountSettingsFailure.Unexpected(IllegalStateException("none")))
        },
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                val paneFocus = remember { FocusRequester() }
                TvAccountScreen(
                    account = account,
                    settingsState = settingsState(),
                    appConfigState = appConfigState(),
                    onSettingsEvent = { settingsEvents += it; true },
                    onAppConfigEvent = { appConfigEvents += it; true },
                    onSignOut = onSignOut,
                    paneFocus = paneFocus,
                    trashSizeBytes = 0,
                    trashPane = {},
                    loadTunnelRoutes = loadTunnelRoutes,
                )
            }
        }
    }
}
