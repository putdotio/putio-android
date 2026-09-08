package io.putdotio.android

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class MobileVideoWindowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun configurationRecreationPreservesBaselineUntilTheVideoRouteExits() {
        val controller = Robolectric.buildActivity(VideoWindowHostActivity::class.java).setup()
        try {
            compose.waitForIdle()
            val before = controller.get()
            compose.runOnIdle {
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, before.requestedOrientation)
                assertEquals(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    WindowCompat.getInsetsController(before.window, before.window.decorView).systemBarsBehavior,
                )
                controller.configurationChange(Configuration(before.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                    screenWidthDp = 891
                    screenHeightDp = 411
                }).visible()
            }
            compose.waitForIdle()
            val after = controller.get()
            compose.runOnIdle {
                assertNotSame(before, after)
                assertTrue(after.window.decorView.isAttachedToWindow)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, before.requestedOrientation)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, after.requestedOrientation)
                after.showVideo = false
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, after.requestedOrientation)
                assertEquals(
                    before.originalBarsBehavior,
                    WindowCompat.getInsetsController(after.window, after.window.decorView).systemBarsBehavior,
                )
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun landscapeRequestRespectsTabletMultiwindowAndPortraitContent() {
        val original = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            videoWindowOrientation(original, landscape = true, smallestWidthDp = 411, multiWindow = false),
        )
        assertEquals(original, videoWindowOrientation(original, true, 600, false))
        assertEquals(original, videoWindowOrientation(original, true, 411, true))
        assertEquals(original, videoWindowOrientation(original, false, 411, false))
    }

    class VideoWindowHostActivity : ComponentActivity() {
        var showVideo by mutableStateOf(true)
        var originalBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            private set

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            if (savedInstanceState == null) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            originalBarsBehavior = WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior
            setContent {
                if (showVideo) MobileVideoWindow(fileId = 42L)
            }
        }
    }
}
