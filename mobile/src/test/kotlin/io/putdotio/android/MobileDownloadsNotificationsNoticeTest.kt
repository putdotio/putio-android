package io.putdotio.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.downloads.DownloadNotificationAccess
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_NOTIFICATIONS_TAG
import io.putdotio.android.downloads.MobileDownloadsScreen
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w400dp-h800dp")
class MobileDownloadsNotificationsNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun notificationsOffNoticeUsesRoundedHighContrastNoticeSurface() {
        var background = Color.Unspecified
        var noticeSurface = Color.Unspecified
        var onSurface = Color.Unspecified
        var onSurfaceVariant = Color.Unspecified
        var primary = Color.Unspecified
        compose.setBuiltContent {
            PutioTheme {
                background = MaterialTheme.colorScheme.background
                noticeSurface = MaterialTheme.colorScheme.surfaceContainerHigh
                onSurface = MaterialTheme.colorScheme.onSurface
                onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                primary = MaterialTheme.colorScheme.primary
                Box(Modifier.fillMaxWidth().background(background)) {
                    MobileDownloadsScreen(
                        state = DownloadsState(),
                        onEvent = { true },
                        onPlay = {},
                        notifications = DownloadNotificationAccess(enabled = false) {},
                    )
                }
            }
        }
        compose.onNodeWithText("Download notifications are off").assertIsDisplayed()

        val pixels = compose.onNodeWithTag(MOBILE_DOWNLOADS_NOTIFICATIONS_TAG).captureToImage().toPixelMap()
        val insetBackground = pixels[pixels.width / 2, 2]
        assertEquals("Notice card must use surfaceContainerHigh", noticeSurface, insetBackground)
        assertEquals("Notice card corners must be rounded against background", background, pixels[0, 0])
        assertTrue(
            "Title contrast must meet WCAG AA (4.5:1)",
            contrastRatio(onSurface, insetBackground) >= 4.5,
        )
        assertTrue(
            "Supporting copy contrast must meet WCAG AA (4.5:1)",
            contrastRatio(onSurfaceVariant, insetBackground) >= 4.5,
        )
        assertTrue(
            "Action contrast must meet WCAG AA (4.5:1)",
            contrastRatio(primary, insetBackground) >= 4.5,
        )
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val l1 = first.luminance().toDouble()
        val l2 = second.luminance().toDouble()
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }
}
