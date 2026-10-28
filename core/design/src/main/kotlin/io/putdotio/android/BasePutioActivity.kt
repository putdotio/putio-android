package io.putdotio.android

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

public abstract class BasePutioActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Text stops growing two steps above the default, as in the iOS app;
        // past that, rows, sheets and bars crowd out their own content. A font
        // scale change recreates the activity, so this reads each new value.
        val fontScale = newBase.resources.configuration.fontScale
        if (fontScale > MAX_FONT_SCALE) {
            applyOverrideConfiguration(Configuration().apply { this.fontScale = MAX_FONT_SCALE })
        }
    }

    protected fun configureEdgeToEdge() {
        // The binding is dark only; the automatic style would draw dark
        // status icons over the shell when the device theme is light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
    }

    internal companion object {
        /** Android's "Largest" step before Android 14's nonlinear scales. */
        const val MAX_FONT_SCALE: Float = 1.3f
    }
}
