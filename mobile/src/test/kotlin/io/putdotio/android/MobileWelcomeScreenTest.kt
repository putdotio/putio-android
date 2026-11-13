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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
@Config(sdk = [35], qualifiers = "w412dp-h915dp")
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
        compose.onNodeWithText("Your put.io files, streaming and offline on this device.").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        assertEquals(1, actions)

        compose.runOnIdle { reason = MobileSignedOutReason.SessionExpired }
        compose.onNodeWithText("Welcome back!").assertIsDisplayed()
        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertIsDisplayed()
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        assertEquals(2, actions)

        compose.runOnIdle { reason = MobileSignedOutReason.SignInFailed }
        compose.onNodeWithText("Welcome!").assertIsDisplayed()
        compose.onNodeWithText("Sign-in didn’t finish").assertIsDisplayed()
        compose.onNodeWithText("Nothing was saved, so you can try again.").assertIsDisplayed()
        compose.onAllNodesWithText("Sign in").assertCountEquals(0)
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(3, actions)
    }

    @Test
    fun secureStorageFailureOffersTryAgainAboveResetAndSignIn() {
        var retries = 0
        compose.setContent {
            PutioTheme {
                Surface {
                    MobileSignedOutScreen(
                        reason = MobileSignedOutReason.SecureStorageUnavailable,
                        canSignIn = true,
                        onSignIn = { actions += 1 },
                        onRetrySecureStorage = { retries += 1 },
                    )
                }
            }
        }

        compose.onNodeWithText("Secure storage is unavailable").assertIsDisplayed()
        compose.onNodeWithText(
            "Your session couldn’t be read or saved safely on this device. Reset clears it so you can sign in again.",
        ).assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
        val tryAgain = compose.onNodeWithText("Try again").assertIsDisplayed().getUnclippedBoundsInRoot()
        val reset = compose.onNodeWithText("Reset and sign in").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(tryAgain.bottom <= reset.top)

        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
        assertEquals(0, actions)

        compose.onNodeWithText("Reset and sign in").performClick()
        assertEquals(1, retries)
        assertEquals(1, actions)
    }

    @Test
    fun missingAuthTabBrowserOffersChromeInsteadOfRetry() {
        var chromeRequests = 0
        compose.setContent {
            PutioTheme {
                Surface {
                    MobileSignedOutScreen(
                        reason = MobileSignedOutReason.AuthTabBrowserMissing,
                        canSignIn = true,
                        onSignIn = { actions += 1 },
                        onGetChrome = { chromeRequests += 1 },
                    )
                }
            }
        }

        compose.onNodeWithText("Sign-in needs an up-to-date Chrome").assertIsDisplayed()
        compose.onNodeWithText("Install or update Chrome, then come back to sign in.").assertIsDisplayed()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
        compose.onAllNodesWithText("Nothing was saved, so you can try again.").assertCountEquals(0)
        compose.onNodeWithText("Get Chrome").performScrollTo().performClick()
        assertEquals(1, chromeRequests)
        assertEquals(0, actions)
    }

    @Test
    fun deviceWithoutGooglePlayExplainsAndOffersOnlyTryAgain() {
        var chromeRequests = 0
        compose.setContent {
            PutioTheme {
                Surface {
                    MobileSignedOutScreen(
                        reason = MobileSignedOutReason.ChromeStoreUnavailable,
                        canSignIn = true,
                        onSignIn = { actions += 1 },
                        onGetChrome = { chromeRequests += 1 },
                    )
                }
            }
        }

        compose.onNodeWithText("This device can’t get Chrome").assertIsDisplayed()
        compose.onNodeWithText("Sign-in needs Chrome from Google Play, which isn’t available here.")
            .assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, actions)
        assertEquals(0, chromeRequests)
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
    fun aPhoneShowsTheActionWithoutScrolling() {
        setSignedOut()

        compose.onNodeWithText("Sign in").assertIsDisplayed()
    }
}

