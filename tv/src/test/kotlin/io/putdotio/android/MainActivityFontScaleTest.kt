package io.putdotio.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityFontScaleTest {
    @Test
    fun fontSizeChangeRecreatesTheActivitySoTheCapFollowsIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(0, info.configChanges and ActivityInfo.CONFIG_FONT_SCALE)
    }

    @Test
    fun tvTextStopsAtTheLargeStep() {
        val previous = RuntimeEnvironment.getApplication().resources.configuration.fontScale
        try {
            RuntimeEnvironment.setFontScale(2f)
            val activity = Robolectric.buildActivity(MainActivity::class.java).get()
            assertEquals(1.15f, activity.resources.configuration.fontScale)
        } finally {
            RuntimeEnvironment.setFontScale(previous)
        }
    }
}
