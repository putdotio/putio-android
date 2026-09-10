package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.tv.TvLinkScreen
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvLinkPhase
import io.putdotio.android.tv.auth.TvLinkStop
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Local (JVM) proof of the two TV screens: the device-code screen shows the
 * code and put.io/link, and the signed-in shell lists the four drawer
 * destinations with focus starting in the pane. The on-device counterpart is
 * LaunchSmokeTest plus the harness recording.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvShellTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun linkScreenShowsCodeAndLinkUrlWithNewCodeFocused() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvLinkScreen(phase = TvLinkPhase.AwaitingLink("GIQTKN"), sessionExpired = false, onRequestNewCode = {})
            }
        }

        compose.onNodeWithContentDescription("Activation code GIQTKN").assertIsDisplayed()
        compose.onNodeWithText("put.io/link").assertIsDisplayed()
        compose.onNodeWithText("Get new code").assertIsFocused()
    }

    @Test
    fun anExpiredSessionIsExplainedWhileTheNewCodeIsLive() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvLinkScreen(phase = TvLinkPhase.AwaitingLink("GIQTKN"), sessionExpired = true, onRequestNewCode = {})
            }
        }

        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Activation code GIQTKN").assertIsDisplayed()
    }

    @Test
    fun expiredCodeExplainsAndOffersANewOne() {
        var requests = 0
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvLinkScreen(
                    phase = TvLinkPhase.Stopped(TvLinkStop.CodeExpired),
                    sessionExpired = true,
                    onRequestNewCode = { requests += 1 },
                )
            }
        }

        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertIsDisplayed()
        compose.onNodeWithText("That code expired. Get a new one to continue.").assertIsDisplayed()
        compose.onNodeWithText("Get new code").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(1, requests)
    }

    @Test
    fun shellListsDestinationsAndDpadMovesBetweenDrawerAndPane() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvShell(account = TvAccount(userId = 1, username = "user", email = "user@example.com"), onSignOut = {})
            }
        }

        compose.onNodeWithText("put.io").assertIsDisplayed()
        compose.onNodeWithText("Your files will show up here.").assertIsFocused()

        compose.onNodeWithText("Your files will show up here.").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused()
        listOf("Files", "Search", "History", "Account").forEach {
            compose.onNode(hasText(it) and hasClickAction()).assertIsDisplayed()
        }

        compose.onNode(hasText("Files") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Account") and hasClickAction()).assertIsFocused()
        compose.onNode(hasText("Account") and hasClickAction()).performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Signed in as user").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsFocused()
    }
}
