package io.putdotio.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import io.putdotio.android.tv.TvLaunchRequests
import io.putdotio.android.tv.consumeTvLaunchRequest
import io.putdotio.android.tv.toTvLaunchRequest
import io.putdotio.android.tv.toUri
import io.putdotio.android.tv.auth.TvAuthRuntime

class MainActivity : BasePutioActivity() {
    private val launchRequests: TvLaunchRequests by lazy { ViewModelProvider(this)[TvLaunchRequests::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        val runtime = TvAuthRuntime.get(applicationContext)
        // A recreated activity answered its launch intent already; one rebuilt after process
        // death restores the request it had not handled yet instead.
        val restored = savedInstanceState != null || launchRequests.launchIntentConsumed
        receiveLaunchRequest(intent, fresh = !restored)
        launchRequests.launchIntentConsumed = true
        if (launchRequests.pending.value == null) {
            savedInstanceState?.getString(STATE_PENDING_LAUNCH)?.toUri()?.toTvLaunchRequest()
                ?.let(launchRequests::receive)
        }
        setContent {
            PutioApp(runtime, launchRequests)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveLaunchRequest(intent, fresh = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_LAUNCH, launchRequests.pending.value?.toUri()?.toString())
    }

    private fun receiveLaunchRequest(intent: Intent?, fresh: Boolean) {
        val request = intent?.consumeTvLaunchRequest() ?: return
        // Recents replays the intent that started the task; it is not a new request.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (fresh && !fromHistory) launchRequests.receive(request)
    }

    private companion object {
        const val STATE_PENDING_LAUNCH = "pendingLaunch"
    }
}
