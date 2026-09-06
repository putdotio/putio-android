package io.putdotio.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class MobileTrashViewModel(
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
        repository: TrashRepository,
    ): TrashController? =
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

            val controller = TrashController(
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
        val controller: TrashController,
    )

    private data class SessionKey(
        val userId: Long,
        val sessionId: MobileAuthSessionId,
    )
}

internal fun mobileTrashViewModelFactory(
    authState: StateFlow<MobileAuthState>,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            MobileTrashViewModel(authState)
        }
    }
