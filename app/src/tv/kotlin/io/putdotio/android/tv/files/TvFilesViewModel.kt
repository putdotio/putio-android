package io.putdotio.android.tv.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Holds one Files controller per signed-in session so folder state survives
 * navigation and configuration changes but never outlives the session that
 * loaded it.
 */
internal class TvFilesViewModel(
    private val authState: StateFlow<TvAuthState>,
) : ViewModel() {
    private val lock = Any()
    private var active: ActiveSession? = null

    init {
        viewModelScope.launch { authState.collect { reconcile() } }
    }

    fun controllerFor(
        userId: Long,
        sessionId: TvAuthSessionId,
        repository: FilesRepository,
    ): FilesBrowserController? =
        synchronized(lock) {
            val key = SessionKey(userId, sessionId)
            if (authState.value.sessionKey() != key) return@synchronized null
            active?.takeIf { it.key == key }?.let { return@synchronized it.controller }
            active?.controller?.close()
            active = null
            val controller = FilesBrowserController(repository, viewModelScope)
            if (authState.value.sessionKey() != key) {
                controller.close()
                null
            } else {
                active = ActiveSession(key, controller)
                controller
            }
        }

    override fun onCleared() {
        synchronized(lock) {
            active?.controller?.close()
            active = null
        }
    }

    private fun reconcile() {
        synchronized(lock) {
            val current = active ?: return
            if (current.key != authState.value.sessionKey()) {
                current.controller.close()
                active = null
            }
        }
    }

    private fun TvAuthState.sessionKey(): SessionKey? =
        (this as? TvAuthState.SignedIn)?.let { SessionKey(it.account.userId, it.sessionId) }

    private data class ActiveSession(
        val key: SessionKey,
        val controller: FilesBrowserController,
    )

    private data class SessionKey(
        val userId: Long,
        val sessionId: TvAuthSessionId,
    )
}

internal fun tvFilesViewModelFactory(authState: StateFlow<TvAuthState>): ViewModelProvider.Factory =
    viewModelFactory { initializer { TvFilesViewModel(authState) } }
