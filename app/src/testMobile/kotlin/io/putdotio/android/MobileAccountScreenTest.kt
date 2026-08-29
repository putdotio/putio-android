package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.design.PutioTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileAccountScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun accountIdentityAndSignOutAreAvailable() {
        var signedOut = false
        compose.setContent {
            PutioTheme {
                MobileAccountScreen(
                    account = MobileAccount(
                        userId = 42L,
                        username = "putio-user",
                        email = "user@example.com",
                    ),
                    onSignOut = { signedOut = true },
                )
            }
        }

        compose.onNodeWithText("putio-user").assertIsDisplayed()
        compose.onNodeWithText("user@example.com").assertIsDisplayed()
        compose.onNodeWithText("Sign out").performClick()

        assertTrue(signedOut)
    }
}
