package io.putdotio.android.tv.files

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
    ): FilesBrowserController? = sessionFor(userId, sessionId, repository)?.controller

    /**
     * Which row last held D-pad focus in each folder. It lives here, not in the pane,
     * because the pane is disposed whenever another destination is shown and must
     * come back to the same row; it dies with the session like the controller.
     */
    fun focusMemoryFor(
        userId: Long,
        sessionId: TvAuthSessionId,
        repository: FilesRepository,
    ): SnapshotStateMap<Long, Long>? = sessionFor(userId, sessionId, repository)?.focusMemory

    private fun sessionFor(
        userId: Long,
        sessionId: TvAuthSessionId,
        repository: FilesRepository,
    ): ActiveSession? =
        synchronized(lock) {
            val key = SessionKey(userId, sessionId)
            if (authState.value.sessionKey() != key) return@synchronized null
            active?.takeIf { it.key == key }?.let { return@synchronized it }
            active?.controller?.close()
            active = null
            val controller = FilesBrowserController(repository, viewModelScope)
            if (authState.value.sessionKey() != key) {
                controller.close()
                null
            } else {
                ActiveSession(key, controller, mutableStateMapOf()).also { active = it }
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

    private class ActiveSession(
        val key: SessionKey,
        val controller: FilesBrowserController,
        val focusMemory: SnapshotStateMap<Long, Long>,
    )

    private data class SessionKey(
        val userId: Long,
        val sessionId: TvAuthSessionId,
    )
}

internal fun tvFilesViewModelFactory(authState: StateFlow<TvAuthState>): ViewModelProvider.Factory =
    viewModelFactory { initializer { TvFilesViewModel(authState) } }
