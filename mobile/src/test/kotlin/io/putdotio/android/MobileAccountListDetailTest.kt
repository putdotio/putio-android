package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.MOBILE_ACCOUNT_PAGE_PANE_TAG
import io.putdotio.android.account.MobileAccountListDetail
import io.putdotio.android.account.MobileAccountPage
import io.putdotio.android.account.MobileAccountPanes
import io.putdotio.android.design.PutioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileAccountListDetailTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun crossingTheBreakpointKeepsTheOpenPagesState() {
        var wide by mutableStateOf(false)
        val panes = MobileAccountPanes(listTopBar = {}, pageTopBar = {})
        compose.setContent {
            PutioTheme {
                Box(Modifier.requiredSize(width = if (wide) 1184.dp else 600.dp, height = 700.dp)) {
                    MobileAccountListDetail(
                        panes = panes.takeIf { wide },
                        open = MobileAccountPage.PrivacyControls,
                        list = {},
                        page = {
                            var taps by rememberSaveable { mutableIntStateOf(0) }
                            TextButton(onClick = { taps++ }) { Text("Tapped $taps") }
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("Tapped 0").performClick()
        compose.onNodeWithText("Tapped 1").assertExists()

        compose.runOnIdle { wide = true }
        compose.onNodeWithTag(MOBILE_ACCOUNT_PAGE_PANE_TAG).assertExists()
        compose.onNodeWithText("Tapped 1").assertExists()

        compose.runOnIdle { wide = false }
        compose.onNodeWithTag(MOBILE_ACCOUNT_PAGE_PANE_TAG).assertDoesNotExist()
        compose.onNodeWithText("Tapped 1").assertExists()
    }
}
