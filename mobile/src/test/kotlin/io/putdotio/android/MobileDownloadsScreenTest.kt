package io.putdotio.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.downloads.DownloadsEvent
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.downloads.MobileDownloadsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileDownloadsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun progressIsWatchedOnlyWhileTheScreenIsShown() {
        val events = mutableListOf<DownloadsEvent>()
        var shown by mutableStateOf(true)
        compose.setContent {
            PutioTheme {
                if (shown) {
                    MobileDownloadsScreen(DownloadsState(), onEvent = { events += it; true }, onPlay = {})
                }
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(DownloadsEvent.Shown), events)

        shown = false
        compose.waitForIdle()
        assertEquals(listOf(DownloadsEvent.Shown, DownloadsEvent.Hidden), events)
    }
}
