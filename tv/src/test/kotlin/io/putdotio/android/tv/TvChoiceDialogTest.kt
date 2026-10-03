package io.putdotio.android.tv

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The choice dialog on a 960×540 dp TV, where a long list outgrows the dialog. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvChoiceDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theTitleStaysInViewWhileFocusBringsAChoiceBelowTheFoldOnScreen() {
        show(selected = CHOICES.last())

        compose.onNodeWithText(CHOICES.last()).assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun theTitleStaysInViewWithFocusOnAMiddleChoice() {
        show(selected = CHOICES[CHOICES.size / 2])

        compose.onNodeWithText(CHOICES[CHOICES.size / 2]).assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText(TITLE).assertIsDisplayed()
    }

    private fun show(selected: String) {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvChoiceDialog(
                    title = TITLE,
                    choices = CHOICES.map { TvChoice(it, it) },
                    selected = selected,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val TITLE = "Choices"
        val CHOICES = (1..14).map { "Choice $it" }
    }
}
