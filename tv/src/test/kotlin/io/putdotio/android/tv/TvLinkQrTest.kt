package io.putdotio.android.tv

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.tv.auth.TvLinkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sign-in QR code as a phone would read it: decoded from the pixels the screen draws. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvLinkQrTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun theQrCodeBesideTheCodeScansToPutiosLinkPageForIt() {
        show(TvLinkPhase.AwaitingLink("GIQTKN"))

        compose.onNodeWithContentDescription("Activation code GIQTKN").assertIsDisplayed()
        compose.onNodeWithText("Or scan with your phone").assertIsDisplayed()
        val qr = compose.onNodeWithTag(TV_LINK_QR_TAG)
        qr.assertIsDisplayed()
        // The payload put.io's own QR image for a code (the SDK's qrCodeUrl) carries.
        assertEquals("https://app.put.io/link?code=GIQTKN", scan(qr.captureToImage()))
        // Get new code stays the only focus target.
        assertFalse(isFocusable().matches(qr.fetchSemanticsNode()))
    }

    @Test
    fun noQrCodeShowsWithoutACode() {
        show(TvLinkPhase.RequestingCode)

        compose.onAllNodesWithTag(TV_LINK_QR_TAG).assertCountEquals(0)
    }

    @Test
    fun theEncoderRoundTripsLongerLinks() {
        val link = "https://put.io/link?code=" + "A".repeat(120)
        val modules = encodeTvQr(link)
        val scale = 4
        val span = (modules.size + 2 * QR_QUIET_ZONE_MODULES) * scale
        val pixels = IntArray(span * span) { index ->
            val x = index % span / scale - QR_QUIET_ZONE_MODULES
            val y = index / span / scale - QR_QUIET_ZONE_MODULES
            val dark = x in modules.indices && y in modules.indices && modules[y][x]
            if (dark) BLACK else WHITE
        }

        assertEquals(link, decode(pixels, span, span))
    }

    private fun show(phase: TvLinkPhase) {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvLinkScreen(phase = phase, sessionExpired = false, onRequestNewCode = {})
            }
        }
    }

    private fun scan(image: ImageBitmap): String {
        val map = image.toPixelMap()
        val pixels = IntArray(map.width * map.height) { index ->
            val color = map[index % map.width, index / map.width]
            val luminance = (color.red + color.green + color.blue) / 3f
            if (luminance > 0.5f) WHITE else BLACK
        }
        return decode(pixels, map.width, map.height)
    }

    // A camera's search, not PURE_BARCODE: the reader finds the code inside whatever surrounds it.
    private fun decode(pixels: IntArray, width: Int, height: Int): String =
        QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))),
            mapOf(DecodeHintType.TRY_HARDER to true),
        ).text

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
