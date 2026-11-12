package io.putdotio.android

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.MOBILE_ABOUT_ROW_TAG
import io.putdotio.android.account.MOBILE_ACCOUNT_LIST_TAG
import io.putdotio.android.account.MOBILE_ACCOUNT_NO_PAGE_TAG
import io.putdotio.android.account.MOBILE_ACCOUNT_PAGE_PANE_TAG
import io.putdotio.android.account.MOBILE_APP_INFO_TAG
import io.putdotio.android.account.MOBILE_PRIVACY_CONTROLS_LIST_TAG
import io.putdotio.android.account.MOBILE_PRIVACY_CONTROLS_ROW_TAG
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS-w1280dp-h800dp-land")
class MobileAccountPanesTest : NavigationShellFixture(1f) {
    @Test
    fun aWideWindowOpensEachPageBesideTheListMarksItsRowAndBackClosesIt() {
        mount()
        compose.onNode(hasText("Account") and hasAnyAncestor(hasTestTag(MOBILE_NAV_RAIL_TAG))).performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_NO_PAGE_TAG).assertIsDisplayed()

        open(MOBILE_PRIVACY_CONTROLS_ROW_TAG)
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TAB_TITLE_TAG).assertTextEquals("Account")
        compose.onNodeWithTag(MOBILE_PRIVACY_CONTROLS_ROW_TAG).assertIsDisplayed().assertIsSelected()
        inPage(hasTestTag(MOBILE_PRIVACY_CONTROLS_LIST_TAG)).assertIsDisplayed()
        inPage(hasText("Privacy controls")).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()

        open(MOBILE_ABOUT_ROW_TAG)
        compose.onNodeWithTag(MOBILE_ABOUT_ROW_TAG).assertIsDisplayed().assertIsSelected()
        inPage(hasTestTag(MOBILE_APP_INFO_TAG)).assertIsDisplayed()

        back()
        compose.onNodeWithTag(MOBILE_ACCOUNT_NO_PAGE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_ABOUT_ROW_TAG).assertIsDisplayed()
    }

    private fun open(rowTag: String) {
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(rowTag))
        compose.onNodeWithTag(rowTag).performClick()
        compose.waitForIdle()
    }

    private fun inPage(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher and hasAnyAncestor(hasTestTag(MOBILE_ACCOUNT_PAGE_PANE_TAG))).onFirst()

    private fun back() {
        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w1280dp-h800dp-land")
class MobileAccountPanesLargeTextTest : NavigationShellFixture(2f) {
    @Test
    fun aModalLayoutKeepsOneNavigationMenuWhileAPageIsOpenBesideTheList() {
        mount()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        drawerDestination("Account").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_PRIVACY_CONTROLS_ROW_TAG))
        compose.onNodeWithTag(MOBILE_PRIVACY_CONTROLS_ROW_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MOBILE_ACCOUNT_PAGE_PANE_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(MOBILE_NAV_MENU_TAG).assertCountEquals(1)
        compose.onNode(hasTestTag(MOBILE_NAV_MENU_TAG) and hasAnyAncestor(hasTestTag(MOBILE_ACCOUNT_PAGE_PANE_TAG)))
            .assertDoesNotExist()
    }
}
