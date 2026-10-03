package io.putdotio.android

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

abstract class BasePutioActivity : ComponentActivity() {
    protected fun configureEdgeToEdge() {
        // The binding is dark only; the automatic style would draw dark
        // status icons over the shell when the device theme is light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
    }
}
