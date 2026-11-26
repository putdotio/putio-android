package io.putdotio.android

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

public abstract class BasePutioActivity : ComponentActivity() {
    /** The largest font scale this app's text follows; larger system sizes render at it. */
    protected open val maxFontScale: Float get() = MAX_FONT_SCALE

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Read once: subclasses must leave fontScale out of configChanges, so
        // a font size change recreates the activity (MainActivityFontScaleTest).
        val fontScale = newBase.resources.configuration.fontScale
        val cap = maxFontScale
        if (fontScale > cap) {
            applyOverrideConfiguration(Configuration().apply { this.fontScale = cap })
        }
    }

    protected fun configureEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.isNavigationBarContrastEnforced = false
        } else {
            WindowCompat.enableEdgeToEdge(window)
        }
    }

    public companion object {
        /** Android's "Largest" step before Android 14's nonlinear scales. */
        public const val MAX_FONT_SCALE: Float = 1.3f
    }
}
