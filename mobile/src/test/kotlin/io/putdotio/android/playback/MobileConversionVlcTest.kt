package io.putdotio.android.playback

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.sdk.files.PlaybackConversionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileConversionVlcTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aRunningConversionOffersVlcBesideItsProgress() {
        var opened = 0
        compose.setContent {
            PutioTheme {
                MobileConversionState(
                    PlaybackContent.Conversion(PlaybackConversionState.Converting(40.0)),
                    onRefresh = {},
                    onStart = {},
                    onPlayInVlc = { opened++ },
                )
            }
        }

        compose.onNodeWithText("40%").assertIsDisplayed()
        compose.onNodeWithText("Play in VLC now").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun withoutVlcTheConversionOffersNoHandoff() {
        compose.setContent {
            PutioTheme {
                MobileConversionState(
                    PlaybackContent.Conversion(PlaybackConversionState.Converting(40.0)),
                    onRefresh = {},
                    onStart = {},
                )
            }
        }

        compose.onNodeWithText("40%").assertIsDisplayed()
        compose.onNodeWithText("Play in VLC now").assertDoesNotExist()
    }
}
