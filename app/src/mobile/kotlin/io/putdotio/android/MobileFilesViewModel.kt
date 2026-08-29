package io.putdotio.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class MobileFilesViewModel(
    private val authState: StateFlow<MobileAuthState>,
) : ViewModel() {
    private val activeSessionLock = Any()
    private var activeSession: ActiveSession? = null

    init {
        viewModelScope.launch {
            authState.collect {
                reconcileActiveSession()
            }
        }
    }

    fun controllerFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        repository: FilesRepository,
    ): FilesBrowserController? =
        synchronized(activeSessionLock) {
            val key = SessionKey(userId, sessionId)
            if (authState.value.sessionKey() != key) {
                return@synchronized null
            }
            activeSession?.takeIf { it.key == key }?.controller?.let {
                return@synchronized it
            }

            activeSession?.controller?.close()
            activeSession = null
            if (authState.value.sessionKey() != key) {
                return@synchronized null
            }

            val controller = FilesBrowserController(
                repository = repository,
                parentScope = viewModelScope,
            )
            if (authState.value.sessionKey() != key) {
                controller.close()
                null
            } else {
                activeSession = ActiveSession(key, controller)
                controller
            }
        }

    private fun MobileAuthState.sessionKey(): SessionKey? =
        (this as? MobileAuthState.SignedIn)?.let {
            SessionKey(it.account.userId, it.sessionId)
        }

    override fun onCleared() {
        clearActiveSession()
    }

    private fun clearActiveSession() {
        synchronized(activeSessionLock) {
            activeSession?.controller?.close()
            activeSession = null
        }
    }

    private fun reconcileActiveSession() {
        synchronized(activeSessionLock) {
            val active = activeSession ?: return
            if (active.key != authState.value.sessionKey()) {
                active.controller.close()
                activeSession = null
            }
        }
    }

    private data class ActiveSession(
        val key: SessionKey,
        val controller: FilesBrowserController,
    )

    private data class SessionKey(
        val userId: Long,
        val sessionId: MobileAuthSessionId,
    )
}

internal fun mobileFilesViewModelFactory(
    authState: StateFlow<MobileAuthState>,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            MobileFilesViewModel(authState)
        }
    }
