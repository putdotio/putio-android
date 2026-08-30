package io.putdotio.android

import android.os.Bundle
import androidx.activity.compose.setContent

class MainActivity : BasePutioActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        setContent {
            PutioApp()
        }
    }
}
