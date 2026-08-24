package io.putdotio.android

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Local (JVM) proof that the mobile Material 3 shell renders, via Robolectric
 * with native graphics. The on-device counterpart is LaunchSmokeTest.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileShellTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shellRendersAllDestinations() {
        compose.setContent { PutioApp() }

        compose.onNodeWithText("put.io").assertIsDisplayed()
        // "Files" appears twice: top bar title plus its nav label.
        listOf("Files", "Transfers", "Activity", "Account").forEach { label ->
            compose.onAllNodes(hasText(label)).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun selectingADestinationRetitlesTheTopBar() {
        compose.setContent { PutioApp() }

        compose.onNodeWithText("Transfers").performClick()

        // Top bar title and the nav label both show the destination.
        compose.onAllNodes(hasText("Transfers")).assertCountEquals(2)
    }
}
