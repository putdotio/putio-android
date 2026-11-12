package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.FakeLoggedInAppsRepository
import io.putdotio.android.account.MOBILE_SIGN_OUT_OTHERS_CONFIRM_TAG
import io.putdotio.android.account.MOBILE_SIGN_OUT_OTHERS_TAG
import io.putdotio.android.account.MobileLoggedInAppsRoute
import io.putdotio.android.account.loggedInApp
import io.putdotio.android.account.mobileLoggedInAppRevokeTag
import io.putdotio.android.design.PutioTheme
import java.time.Instant
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

    private fun show(repository: FakeLoggedInAppsRepository) {
        compose.setContent {
            PutioTheme { MobileLoggedInAppsRoute(repository, onAuthenticationRequired = {}) }
        }
        compose.waitForIdle()
    }
}
