package io.putdotio.android

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class MobileDeepLinkRequests : ViewModel() {
    private val mutablePending = MutableStateFlow<MobileDeepLink?>(null)
    internal val pending: StateFlow<MobileDeepLink?> = mutablePending.asStateFlow()

    internal var receivedLink: Boolean = false
        private set

    internal fun receive(link: MobileDeepLink) {
        receivedLink = true
        mutablePending.value = link
    }

    internal fun acknowledge(link: MobileDeepLink) {
        if (mutablePending.value == link) mutablePending.value = null
    }

    internal companion object {
        val None: MobileDeepLinkRequests get() = MobileDeepLinkRequests()
    }
}
