package io.putdotio.android.sharing

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

/** One public links controller per signed-in session, shared by Files and Account. */
internal class MobilePublicLinksViewModel(
    authState: StateFlow<MobileAuthState>,
) : ViewModel() {
    private val session = SessionScopedHolder<MobileAuthState, MobileSessionKey, PublicLinksController>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = PublicLinksController::close,
    )

    fun controllerFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        repository: PublicLinksRepository,
    ): PublicLinksController? =
        session.valueFor(MobileSessionKey(userId, sessionId)) {
            PublicLinksController(repository, viewModelScope)
        }

    override fun onCleared() {
        session.clear()
    }
}

internal fun mobilePublicLinksViewModelFactory(authState: StateFlow<MobileAuthState>): ViewModelProvider.Factory =
    viewModelFactory { initializer { MobilePublicLinksViewModel(authState) } }
