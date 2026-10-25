package io.putdotio.android

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileSignedOutReason
import io.putdotio.android.design.PutioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileWelcomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var actions = 0

    private fun setSignedOut(canSignIn: Boolean = true, reason: () -> MobileSignedOutReason? = { null }) {
        compose.setContent {
            PutioTheme {
                Surface {
                    MobileSignedOutScreen(reason = reason(), canSignIn = canSignIn, onSignIn = { actions += 1 })
                }
            }
        }
    }

    @Test
    fun eachSignedOutReasonShowsItsCopyAndOneAction() {
        var reason by mutableStateOf<MobileSignedOutReason?>(null)
        setSignedOut { reason }

        compose.onNodeWithContentDescription("put.io").assertIsDisplayed()
        compose.onNodeWithText("Welcome!").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Your put.io files, streaming and offline on this phone.").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        assertEquals(1, actions)

        compose.runOnIdle { reason = MobileSignedOutReason.SessionExpired }
        compose.onNodeWithText("Welcome back!").assertIsDisplayed()
        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        assertEquals(2, actions)

        compose.runOnIdle { reason = MobileSignedOutReason.SignInFailed }
        compose.onNodeWithText("Welcome!").assertIsDisplayed()
        compose.onNodeWithText("Sign-in didn’t finish.").assertIsDisplayed()
        compose.onNodeWithText("Nothing was saved, so you can try again.").assertIsDisplayed()
        compose.onAllNodesWithText("Sign in").assertCountEquals(0)
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(3, actions)

        compose.runOnIdle { reason = MobileSignedOutReason.SecureStorageUnavailable }
        compose.onNodeWithText("Secure storage is unavailable.").assertIsDisplayed()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
        compose.onNodeWithText("Reset and sign in").performScrollTo().performClick()
        assertEquals(4, actions)
    }

    @Test
    fun missingOAuthClientExplainsWithoutAnAction() {
        setSignedOut(canSignIn = false)

        compose.onNodeWithText("Mobile sign-in isn’t configured").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun browserStepOffersOnlyCancel() {
        compose.setContent {
            PutioTheme {
                Surface {
                    MobileWelcomeScreen(content = MobileBrowserWelcomeContent, onAction = { actions += 1 })
                }
            }
        }

        compose.onNodeWithText("Almost there").assertIsDisplayed()
        compose.onNodeWithText("Finish signing in in your browser, then come back here.").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(1, actions)
    }

    @Test
    fun sequenceHoldsEachFrameThenRollsToTheNext() {
        // DESIGN.md Welcome: each of the four frames holds 1.9 s, then the next rolls in over 0.6 s.
        assertEquals(0f, welcomeSequencePosition(0L, frameCount = 4))
        assertEquals(0f, welcomeSequencePosition(1_900L, frameCount = 4))
        assertTrue(welcomeSequencePosition(2_200L, frameCount = 4) in 0.01f..0.99f)
        assertEquals(1f, welcomeSequencePosition(2_500L, frameCount = 4))
        assertEquals(3f, welcomeSequencePosition(7_500L, frameCount = 4))
        assertTrue(welcomeSequencePosition(9_999L, frameCount = 4) > 3.99f)
        assertEquals(0f, welcomeSequencePosition(10_000L, frameCount = 4))
    }
}
