package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileContentStateTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadingStateIsAnnounced() {
        compose.setContent { MobileLoadingState(message = "Loading files") }

        compose.onAllNodesWithText("Loading files").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Loading files").assertCountEquals(0)
        compose.onNodeWithText("Loading files").assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
    }

    @Test
    fun emptyStateExplainsTheState() {
        compose.setContent {
            MobileEmptyState(
                title = "Nothing here",
                message = "This folder is empty",
            )
        }

        compose.onNodeWithText("Nothing here").assertIsDisplayed()
        compose.onNodeWithText("This folder is empty").assertIsDisplayed()
    }

    @Test
    fun errorStateOffersRetry() {
        var retried = false
        compose.setContent {
            MobileErrorState(
                title = "Couldn’t load files",
                message = "Check your connection",
                retryLabel = "Try again",
                onRetry = { retried = true },
            )
        }

        compose.onNodeWithText("Try again").performClick()

        assertTrue(retried)
    }

    @Test
    fun largeFontInShortViewportKeepsAuthActionReachable() {
        var signedIn = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                Box(Modifier.height(200.dp)) {
                    MobileAuthMessageScreen(
                        title = "Sign in to put.io",
                        message = "Your files, wherever you are. Sign in securely in your browser to continue.",
                        actionLabel = "Sign in",
                        onAction = { signedIn = true },
                    )
                }
            }
        }

        compose.onNodeWithText("Sign in to put.io").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
        compose.onNodeWithText("Sign in").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(signedIn)
    }
}
