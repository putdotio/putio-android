package io.putdotio.android

import androidx.lifecycle.Lifecycle

internal class MobileOAuthBrowserReturnTracker(
    initialLeftWhileAwaitingCallback: Boolean = false,
    initialCallbackSequenceWhenPaused: Long? = null,
) {
    var leftWhileAwaitingCallback: Boolean = initialLeftWhileAwaitingCallback
        private set
    var callbackSequenceWhenPaused: Long? = initialCallbackSequenceWhenPaused
        private set

    fun onLifecycleEvent(
        event: Lifecycle.Event,
        awaitingCallback: Boolean,
        callbackDispatchSequence: Long,
    ): Boolean =
        when (event) {
            Lifecycle.Event.ON_PAUSE -> {
                leftWhileAwaitingCallback = awaitingCallback
                callbackSequenceWhenPaused = callbackDispatchSequence.takeIf { awaitingCallback }
                false
            }

            Lifecycle.Event.ON_RESUME -> {
                val callbackReceived = callbackSequenceWhenPaused?.let {
                    it != callbackDispatchSequence
                } == true
                val cancelledInBrowser =
                    leftWhileAwaitingCallback && awaitingCallback && !callbackReceived
                leftWhileAwaitingCallback = false
                callbackSequenceWhenPaused = null
                cancelledInBrowser
            }

            else -> false
        }
}
