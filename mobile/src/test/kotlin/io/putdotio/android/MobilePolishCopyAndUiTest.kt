package io.putdotio.android

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.downloads.DownloadNotificationAccess
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.downloads.MobileDownloadsScreen
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.historyState
import io.putdotio.android.playback.CastDevice
import io.putdotio.android.playback.CastDeviceKind
import io.putdotio.android.playback.MobileCastPicker
import io.putdotio.android.search.MobileHistoryScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w400dp-h640dp")
class MobilePolishCopyAndUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun castPickerHidesModelSubtitleWhenItMatchesDeviceName() {
        val gateway = FakeCastGateway { 0L }.apply {
            devices.value = listOf(
                CastDevice(
                    id = "1",
                    name = "MIBOX4",
                    model = "MIBOX4",
                    audioOnly = false,
                    kind = CastDeviceKind.Tv,
                    selected = false,
                ),
                CastDevice(
                    id = "2",
                    name = "Bedroom TV ",
                    model = " bedroom tv",
                    audioOnly = false,
                    kind = CastDeviceKind.Tv,
                    selected = false,
                ),
                CastDevice(
                    id = "3",
                    name = "Living Room",
                    model = "Chromecast Ultra",
                    audioOnly = false,
                    kind = CastDeviceKind.Tv,
                    selected = false,
                ),
                CastDevice(
                    id = "4",
                    name = "Kitchen Speaker",
                    model = "Kitchen Speaker",
                    audioOnly = true,
                    kind = CastDeviceKind.Speaker,
                    selected = false,
                ),
            )
        }

        compose.setBuiltContent {
            PutioTheme {
                MobileCastPicker(gateway = gateway, onDismiss = {})
            }
        }

        compose.onAllNodesWithText("MIBOX4", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("bedroom tv", substring = true, ignoreCase = true, useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onNodeWithText("Living Room").assertIsDisplayed()
        compose.onNodeWithText("Chromecast Ultra").assertIsDisplayed()
        compose.onNodeWithText("Kitchen Speaker").assertIsDisplayed()
        compose.onNodeWithText("Audio only").assertIsDisplayed()
    }

    @Test
    fun downloadsEmptyStateDrawsKaomojiGlyphAboveHeadline() {
        var background = 0
        var showReference by mutableStateOf(false)
        compose.setBuiltContent {
            PutioTheme {
                background = MaterialTheme.colorScheme.background.toArgb()
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize().testTag("root"),
                ) {
                    if (showReference) {
                        MobileEmptyState(
                            title = "Nothing downloaded yet",
                            message = "Reference",
                            glyph = PutioEmptyGlyph.NothingYet,
                        )
                    } else {
                        MobileDownloadsScreen(
                            state = DownloadsState(),
                            onEvent = { true },
                            onPlay = {},
                            notifications = DownloadNotificationAccess(enabled = true) {},
                        )
                    }
                }
            }
        }

        val actual = captureGlyphAboveHeadline("Nothing downloaded yet", background)
        showReference = true
        val expected = captureGlyphAboveHeadline("Nothing downloaded yet", background)
        assertGlyphMatches(expected, actual, "Nothing downloaded yet")
    }

    @Test
    fun historyEmptyStateDrawsKaomojiGlyphAboveHeadline() {
        var background = 0
        var showReference by mutableStateOf(false)
        compose.setBuiltContent {
            PutioTheme {
                background = MaterialTheme.colorScheme.background.toArgb()
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize().testTag("root"),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (showReference) {
                            MobileEmptyState(
                                title = "No history yet",
                                message = "Reference",
                                glyph = PutioEmptyGlyph.NothingYet,
                            )
                        } else {
                            MobileHistoryScreen(historyState(HistoryContent.Empty), onHistoryEvent = {})
                        }
                    }
                }
            }
        }

        val actual = captureGlyphAboveHeadline("No history yet", background)
        showReference = true
        val expected = captureGlyphAboveHeadline("No history yet", background)
        assertGlyphMatches(expected, actual, "No history yet")
    }

    @Test
    fun exclusiveLinksAndCaptionSettingsUseConsistentEnUsCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val action = context.getString(R.string.mobile_public_links_action)
        val empty = context.getString(R.string.mobile_public_links_empty)
        val limit = context.getString(R.string.mobile_public_links_refused_limit)
        val caption = context.getString(R.string.mobile_playback_subtitle_caption_settings)

        assertEquals("Exclusive link", action)
        assertEquals("Links you make with Exclusive link show up here.", empty)
        assertTrue(limit.contains("Account, Exclusive links"))
        assertFalse(limit.contains("Exclusive access"))
        assertEquals("Font and colors follow Android’s caption settings.", caption)
    }

    private fun captureGlyphAboveHeadline(headline: String, backgroundArgb: Int): CroppedGlyph {
        val headlineBounds = compose.onNodeWithText(headline).assertIsDisplayed().getUnclippedBoundsInRoot()
        val bitmap = compose.onNodeWithTag("root").captureToImage().asAndroidBitmap()
        val density = compose.density.density
        val band = IntRect(
            left = (bitmap.width / 4).coerceAtLeast(0),
            top = ((headlineBounds.top - 56.dp).value * density).toInt().coerceAtLeast(0),
            right = (bitmap.width * 3 / 4).coerceAtMost(bitmap.width - 1),
            bottom = ((headlineBounds.top - 16.dp).value * density).toInt().coerceAtMost(bitmap.height - 1),
        )
        return cropForeground(bitmap, band, backgroundArgb)
    }

    private fun cropForeground(bitmap: Bitmap, band: IntRect, backgroundArgb: Int): CroppedGlyph {
        val bounds = foregroundBounds(bitmap, band, backgroundArgb) ?: return CroppedGlyph(0, 0, IntArray(0))
        val width = bounds.right - bounds.left + 1
        val height = bounds.bottom - bounds.top + 1
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, bounds.left, bounds.top, width, height)
        return CroppedGlyph(width, height, pixels)
    }

    private fun foregroundBounds(bitmap: Bitmap, band: IntRect, backgroundArgb: Int): IntRect? {
        var minX = Int.MAX_VALUE
        var maxX = -1
        var minY = Int.MAX_VALUE
        var maxY = -1
        for (y in band.top..band.bottom) {
            val row = IntArray(band.right - band.left + 1)
            bitmap.getPixels(row, 0, row.size, band.left, y, row.size, 1)
            val first = row.indexOfFirst { it != backgroundArgb }
            if (first < 0) continue
            val last = row.indexOfLast { it != backgroundArgb }
            minX = minOf(minX, band.left + first)
            maxX = maxOf(maxX, band.left + last)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        return if (maxX < minX || maxY < minY) null else IntRect(minX, minY, maxX, maxY)
    }

    private fun assertGlyphMatches(expected: CroppedGlyph, actual: CroppedGlyph, headline: String) {
        assertTrue("Expected reference glyph pixels above '$headline'", expected.pixels.isNotEmpty())
        assertEquals("Glyph width above '$headline'", expected.width, actual.width)
        assertEquals("Glyph height above '$headline'", expected.height, actual.height)
        assertTrue("Expected NothingYet glyph pixels above '$headline'", expected.pixels.contentEquals(actual.pixels))
    }

    private class CroppedGlyph(val width: Int, val height: Int, val pixels: IntArray)
}
