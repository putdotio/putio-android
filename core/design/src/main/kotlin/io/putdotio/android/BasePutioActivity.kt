package io.putdotio.android

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

public abstract class BasePutioActivity : ComponentActivity() {
    protected open val maxFontScale: Float get() = MAX_FONT_SCALE

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val fontScale = newBase.resources.configuration.fontScale
        val cap = maxFontScale
        if (fontScale > cap) {
            applyOverrideConfiguration(Configuration().apply { this.fontScale = cap })
        }
    }

    protected fun configureEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.decorView
            window.isNavigationBarContrastEnforced = false
        } else {
            WindowCompat.enableEdgeToEdge(window)
        }
    }

    public companion object {
        public const val MAX_FONT_SCALE: Float = 1.3f
    }
}
