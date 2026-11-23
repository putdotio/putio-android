package io.putdotio.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityConfigChangesTest {
    @Test
    fun mainActivityHandlesLayoutAndInputConfigChangesWithoutRecreating() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(EXPECTED_CONFIG_CHANGES, info.configChanges and EXPECTED_CONFIG_CHANGES)
        assertEquals(0, info.configChanges and ActivityInfo.CONFIG_FONT_SCALE)
    }

    private companion object {
        const val EXPECTED_CONFIG_CHANGES =
            ActivityInfo.CONFIG_ORIENTATION or
                ActivityInfo.CONFIG_SCREEN_LAYOUT or
                ActivityInfo.CONFIG_SCREEN_SIZE or
                ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
                ActivityInfo.CONFIG_KEYBOARD or
                ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
                ActivityInfo.CONFIG_NAVIGATION
    }
}
