package io.putdotio.android

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.MobileDownloadEngine
import io.putdotio.android.downloads.MobileDownloadStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Owns one downloads controller per signed-in user; the index and cache outlive sign-out. */
internal class MobileDownloadsViewModel(
    private val authState: StateFlow<MobileAuthState>,
) : ViewModel() {
    private val lock = Any()
    private var active: ActiveDownloads? = null

    init {
        viewModelScope.launch { authState.collect { reconcile() } }
    }

    fun controllerFor(context: Context, userId: Long, sessionId: MobileAuthSessionId): DownloadsController? =
        synchronized(lock) {
            val key = DownloadsSessionKey(userId, sessionId)
            if (authState.value.sessionKey() != key) return@synchronized null
            active?.takeIf { it.key == key }?.controller?.let { return@synchronized it }
            active?.close()
            active = null
            val store = MobileDownloadStore(context.applicationContext, userId)
            val engine = MobileDownloadEngine(context.applicationContext, store, userId)
            val controller = DownloadsController(store, engine, viewModelScope)
            if (authState.value.sessionKey() != key) {
                controller.close()
                engine.close()
                null
            } else {
                active = ActiveDownloads(key, controller, engine)
                controller
            }
        }

    override fun onCleared() {
        synchronized(lock) {
            active?.close()
            active = null
        }
    }

    // The session ended or changed hands: park this user's transfers, then detach.
    private fun reconcile() {
        synchronized(lock) {
            val current = active ?: return
            if (current.key != authState.value.sessionKey()) {
                current.park()
                current.close()
                active = null
            }
        }
    }

    private fun MobileAuthState.sessionKey(): DownloadsSessionKey? =
        (this as? MobileAuthState.SignedIn)?.let { DownloadsSessionKey(it.account.userId, it.sessionId) }

    private class ActiveDownloads(
        val key: DownloadsSessionKey,
        val controller: DownloadsController,
        private val engine: MobileDownloadEngine,
    ) {
        fun park() = engine.park()

        fun close() {
            controller.close()
            engine.close()
        }
    }

    private data class DownloadsSessionKey(val userId: Long, val sessionId: MobileAuthSessionId)
}

internal fun mobileDownloadsViewModelFactory(authState: StateFlow<MobileAuthState>): ViewModelProvider.Factory =
    viewModelFactory { initializer { MobileDownloadsViewModel(authState) } }
