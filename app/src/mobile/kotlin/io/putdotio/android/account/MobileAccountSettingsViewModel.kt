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
import io.putdotio.android.settings.AccountSettingsController
import io.putdotio.android.settings.AccountSettingsRepository
import kotlinx.coroutines.flow.StateFlow

internal class MobileAccountSettingsViewModel(
    authState: StateFlow<MobileAuthState>,
) : ViewModel() {
    private val session = SessionScopedHolder<MobileAuthState, MobileSessionKey, AccountSettingsController>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = AccountSettingsController::close,
    )

    fun controllerFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        repository: AccountSettingsRepository,
    ): AccountSettingsController? =
        session.valueFor(MobileSessionKey(userId, sessionId)) {
            AccountSettingsController(repository, viewModelScope)
        }

    override fun onCleared() {
        session.clear()
    }
}

internal fun mobileAccountSettingsViewModelFactory(
    authState: StateFlow<MobileAuthState>,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            MobileAccountSettingsViewModel(authState)
        }
    }
