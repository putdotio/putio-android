package io.putdotio.android

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.InactiveAccountNotice
import io.putdotio.android.account.MobileInactiveAccountNotice
import io.putdotio.android.design.PutioTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileInactiveAccountNoticeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun deactivatedAccountShowsWebCopyWithTheDeletionCountdownAndOpensBilling() {
        val zone = ZoneId.systemDefault()
        val deletion = LocalDate.now(zone).plusDays(14).atTime(12, 0).atZone(zone).toInstant()
        compose.setContent { PutioTheme { MobileInactiveAccountNotice(InactiveAccountNotice.Deactivated(deletion)) } }

        compose.onNodeWithText("Your account has been deactivated 😢").assertIsDisplayed()
        compose.onNodeWithText("Your files are still here, but they are scheduled to be deleted in 14 days.")
            .assertIsDisplayed()
        compose.onNodeWithText("Keep a good thing going!").performClick()

        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://app.put.io/billing", opened.dataString)
    }

    @Test
    fun deletionTomorrowReadsInTheSingular() {
        val zone = ZoneId.systemDefault()
        val deletion = LocalDate.now(zone).plusDays(1).atTime(12, 0).atZone(zone).toInstant()
        compose.setContent { PutioTheme { MobileInactiveAccountNotice(InactiveAccountNotice.Deactivated(deletion)) } }

        compose.onNodeWithText("Your files are still here, but they are scheduled to be deleted in 1 day.")
            .assertIsDisplayed()
    }

    @Test
    fun withoutADeletionDateOnlyTheTitleAndActionShow() {
        compose.setContent { PutioTheme { MobileInactiveAccountNotice(InactiveAccountNotice.Deactivated(null)) } }

        compose.onNodeWithText("Your account has been deactivated 😢").assertIsDisplayed()
        compose.onNodeWithText("Your files are still here", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Keep a good thing going!").assertIsDisplayed()
    }

    @Test
    fun familyPlanMemberIsSentToTheFamilyPage() {
        compose.setContent { PutioTheme { MobileInactiveAccountNotice(InactiveAccountNotice.FamilyPlanExpired) } }

        compose.onNodeWithText(
            "Your family plan’s owner needs to update their payment details to keep your plan active.",
        ).assertIsDisplayed()
        compose.onNodeWithText("If you’re ready to leave the nest, you can start your own subscription too!")
            .assertIsDisplayed()
        compose.onNodeWithText("Leave the family plan").performClick()

        assertEquals("https://app.put.io/family", shadowOf(compose.activity).nextStartedActivity.dataString)
    }

    @Test
    fun withoutABrowserTheAddressReplacesTheAction() {
        compose.setContent {
            PutioTheme {
                MobileInactiveAccountNotice(InactiveAccountNotice.Deactivated(null), openUrl = { _, _ -> false })
            }
        }

        compose.onNodeWithText("Keep a good thing going!").performClick()

        compose.onNodeWithText("app.put.io/billing").assertIsDisplayed()
        compose.onNodeWithText("Keep a good thing going!").assertDoesNotExist()
    }
}
