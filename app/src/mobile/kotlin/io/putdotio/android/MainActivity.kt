package io.putdotio.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.browser.auth.AuthTabIntent

class MainActivity : BasePutioActivity() {
    internal val authTabLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        handleAuthTabActivityResult(
            context = applicationContext,
            resultCode = result.resultCode,
            rawResultUri = result.resultUri?.toString(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        setContent {
            PutioApp(authTabLauncher)
        }
    }
}
