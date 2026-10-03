package io.putdotio.android

import androidx.compose.material3.Surface
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileSignedOutReason
import io.putdotio.android.design.PutioTheme
import org.junit.Assert.assertEquals
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

    private fun setSignedOut(reason: MobileSignedOutReason?, canSignIn: Boolean = true) {
        compose.setContent {
            PutioTheme {
                Surface {
                    MobileSignedOutScreen(reason = reason, canSignIn = canSignIn, onSignIn = { actions += 1 })
                }
            }
        }
    }

    @Test
    fun freshSignInWelcomesAndOffersSignIn() {
        setSignedOut(reason = null)

        compose.onNodeWithContentDescription("put.io").assertIsDisplayed()
        compose.onNodeWithText("Welcome!").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Sign in to stream, download and manage your files.").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(1, actions)
    }

    @Test
    fun expiredSessionWelcomesBackWithNotice() {
        setSignedOut(MobileSignedOutReason.SessionExpired)

        compose.onNodeWithText("Welcome back!").assertIsDisplayed()
        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(1, actions)
    }

    @Test
    fun failedSignInOffersTryAgain() {
        setSignedOut(MobileSignedOutReason.SignInFailed)

        compose.onNodeWithText("Sign-in didn’t finish", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Sign in").assertCountEquals(0)
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, actions)
    }

    @Test
    fun missingOAuthClientExplainsWithoutAnAction() {
        setSignedOut(reason = null, canSignIn = false)

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
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, actions)
    }
}
