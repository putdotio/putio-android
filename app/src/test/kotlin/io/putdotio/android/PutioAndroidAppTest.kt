package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Local (JVM) proof that the Compose shell renders, via Robolectric with
 * native graphics. The on-device counterpart is LaunchSmokeTest.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PutioAndroidAppTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shellRendersProductName() {
        compose.setContent { PutioAndroidApp() }

        compose.onNodeWithText("put.io").assertIsDisplayed()
    }
}
