package io.putdotio.android

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.FakeLoggedInAppsRepository
import io.putdotio.android.account.LoggedInAppsController
import io.putdotio.android.account.MOBILE_ACCOUNT_LIST_TAG
import io.putdotio.android.account.MOBILE_LOGGED_IN_APPS_LIST_TAG
import io.putdotio.android.account.MOBILE_LOGGED_IN_APPS_ROW_TAG
import io.putdotio.android.account.MOBILE_PRIVACY_CONTROLS_ROW_TAG
import io.putdotio.android.account.MOBILE_SIGN_OUT_OTHERS_CONFIRM_TAG
import io.putdotio.android.account.MOBILE_SIGN_OUT_OTHERS_TAG
import io.putdotio.android.account.MobileLoggedInAppsRoute
import io.putdotio.android.account.loggedInApp
import io.putdotio.android.account.mobileLoggedInAppRevokeTag
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesPage
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileLoggedInAppsTest {
    @get:Rule
    val compose = createComposeRule()

    private val thisDevice = loggedInApp(1L, "put.io for Android", thisDevice = true)
    private val ios = loggedInApp(2L, "put.io for iOS", Instant.parse("2026-11-09T12:00:00Z"))
    private val infuse = loggedInApp(4L, "Infuse", Instant.parse("2026-10-30T12:00:00Z"))

    @Test
    fun eachAppShowsWhenItWasLastUsedAndThisDeviceCannotRevokeItself() {
        val repository = FakeLoggedInAppsRepository(listOf(thisDevice, ios, infuse))
        show(repository)

        compose.onNodeWithText("This device").assertIsDisplayed()
        compose.onNodeWithText("Used on Nov 9, 2026").assertIsDisplayed()
        compose.onNodeWithText("Used on Oct 30, 2026").assertIsDisplayed()
        compose.onNodeWithTag(mobileLoggedInAppRevokeTag(thisDevice.id)).assertDoesNotExist()

        compose.onNodeWithTag(mobileLoggedInAppRevokeTag(ios.id)).performClick()
        compose.waitForIdle()

        assertEquals(listOf(ios.id), repository.revoked)
        compose.onNodeWithText("put.io for iOS").assertDoesNotExist()
        compose.onNodeWithText("Infuse").assertIsDisplayed()
    }

    @Test
    fun signingOutOfAllOtherAppsAsksFirstAndCancelRevokesNothing() {
        val repository = FakeLoggedInAppsRepository(listOf(thisDevice, ios, infuse))
        show(repository)

        compose.onNodeWithTag(MOBILE_SIGN_OUT_OTHERS_TAG).performClick()
        compose.onNodeWithText("Sign out of all other apps?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(emptyList<Any>(), repository.revoked)
        compose.onNodeWithText("Infuse").assertIsDisplayed()

        compose.onNodeWithTag(MOBILE_SIGN_OUT_OTHERS_TAG).performClick()
        compose.onNodeWithTag(MOBILE_SIGN_OUT_OTHERS_CONFIRM_TAG).performClick()
        compose.waitForIdle()

        assertEquals(listOf(ios.id, infuse.id), repository.revoked)
        compose.onNodeWithText("put.io for Android").assertIsDisplayed()
        compose.onNodeWithText("Infuse").assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_SIGN_OUT_OTHERS_TAG).assertDoesNotExist()
    }

    @Test
    fun accountOpensLoggedInAppsAndLeavingDoesNotStopSigningOutOthers() {
        val repository = FakeLoggedInAppsRepository(listOf(thisDevice, ios, infuse))
        val firstRevoke = CompletableDeferred<Unit>()
        val revoke = repository.onRevoke
        repository.onRevoke = { id ->
            if (id == ios.id) firstRevoke.await()
            revoke(id)
        }
        showShell(repository)

        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_LOGGED_IN_APPS_ROW_TAG))
        compose.onNodeWithTag(MOBILE_LOGGED_IN_APPS_ROW_TAG).performClick()
        compose.onNodeWithText("put.io for iOS").assertIsDisplayed()

        compose.onNodeWithTag(MOBILE_SIGN_OUT_OTHERS_TAG).performClick()
        compose.onNodeWithTag(MOBILE_SIGN_OUT_OTHERS_CONFIRM_TAG).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(MOBILE_LOGGED_IN_APPS_LIST_TAG).assertDoesNotExist()
        compose.runOnIdle { firstRevoke.complete(Unit) }
        compose.waitForIdle()

        assertEquals(listOf(ios.id, infuse.id), repository.revoked)
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_LOGGED_IN_APPS_ROW_TAG))
        compose.onNodeWithTag(MOBILE_LOGGED_IN_APPS_ROW_TAG).performClick()
        compose.onNodeWithText("Signed out of all other apps").assertIsDisplayed()
        compose.onNodeWithText("put.io for iOS").assertDoesNotExist()
    }

    @Test
    fun accountHasNoLoggedInAppsRowWithoutAController() {
        showShell(repository = null)

        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_PRIVACY_CONTROLS_ROW_TAG))
        compose.onAllNodesWithTag(MOBILE_LOGGED_IN_APPS_ROW_TAG).assertCountEquals(0)
    }

    private fun show(repository: FakeLoggedInAppsRepository) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { LoggedInAppsController(repository, scope) }
            PutioTheme { MobileLoggedInAppsRoute(controller, onAuthenticationRequired = {}) }
        }
        compose.waitForIdle()
    }

    private fun showShell(repository: FakeLoggedInAppsRepository?) {
        compose.setContent {
            PutioTheme {
                val scope = rememberCoroutineScope()
                MobileShell(
                    filesState = emptyFiles(),
                    loggedInAppsController = remember { repository?.let { LoggedInAppsController(it, scope) } },
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = MobileAccount(userId = 42L, username = "user", email = "user@example.com"),
                    playbackRepository = NoPlayback,
                    playbackPlayerFactory = NoAudioSessionFactory,
                    sessionId = MobileAuthSessionId(1L),
                    onFilesEvent = { true },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun emptyFiles(): FilesBrowserState {
        val initial = FilesBrowserReducer.start()
        return FilesBrowserReducer.reduce(
            initial.state,
            FilesBrowserEvent.LoadSucceeded(checkNotNull(initial.effect).requestId, FilesPage(emptyList(), null)),
        ).state
    }

    private object NoPlayback : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> = error("Unexpected playback")

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
    }
}
