package io.putdotio.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BasePutioActivityTest {
    private class TestActivity : BasePutioActivity()

    @After
    fun resetFontScale() = RuntimeEnvironment.setFontScale(1f)

    @Test
    fun capsLargerSystemFontScales() {
        assertEquals(1.3f, fontScaleAt(2f))
        assertEquals(1.3f, fontScaleAt(1.5f))
    }

    @Test
    fun keepsFontScalesUpToTheCap() {
        assertEquals(0.85f, fontScaleAt(0.85f))
        assertEquals(1.15f, fontScaleAt(1.15f))
        assertEquals(1.3f, fontScaleAt(1.3f))
    }

    private fun fontScaleAt(system: Float): Float {
        RuntimeEnvironment.setFontScale(system)
        val controller = Robolectric.buildActivity(TestActivity::class.java).setup()
        return controller.get().resources.configuration.fontScale.also { controller.destroy() }
    }
}
