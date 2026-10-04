package io.putdotio.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileInactiveAccountNoticeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun deactivatedAccountShowsWebCopyWithTheDeletionCountdown() {
        compose.setContent {
            PutioTheme { MobileInactiveAccountNotice(InactiveAccountNotice.Deactivated(deletionIn(14))) }
        }

        compose.onNodeWithText("Your account has been deactivated 😢").assertIsDisplayed()
        compose.onNodeWithText("Your files are still here, but they are scheduled to be deleted in 14 days.")
            .assertIsDisplayed()
        assertNoActionOrPaymentWording()
    }

    @Test
    fun deletionTomorrowReadsInTheSingular() {
        compose.setContent {
            PutioTheme { MobileInactiveAccountNotice(InactiveAccountNotice.Deactivated(deletionIn(1))) }
        }

        compose.onNodeWithText("Your files are still here, but they are scheduled to be deleted in 1 day.")
            .assertIsDisplayed()
    }

    @Test
    fun withoutADeletionDateOnlyTheTitleShows() {
        compose.setContent { PutioTheme { MobileInactiveAccountNotice(InactiveAccountNotice.Deactivated(null)) } }

        compose.onNodeWithText("Your account has been deactivated 😢").assertIsDisplayed()
        compose.onNodeWithText("Your files are still here", substring = true).assertDoesNotExist()
        assertNoActionOrPaymentWording()
    }

    @Test
    fun familyPlanMemberIsToldThePlanExpired() {
        compose.setContent { PutioTheme { MobileInactiveAccountNotice(InactiveAccountNotice.FamilyPlanExpired) } }

        compose.onNodeWithText("Your family plan is no longer active.").assertIsDisplayed()
        assertNoActionOrPaymentWording()
    }

    /** Google Play's payments policy: no link out to billing and no call to pay, renew or subscribe. */
    private fun assertNoActionOrPaymentWording() {
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.onAllNodesWithText("app.put.io", substring = true).assertCountEquals(0)
        val words = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
            generateSequence(listOf(root)) { nodes -> nodes.flatMap { it.children }.ifEmpty { null } }
                .flatten()
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .joinToString(" ")
        }
        assertEquals(emptyList<String>(), PaymentWords.findAll(words).map { it.value }.toList())
    }

    private fun deletionIn(days: Long) = ZoneId.systemDefault().let { zone ->
        LocalDate.now(zone).plusDays(days).atTime(12, 0).atZone(zone).toInstant()
    }

    private companion object {
        val PaymentWords = Regex(
            """\b(pay\w*|renew\w*|subscri\w*|billing|keep a good thing going)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
