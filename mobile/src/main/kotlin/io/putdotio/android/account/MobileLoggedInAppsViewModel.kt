package io.putdotio.android.account

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

internal class MobileLoggedInAppsViewModel(
    authState: StateFlow<MobileAuthState>,
) : ViewModel() {
    private val session = SessionScopedHolder<MobileAuthState, MobileSessionKey, LoggedInAppsController>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = LoggedInAppsController::close,
    )

    fun controllerFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        repository: LoggedInAppsRepository,
    ): LoggedInAppsController? =
        session.valueFor(MobileSessionKey(userId, sessionId)) {
            LoggedInAppsController(repository, viewModelScope)
        }

    override fun onCleared() {
        session.clear()
    }
}

internal fun mobileLoggedInAppsViewModelFactory(authState: StateFlow<MobileAuthState>): ViewModelProvider.Factory =
    viewModelFactory { initializer { MobileLoggedInAppsViewModel(authState) } }
