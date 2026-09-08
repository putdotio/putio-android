package io.putdotio.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w640dp-h320dp-land")
class MobileResumePlaybackDialogTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() { RuntimeEnvironment.setFontScale(2f) }
    }).around(compose)

    @Test
    fun bothChoicesRemainReachableAtLargeFontInShortLandscape() {
        var resumed = 0
        var restarted = 0
        compose.setContent {
            PutioTheme {
                MobileResumePlaybackDialog(
                    title = "A long episode filename 東京 été ".repeat(20),
                    startFromSeconds = 3723.0,
                    onResume = { resumed += 1 },
                    onRestart = { restarted += 1 },
                    onDismiss = {},
                )
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Resume from 1:02:03?")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale)
        compose.onNodeWithText("Resume").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Start over").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, resumed)
            assertEquals(1, restarted)
        }
    }
}
