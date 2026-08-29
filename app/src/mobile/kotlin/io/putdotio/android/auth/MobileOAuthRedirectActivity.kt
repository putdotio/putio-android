package io.putdotio.android.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.putdotio.android.MainActivity

/**
 * Narrow AppAuth-style redirect receiver. It consumes the callback in-process
 * and brings the app forward without copying the URI or token into extras.
 */
class MobileOAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawCallbackUri = intent?.dataString
        intent?.data = null
        MobileOAuthRuntime.get(applicationContext).dispatchOAuthCallback(rawCallbackUri)

        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
    }
}
