package io.putdotio.android.viewer

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileViewerLoadFailureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aTextFileThatFailsToLoadOffersTryAgainAndLoadsInPlace() {
        val uri = Uri.parse("content://io.putdotio.android.test/notes.txt")
        val opens = AtomicInteger()
        shadowOf(compose.activity.contentResolver).registerInputStreamSupplier(uri) {
            if (opens.getAndIncrement() == 0) DroppedStream() else "first line".byteInputStream()
        }
        var openedElsewhere = 0
        compose.setContent {
            PutioTheme { MobileTextPage(uri, onOpenWithAnotherApp = { openedElsewhere++ }) }
        }

        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Couldn’t load this").assertIsDisplayed()
        compose.onNodeWithText("Check your connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Open with another app").performClick()
        assertEquals(1, openedElsewhere)

        compose.onNodeWithText("Try again").performClick()

        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("first line").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, opens.get())
    }

    private class DroppedStream : InputStream() {
        override fun read(): Int = throw IOException("Connection reset")

        override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("Connection reset")
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
