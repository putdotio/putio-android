package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

        compose.onNodeWithContentDescription("Loading files").assertIsDisplayed()
        compose.onNodeWithText("Loading files").assertIsDisplayed()
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
}
