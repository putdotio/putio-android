package io.putdotio.android

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A pending product link; it stays pending until the signed-in shell has routed it. */
class MobileDeepLinkRequests internal constructor() : ViewModel() {
    private val mutablePending = MutableStateFlow<MobileDeepLink?>(null)
    internal val pending: StateFlow<MobileDeepLink?> = mutablePending.asStateFlow()

    /** Whether this launch opened a link, so the shell skips asks that would cover it. */
    internal var receivedLink: Boolean = false
        private set

    /** Only the newest link matters; a second tap replaces the first. */
    internal fun receive(link: MobileDeepLink) {
        receivedLink = true
        mutablePending.value = link
    }

    internal fun acknowledge(link: MobileDeepLink) {
        if (mutablePending.value == link) mutablePending.value = null
    }

    internal companion object {
        /** A fresh, empty holder for callers without an Activity; never shared between them. */
        val None: MobileDeepLinkRequests get() = MobileDeepLinkRequests()
    }
}
