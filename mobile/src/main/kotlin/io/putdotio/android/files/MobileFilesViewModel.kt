package io.putdotio.android.files

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

internal class MobileFilesViewModel(
    authState: StateFlow<MobileAuthState>,
) : ViewModel() {
    private val session = SessionScopedHolder<MobileAuthState, MobileSessionKey, FilesBrowserController>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = FilesBrowserController::close,
    )

    // Lives with the session, not the screen, so a rotation does not read every entry again.
    private val continueWatching = SessionScopedHolder<MobileAuthState, MobileSessionKey, MobileContinueWatching>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = MobileContinueWatching::close,
    )

    fun controllerFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        repository: FilesRepository,
    ): FilesBrowserController? =
        session.valueFor(MobileSessionKey(userId, sessionId)) {
            FilesBrowserController(repository, viewModelScope)
        }

    fun continueWatchingFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        store: () -> ContinueWatchingStore,
    ): MobileContinueWatching? =
        continueWatching.valueFor(MobileSessionKey(userId, sessionId)) { MobileContinueWatching(store()) }

    override fun onCleared() {
        session.clear()
        continueWatching.clear()
    }
}

internal fun mobileFilesViewModelFactory(
    authState: StateFlow<MobileAuthState>,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            MobileFilesViewModel(authState)
        }
    }
