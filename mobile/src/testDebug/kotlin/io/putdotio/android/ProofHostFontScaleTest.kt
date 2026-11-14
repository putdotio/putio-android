package io.putdotio.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProofHostFontScaleTest {
    @Test
    fun proofHostsStopAtTheLargestStepAsTheAppDoes() {
        val previous = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        try {
            RuntimeEnvironment.setFontScale(2f)
            listOf(MainActivity::class.java, MobileFullscreenProofActivity::class.java, MobileTabletProofActivity::class.java)
                .forEach { host ->
                    val activity = Robolectric.buildActivity(host).get()
                    assertEquals(host.simpleName, 1.3f, activity.resources.configuration.fontScale)
                }
        } finally {
            RuntimeEnvironment.setFontScale(previous)
        }
    }
}
