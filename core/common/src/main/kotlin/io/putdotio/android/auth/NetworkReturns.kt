package io.putdotio.android.auth

/**
 * Turns default-network callbacks into returns: only a network that was missing or unvalidated and
 * then validates counts, so capability updates on a working network and put.io outages never retry.
 */
public class NetworkReturns(
    initiallyValidated: Boolean,
    private val onReturn: () -> Unit,
) {
    private var waiting = !initiallyValidated

    @Synchronized
    public fun onLost() {
        waiting = true
    }

    public fun onCapabilities(validated: Boolean) {
        val returned = synchronized(this) {
            if (!validated) {
                waiting = true
                false
            } else {
                waiting.also { waiting = false }
            }
        }
        if (returned) onReturn()
    }
}
