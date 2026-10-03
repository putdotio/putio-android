package io.putdotio.android.trash

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

internal class MobileTrashViewModel(
    authState: StateFlow<MobileAuthState>,
) : ViewModel() {
    private val session = SessionScopedHolder<MobileAuthState, MobileSessionKey, TrashController>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = TrashController::close,
    )

    fun controllerFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        repository: TrashRepository,
    ): TrashController? =
        session.valueFor(MobileSessionKey(userId, sessionId)) {
            TrashController(repository, viewModelScope)
        }

    override fun onCleared() {
        session.clear()
    }
}

internal fun mobileTrashViewModelFactory(
    authState: StateFlow<MobileAuthState>,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            MobileTrashViewModel(authState)
        }
    }
