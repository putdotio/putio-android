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
            val hosts = listOf(
                MainActivity::class.java,
                MobileFullscreenProofActivity::class.java,
                MobileTabletProofActivity::class.java,
            )
            hosts.forEach { host ->
                val activity = Robolectric.buildActivity(host).get()
                assertEquals(host.simpleName, LARGEST_STEP, activity.resources.configuration.fontScale)
            }
        } finally {
            RuntimeEnvironment.setFontScale(previous)
        }
    }

    private companion object {
        const val LARGEST_STEP = 1.3f
    }
}
