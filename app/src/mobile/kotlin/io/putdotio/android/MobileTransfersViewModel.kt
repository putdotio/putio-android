package io.putdotio.android

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
import io.putdotio.android.transfers.TransfersController
import io.putdotio.android.transfers.TransfersRepository
import kotlinx.coroutines.flow.StateFlow

internal class MobileTransfersViewModel(
    authState: StateFlow<MobileAuthState>,
) : ViewModel() {
    private val session = SessionScopedHolder<MobileAuthState, MobileSessionKey, TransfersController>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = TransfersController::close,
    )

    fun controllerFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        repository: TransfersRepository,
    ): TransfersController? =
        session.valueFor(MobileSessionKey(userId, sessionId)) {
            TransfersController(repository, viewModelScope)
        }

    override fun onCleared() {
        session.clear()
    }
}

internal fun mobileTransfersViewModelFactory(
    authState: StateFlow<MobileAuthState>,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            MobileTransfersViewModel(authState)
        }
    }
