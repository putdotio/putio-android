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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val continueWatching = SessionScopedHolder<MobileAuthState, MobileSessionKey, ContinueWatching>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = ContinueWatching::close,
    )

    // The sheet's own requests outlive a rotation, so a restore put.io accepted still settles the folder.
    private val actions = SessionScopedHolder<MobileAuthState, MobileSessionKey, MobileFilesActionsController>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = MobileFilesActionsController::close,
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
    ): ContinueWatching? =
        continueWatching.valueFor(MobileSessionKey(userId, sessionId)) { ContinueWatching(store()) }

    fun actionsFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        create: (CoroutineScope) -> MobileFilesActionsController,
    ): MobileFilesActionsController? =
        actions.valueFor(MobileSessionKey(userId, sessionId)) {
            val parent = viewModelScope.coroutineContext
            create(CoroutineScope(parent + SupervisorJob(parent[Job])))
        }

    override fun onCleared() {
        session.clear()
        continueWatching.clear()
        actions.clear()
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
