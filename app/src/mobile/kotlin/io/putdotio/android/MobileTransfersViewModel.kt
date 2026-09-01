package io.putdotio.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.transfers.TransfersController
import io.putdotio.android.transfers.TransfersRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class MobileTransfersViewModel(
    private val authState: StateFlow<MobileAuthState>,
) : ViewModel() {
    private val lock = Any()
    private var activeSession: ActiveTransfersSession? = null

    init {
        viewModelScope.launch {
            authState.collect { reconcileActiveSession() }
        }
    }

    fun controllerFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        repository: TransfersRepository,
    ): TransfersController? =
        synchronized(lock) {
            val key = TransfersSessionKey(userId, sessionId)
            if (authState.value.sessionKey() != key) return@synchronized null
            activeSession?.takeIf { it.key == key }?.controller?.let { return@synchronized it }

            activeSession?.controller?.close()
            activeSession = null
            if (authState.value.sessionKey() != key) return@synchronized null

            val controller = TransfersController(repository, viewModelScope)
            if (authState.value.sessionKey() != key) {
                controller.close()
                null
            } else {
                activeSession = ActiveTransfersSession(key, controller)
                controller
            }
        }

    override fun onCleared() {
        clearActiveSession()
    }

    private fun reconcileActiveSession() {
        synchronized(lock) {
            val active = activeSession ?: return
            if (active.key != authState.value.sessionKey()) {
                active.controller.close()
                activeSession = null
            }
        }
    }

    private fun clearActiveSession() {
        synchronized(lock) {
            activeSession?.controller?.close()
            activeSession = null
        }
    }

    private fun MobileAuthState.sessionKey(): TransfersSessionKey? =
        (this as? MobileAuthState.SignedIn)?.let {
            TransfersSessionKey(it.account.userId, it.sessionId)
        }
}

private data class ActiveTransfersSession(
    val key: TransfersSessionKey,
    val controller: TransfersController,
)

private data class TransfersSessionKey(
    val userId: Long,
    val sessionId: MobileAuthSessionId,
)

internal fun mobileTransfersViewModelFactory(
    authState: StateFlow<MobileAuthState>,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            MobileTransfersViewModel(authState)
        }
    }
