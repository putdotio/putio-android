package io.putdotio.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileTooltipTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aLongPressShowsTheLabelWithoutClicking() {
        var clicks = 0
        setButton { clicks++ }

        compose.onNodeWithContentDescription(LABEL).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithText(LABEL).assertExists()
        assertEquals(0, clicks)
    }

    @Test
    fun aTapClicksWithoutTheLabel() {
        var clicks = 0
        setButton { clicks++ }

        compose.onNodeWithContentDescription(LABEL).performClick()

        compose.onNodeWithText(LABEL).assertDoesNotExist()
        assertEquals(1, clicks)
    }

    @Test
    fun theLabelShowsAgainAfterItTimesOut() {
        setButton {}
        val button = compose.onNodeWithContentDescription(LABEL)

        button.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(TOOLTIP_GONE_MILLIS)
        compose.onNodeWithText(LABEL).assertDoesNotExist()

        button.performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText(LABEL).assertExists()
    }

    private fun setButton(onClick: () -> Unit) {
        compose.setContent {
            PutioTheme { MobileIconButton(LABEL, R.drawable.ic_ph_dots_three_vertical, onClick) }
        }
    }

    private companion object {
        const val LABEL = "Actions for Sintel.mkv"
        const val TOOLTIP_GONE_MILLIS = 3_000L
    }
}
