package io.putdotio.android

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A pending product link; it stays pending until the signed-in shell has routed it. */
class MobileDeepLinkRequests internal constructor() : ViewModel() {
    private val mutablePending = MutableStateFlow<MobileDeepLink?>(null)
    internal val pending: StateFlow<MobileDeepLink?> = mutablePending.asStateFlow()

    /** Only the newest link matters; a second tap replaces the first. */
    internal fun receive(link: MobileDeepLink) {
        mutablePending.value = link
    }

    internal fun acknowledge(link: MobileDeepLink) {
        if (mutablePending.value == link) mutablePending.value = null
    }

    internal companion object {
        val None = MobileDeepLinkRequests()
    }
}
