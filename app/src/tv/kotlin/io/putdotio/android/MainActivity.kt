package io.putdotio.android

import android.os.Bundle
import androidx.activity.compose.setContent
import io.putdotio.android.tv.auth.TvAuthRuntime

class MainActivity : BasePutioActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        val runtime = TvAuthRuntime.get(applicationContext)
        setContent {
            PutioApp(runtime.authController)
        }
    }
}
