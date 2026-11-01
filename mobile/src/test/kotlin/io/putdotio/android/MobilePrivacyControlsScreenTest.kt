package io.putdotio.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.MOBILE_PRIVACY_CONTROLS_LIST_TAG
import io.putdotio.android.account.PrivacyLinks
import io.putdotio.android.account.MOBILE_STRICTLY_NECESSARY_TAG
import io.putdotio.android.account.MobilePrivacyControlsScreen
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.accountSettingsState
import io.putdotio.android.settings.readyAccountSettingsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobilePrivacyControlsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun strictlyNecessaryIsAlwaysOnAndEachToggleChangesItsAccountKey() {
        val events = mutableListOf<AccountSettingsEvent>()
        compose.setPrivacyContent(events = events)

        compose.onNodeWithTag(MOBILE_STRICTLY_NECESSARY_TAG)
            .assertTextContains("Always on")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))

        compose.onNodeWithText("Diagnostics").assertIsOn().performClick()
        compose.runOnIdle {
            assertEquals(
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.Diagnostics, enabled = false),
                ),
                events.last(),
            )
        }
        compose.onNodeWithText("Product analytics").assertIsOff().performClick()
        compose.runOnIdle {
            assertEquals(
                AccountSettingsEvent.ChangeRequested(
                    AccountSettingsChange(AccountSettingsKey.ProductAnalytics, enabled = true),
                ),
                events.last(),
            )
            assertEquals(2, events.size)
        }
    }

    @Test
    fun screenReadersHearEachDescriptionOnItsControl() {
        compose.setPrivacyContent()

        compose.onNodeWithTag(MOBILE_STRICTLY_NECESSARY_TAG).assertTextContains(STRICTLY_NECESSARY_COPY)
        compose.onNodeWithText("Diagnostics").assertTextContains(DIAGNOSTICS_COPY)
        compose.onNodeWithText("Product analytics").assertTextContains(PRODUCT_ANALYTICS_COPY)
        compose.onNodeWithTag(MOBILE_PRIVACY_CONTROLS_LIST_TAG).performScrollToNode(hasText(GROUP_FOOTER_COPY))
        compose.onAllNodesWithText("These choices apply to every put.io app you sign in to.").assertCountEquals(0)
    }

    @Test
    fun policyLinksOpenInTheBrowser() {
        val opened = mutableListOf<String>()
        compose.setPrivacyContent(opened = opened)

        compose.onNodeWithTag(MOBILE_PRIVACY_CONTROLS_LIST_TAG).performScrollToNode(hasText("Privacy policy"))
        compose.onNodeWithText("Privacy policy").performClick()
        compose.onNodeWithTag(MOBILE_PRIVACY_CONTROLS_LIST_TAG).performScrollToNode(hasText("Services we use"))
        compose.onNodeWithText("Services we use").performClick()

        compose.runOnIdle { assertEquals(listOf(PrivacyLinks.POLICY, PrivacyLinks.SERVICES_WE_USE), opened) }
    }

    @Test
    fun loadingSettingsKeepsTheLinksReachable() {
        compose.setPrivacyContent(
            accountSettingsState(
                content = AccountSettingsContent.Loading(AccountSettingsRequestId(1L)),
                mutation = AccountSettingsMutation.Idle,
                nextRequestValue = 2L,
            ),
        )

        compose.onAllNodesWithText("Diagnostics").assertCountEquals(0)
        compose.onNodeWithTag(MOBILE_PRIVACY_CONTROLS_LIST_TAG).performScrollToNode(hasText("Services we use"))
        compose.onNodeWithText("Privacy policy").assertExists()
    }
}

private fun ComposeContentTestRule.setPrivacyContent(
    state: AccountSettingsState = readyAccountSettingsState(),
    events: MutableList<AccountSettingsEvent> = mutableListOf(),
    opened: MutableList<String> = mutableListOf(),
) {
    val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }
    setContent {
        PutioTheme {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MobilePrivacyControlsScreen(settingsState = state, onSettingsEvent = events::add)
            }
        }
    }
}

private const val STRICTLY_NECESSARY_COPY =
    "Needed for sign-in, security, transfers, downloads, playback, billing, and saving your choices."
private const val DIAGNOSTICS_COPY =
    "Crash reports, error details, and limited playback diagnostics, so we can fix problems."
private const val PRODUCT_ANALYTICS_COPY =
    "Limited usage, device, billing, and playback data that shows which apps and features work."
private const val GROUP_FOOTER_COPY =
    "Diagnostics and product analytics never send filenames, media titles, URLs, search text, or tokens."
