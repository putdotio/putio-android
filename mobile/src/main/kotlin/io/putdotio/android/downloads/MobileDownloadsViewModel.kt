package io.putdotio.android.downloads

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileSessionKey
import io.putdotio.android.auth.sessionKey
import io.putdotio.android.session.SessionScopedHolder
import kotlinx.coroutines.flow.StateFlow

/** Owns one downloads controller per signed-in user; the index and cache outlive sign-out. */
internal class MobileDownloadsViewModel(
    authState: StateFlow<MobileAuthState>,
) : ViewModel() {
    private val session = SessionScopedHolder<MobileAuthState, MobileSessionKey, ActiveDownloads>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = ActiveDownloads::close,
        // The session ended or changed hands: park this user's transfers, then detach.
        onSessionEnded = {
            it.park()
            it.close()
        },
    )

    fun controllerFor(context: Context, userId: Long, sessionId: MobileAuthSessionId): DownloadsController? =
        session.valueFor(MobileSessionKey(userId, sessionId)) {
            val store = MobileDownloadStore(context.applicationContext, userId)
            val engine = MobileDownloadEngine(context.applicationContext, store, userId, viewModelScope)
            ActiveDownloads(DownloadsController(store, engine, viewModelScope), engine)
        }?.controller

    override fun onCleared() {
        session.clear()
    }

    private class ActiveDownloads(
        val controller: DownloadsController,
        private val engine: MobileDownloadEngine,
    ) {
        fun park() = engine.park()

        fun close() {
            controller.close()
            engine.close()
        }
    }
}

internal fun mobileDownloadsViewModelFactory(authState: StateFlow<MobileAuthState>): ViewModelProvider.Factory =
    viewModelFactory { initializer { MobileDownloadsViewModel(authState) } }
