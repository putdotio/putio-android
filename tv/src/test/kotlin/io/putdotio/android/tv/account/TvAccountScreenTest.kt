package io.putdotio.android.tv.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.AccountStorage
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesSort
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
import io.putdotio.android.tv.pressCenter
import io.putdotio.android.tv.setTvContent
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
        storage = AccountStorage(
            availableBytes = 750_000_000_000,
            sizeBytes = 1_000_000_000_000,
            usedBytes = 250_000_000_000,
        ),
    )
    private val preferences = AccountSettingsPreferences(
        historyEnabled = true,
        trashEnabled = true,
        showSubtitles = true,
        autoSelectSubtitles = false,
        tunnelRoute = TunnelRouteName("cdn77"),
        defaultSort = FilesSort.NAME_ASCENDING,
    )

    @Test
    fun entryFocusesTheFirstGroupAndRightMovesIntoItsRows() {
        show(settingsState = { ready(preferences) })

        compose.onNodeWithText("250 GB of 1.0 TB used").assertIsDisplayed()
        compose.onNodeWithText("devs@example.com").assertIsDisplayed()
        group("Library").assertIsFocused().assertIsSelected().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Manage your trash").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Move deleted files to Trash").assertIsFocused().assertIsOn()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Default sort order").assertIsFocused().performKeyInput { pressKey(Key.DirectionLeft) }
        group("Library").assertIsFocused()
    }

    @Test
    fun theQuotaStatesWhatIsFreeWhenTheAccountShowsOptimisticUsage() {
        var shown by mutableStateOf(account)
        show(settingsState = { ready(preferences) }, account = { shown })

        compose.onNodeWithContentDescription("devs-auto. 250 GB of 1.0 TB used, 25% used").assertExists()
        compose.runOnIdle { shown = account.copy(storage = account.storage.copy(showOptimisticUsage = true)) }
        compose.onNodeWithText("750 GB of 1.0 TB free").assertIsDisplayed()
        compose.onNodeWithContentDescription("devs-auto. 750 GB of 1.0 TB free, 25% used").assertExists()
    }

    @Test
    fun focusingAGroupShowsItsRowsAndSettingsArriveIntoThem() {
        var settings by mutableStateOf(AccountSettingsReducer.start().state)
        show(settingsState = { settings })

        compose.onNodeWithText("Manage your trash").assertIsDisplayed()
        compose.onNodeWithText("Loading account settings").assertIsDisplayed()
        group("Library").performKeyInput { pressKey(Key.DirectionDown) }
        group("Playback").assertIsFocused().assertIsSelected()
        compose.onNodeWithText("Autoplay next video").assertIsDisplayed()
        compose.onAllNodesWithText("Proxy").assertCountEquals(0)

        compose.runOnIdle { settings = ready(preferences) }
        compose.onNodeWithText("Resume where you left off").assertIsOn()
        compose.onNodeWithText("cdn77").assertIsDisplayed()
        compose.onNodeWithText("HLS stream").assertIsDisplayed()
        group("Playback").assertIsFocused()
    }

    @Test
    fun switchesWriteTheirAccountKeys() {
        show(settingsState = { ready(preferences) })

        group("Library").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithText("Resume where you left off").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Resume where you left off").performKeyInput { pressKey(Key.DirectionLeft) }
        group("Playback").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithText("Show subtitles").assertIsFocused().assertIsOn()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Pick matching subtitles").assertIsFocused().assertIsOff()
            .performKeyInput { pressCenter() }
        assertEquals(
            listOf<AccountSettingsEvent>(
                AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.ResumePlayback, false)),
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.AutoSelectSubtitles, true),
                ),
            ),
            settingsEvents,
        )
    }

    @Test
    fun videoPlaybackChoosesInASheetAndSavesOnlyOnSave() {
        show(settingsState = { ready(preferences) }, appConfigState = { readyConfig(VideoPlaybackType.Hls) })

        openPlaybackRow("Video playback", downs = 2)
        compose.onNode(inSheet("HLS stream")).performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(inSheet("Direct MP4")).performKeyInput { pressCenter() }
        compose.onNodeWithText("Original quality when the file is already MP4.").assertIsDisplayed()
        assertEquals(emptyList<AndroidAppConfigEvent>(), appConfigEvents)
        compose.onNode(inSheet("Save")).performKeyInput { pressCenter() }
        assertEquals(
            listOf<AndroidAppConfigEvent>(
                AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4)),
            ),
            appConfigEvents,
        )
        compose.onAllNodesWithText("Direct MP4").assertCountEquals(0)
        compose.onNodeWithText("Video playback").assertIsFocused()
    }

    @Test
    fun proxySheetListsRoutesWithTheirDescriptionsAndCancelChangesNothing() {
        show(
            settingsState = { ready(preferences) },
            loadTunnelRoutes = {
                AccountSettingsRepositoryResult.Success(
                    listOf(
                        TunnelRouteOption(TunnelRouteName("cdn77"), "Fastest for most places. The default."),
                        TunnelRouteOption(TunnelRouteName.DEFAULT, "Amsterdam"),
                        TunnelRouteOption(TunnelRouteName("lon"), "London"),
                    ),
                )
            },
        )

        openPlaybackRow("Proxy", downs = 3)
        compose.onNodeWithText("Choose your proxy").assertIsDisplayed()
        compose.onNodeWithText("Straight from put.io’s servers in Amsterdam.").assertIsDisplayed()
        compose.onNode(inSheet("cdn77")).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(inSheet("lon")).performKeyInput { pressCenter() }
        compose.onNode(inSheet("Save")).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(inSheet("Cancel")).performKeyInput { pressCenter() }
        assertEquals(emptyList<AccountSettingsEvent>(), settingsEvents)
        compose.onNodeWithText("Proxy").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNode(inSheet("cdn77")).performKeyInput {
            repeat(2) { pressKey(Key.DirectionDown) }
            pressCenter()
        }
        compose.onNode(inSheet("Save")).performKeyInput { pressCenter() }
        assertEquals(
            listOf<AccountSettingsEvent>(
                AccountSettingsEvent.ChangeRequested(AccountSettingsChange.Route(TunnelRouteName("lon"))),
            ),
            settingsEvents,
        )
    }

    @Test
    fun defaultSortSavesTheChosenOrder() {
        show(settingsState = { ready(preferences) })

        group("Library").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Manage your trash").performKeyInput { repeat(2) { pressKey(Key.DirectionDown) } }
        compose.onNodeWithText("Default sort order").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNode(inSheet("Name, A–Z")).performKeyInput {
            pressKey(Key.DirectionDown)
            pressCenter()
        }
        compose.onNode(inSheet("Save")).performKeyInput { pressCenter() }
        assertEquals(
            listOf<AccountSettingsEvent>(
                AccountSettingsEvent.ChangeRequested(AccountSettingsChange.Sort(FilesSort.NAME_DESCENDING)),
            ),
            settingsEvents,
        )
    }

    @Test
    fun turningTrashOffConfirmsInRed() {
        show(settingsState = { ready(preferences) })

        group("Library").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Manage your trash").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Move deleted files to Trash").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithText("Turn off Trash?").assertIsDisplayed()
        compose.onNode(hasText("Turn off") and isFocused() and hasAnyAncestor(isDialog())).performKeyInput {
            pressCenter()
        }
        assertEquals(
            listOf<AccountSettingsEvent>(
                AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.Trash, false)),
            ),
            settingsEvents,
        )
        compose.onNodeWithText("Move deleted files to Trash").assertIsFocused()
    }

    @Test
    fun privacyUsesTheApprovedCopyAndOpensThePolicyAsAQrCode() {
        show(settingsState = { ready(preferences) })

        group("Library").performKeyInput {
            repeat(3) { pressKey(Key.DirectionDown) }
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithText(
            "Needed for sign-in, security, transfers, downloads, playback, billing, and saving your choices.",
        ).assertIsDisplayed()
        compose.onNodeWithText(
            "Diagnostics and product analytics never send filenames, media titles, URLs, search text, or tokens.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Diagnostics").assertIsFocused().assertIsOn().performKeyInput { pressCenter() }
        compose.onNodeWithText("Diagnostics").performKeyInput { repeat(2) { pressKey(Key.DirectionDown) } }
        compose.onNodeWithText("Privacy policy").assertIsFocused().performKeyInput { pressCenter() }
        compose.onNodeWithContentDescription("QR code for Privacy policy").assertIsDisplayed()
        compose.onNodeWithText("https://put.io/privacy-policy").assertIsDisplayed()
        compose.onNode(inSheet("Done")).performKeyInput { pressCenter() }
        compose.onNodeWithText("Privacy policy").assertIsFocused()
        assertEquals(
            listOf<AccountSettingsEvent>(
                AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.Diagnostics, false)),
            ),
            settingsEvents,
        )
    }

    @Test
    fun aboutListsTheSupportFactsAndTheirQrCode() {
        show(settingsState = { ready(preferences) })

        group("Library").performKeyInput {
            repeat(4) { pressKey(Key.DirectionDown) }
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithText("Version").assertIsFocused()
        compose.onNodeWithText("Media3 ExoPlayer").assertIsDisplayed()
        compose.onNodeWithContentDescription("QR code with these details for support").assertIsDisplayed()
    }

    @Test
    fun signOutIsTheLastRowAndConfirmsInRed() {
        var signOuts = 0
        show(settingsState = { ready(preferences) }, onSignOut = { signOuts += 1 })

        group("Library").performKeyInput { repeat(5) { pressKey(Key.DirectionDown) } }
        compose.onNode(hasText("Sign out") and isFocused()).performKeyInput { pressCenter() }
        compose.onNodeWithText("Sign out of put.io?").assertIsDisplayed()
        compose.onNodeWithText("You can sign in again any time with a new code.").assertIsDisplayed()
        compose.onNode(hasText("Sign out") and isFocused() and hasAnyAncestor(isDialog())).performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("Cancel") and isFocused()).performKeyInput { pressCenter() }
        assertEquals(0, signOuts)
        compose.onNode(hasText("Sign out") and isFocused()).performKeyInput { pressCenter() }
        compose.onNode(hasText("Sign out") and isFocused() and hasAnyAncestor(isDialog())).performKeyInput {
            pressCenter()
        }
        assertEquals(1, signOuts)
    }

    @Test
    fun aFailedSaveOffersRetryAndHandsFocusBackToItsRow() {
        val failure =
            AccountSettingsFailure.Putio(PutioFailure.NetworkUnavailable(PutioConfigurationException("offline")))
        val requested = AccountSettingsReducer.reduce(
            ready(preferences),
            AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.History, false)),
        ).state
        val failed = AccountSettingsReducer.reduce(
            requested,
            AccountSettingsEvent.SaveFailed(AccountSettingsRequestId(2), failure),
        ).state
        var settings by mutableStateOf(failed)
        show(settingsState = { settings })

        compose.onNodeWithText("Couldn’t save this setting. Check the network and try again.").assertIsDisplayed()
        compose.onNodeWithText("Keep account history").assertIsOn()
        group("Library").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Manage your trash").performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        compose.onNodeWithText("Keep account history").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Try again") and isFocused()).performKeyInput { pressCenter() }
        assertEquals(listOf<AccountSettingsEvent>(AccountSettingsEvent.RetryChange), settingsEvents)

        compose.runOnIdle { settings = AccountSettingsReducer.reduce(failed, AccountSettingsEvent.RetryChange).state }
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
        compose.onNodeWithText("Keep account history").assertIsFocused()
    }

    @Test
    fun aFailedLoadOffersRetryWhileTrashStaysReachable() {
        val failure =
            AccountSettingsFailure.Putio(PutioFailure.NetworkUnavailable(PutioConfigurationException("offline")))
        val failed = AccountSettingsReducer.reduce(
            AccountSettingsReducer.start().state,
            AccountSettingsEvent.LoadFailed(AccountSettingsRequestId(1), failure),
        ).state
        show(settingsState = { failed })

        compose.onNodeWithText("Couldn’t load account settings. Check the network and try again.").assertIsDisplayed()
        group("Library").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Manage your trash").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Try again") and isFocused()).performKeyInput { pressCenter() }
        assertEquals(listOf<AccountSettingsEvent>(AccountSettingsEvent.RetryLoad), settingsEvents)
    }

    private fun inSheet(label: String) = hasText(label) and isFocused() and hasAnyAncestor(isDialog())

    private fun group(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction())

    private fun openPlaybackRow(label: String, downs: Int) {
        group("Library").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithText("Resume where you left off").performKeyInput {
            repeat(downs) { pressKey(Key.DirectionDown) }
        }
        compose.onNodeWithText(label).assertIsFocused().performKeyInput { pressCenter() }
    }

    private fun ready(preferences: AccountSettingsPreferences): AccountSettingsState =
        AccountSettingsReducer.reduce(
            AccountSettingsReducer.start().state,
            AccountSettingsEvent.LoadSucceeded(AccountSettingsRequestId(1), preferences),
        ).state

    private fun readyConfig(type: VideoPlaybackType): AndroidAppConfigState =
        AndroidAppConfigReducer.reduce(
            AndroidAppConfigReducer.start().state,
            AndroidAppConfigEvent.LoadSucceeded(
                AndroidAppConfigRequestId(1),
                AndroidAppConfigPreferences(videoPlaybackType = type),
            ),
        ).state

    private fun show(
        settingsState: () -> AccountSettingsState,
        account: () -> TvAccount = { this.account },
        appConfigState: () -> AndroidAppConfigState = { readyConfig(VideoPlaybackType.Hls) },
        onSignOut: () -> Unit = {},
        loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
            AccountSettingsRepositoryResult.Failure(
                AccountSettingsFailure.Putio(PutioFailure.Unexpected(IllegalStateException("none"))),
            )
        },
    ) {
        compose.setTvContent {
            val paneFocus = remember { FocusRequester() }
            TvAccountScreen(
                account = account(),
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
