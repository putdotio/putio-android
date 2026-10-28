package io.putdotio.android

import android.os.Bundle
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BasePutioActivityTest {
    private class TestActivity : BasePutioActivity() {
        lateinit var density: Density

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent { density = LocalDensity.current }
        }
    }

    private var previousFontScale = 1f

    @Before
    fun saveFontScale() {
        previousFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
    }

    @After
    fun restoreFontScale() = RuntimeEnvironment.setFontScale(previousFontScale)

    @Test
    @Config(sdk = [26, 35])
    fun capsLargerSystemFontScales() {
        assertEquals(1.3f, fontScaleAt(2f))
        assertEquals(1.3f, fontScaleAt(1.5f))
    }

    @Test
    @Config(sdk = [26, 35])
    fun keepsFontScalesUpToTheCap() {
        assertEquals(0.85f, fontScaleAt(0.85f))
        assertEquals(1.15f, fontScaleAt(1.15f))
        assertEquals(1.3f, fontScaleAt(1.3f))
    }

    @Test
    fun composeRendersTextOnTheNonlinearCurveAtTheCap() {
        val launched = launchAt(2f)
        assertEquals(1.3f, launched.density.fontScale)
        for (size in listOf(14f, 40f)) {
            val platform = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, launched.metrics)
            assertEquals(platform, with(launched.density) { size.sp.toPx() }, 0.01f)
        }
        val linear = 40f * 1.3f * launched.density.density
        assertNotEquals(linear, with(launched.density) { 40.sp.toPx() }, 0.01f)
    }

    /** The activity's font scale, after checking Compose renders at the same one. */
    private fun fontScaleAt(system: Float): Float {
        val launched = launchAt(system)
        assertEquals(launched.fontScale, launched.density.fontScale)
        return launched.fontScale
    }

    private class Launched(val fontScale: Float, val metrics: DisplayMetrics, val density: Density)

    private fun launchAt(system: Float): Launched {
        RuntimeEnvironment.setFontScale(system)
        val controller = Robolectric.buildActivity(TestActivity::class.java).setup()
        val activity = controller.get()
        val resources = activity.resources
        return Launched(resources.configuration.fontScale, resources.displayMetrics, activity.density)
            .also { controller.destroy() }
    }
}
