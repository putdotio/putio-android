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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MobileFilesViewModel(
    private val authState: StateFlow<MobileAuthState>,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : ViewModel() {
    private val session = SessionScopedHolder<MobileAuthState, MobileSessionKey, FilesBrowserController>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = FilesBrowserController::close,
    )

    private val continueWatching = SessionScopedHolder<MobileAuthState, MobileSessionKey, ContinueWatching>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = { held ->
            if (preparedContinueWatchingEntry?.second === held) {
                preparedContinueWatchingEntry = null
                mutablePreparedContinueWatching.value = null
            }
            held.close()
        },
    )

    private val pinnedFiles = SessionScopedHolder<MobileAuthState, MobileSessionKey, MobilePinnedFiles>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = MobilePinnedFiles::close,
    )

    private val actions = SessionScopedHolder<MobileAuthState, MobileSessionKey, MobileFilesActionsController>(
        authState = authState,
        keyOf = MobileAuthState::sessionKey,
        scope = viewModelScope,
        close = MobileFilesActionsController::close,
    )

    private val mutablePreparedContinueWatching = MutableStateFlow<MobileSessionKey?>(null)
    val preparedContinueWatching: StateFlow<MobileSessionKey?> = mutablePreparedContinueWatching.asStateFlow()
    private var preparedContinueWatchingEntry: Pair<MobileSessionKey, ContinueWatching>? = null
    private var preparingContinueWatching: MobileSessionKey? = null

    fun controllerFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        repository: FilesRepository,
    ): FilesBrowserController? =
        session.valueFor(MobileSessionKey(userId, sessionId)) {
            FilesBrowserController(repository, viewModelScope)
        }

    fun prepareContinueWatching(
        userId: Long,
        sessionId: MobileAuthSessionId,
        store: () -> ContinueWatchingStore,
    ) {
        val key = MobileSessionKey(userId, sessionId)
        if (preparingContinueWatching == key) return
        preparingContinueWatching = key
        viewModelScope.launch {
            val created = withContext(ioContext) { ContinueWatching(store()) }
            var adopted = false
            val held = continueWatching.valueFor(key) { created.also { adopted = true } }
            if (!adopted) created.close()
            if (held != null) {
                preparedContinueWatchingEntry = key to held
                mutablePreparedContinueWatching.value = key
            }
        }
    }

    fun continueWatchingIfPrepared(userId: Long, sessionId: MobileAuthSessionId): ContinueWatching? {
        val key = MobileSessionKey(userId, sessionId)
        return preparedContinueWatchingEntry?.takeIf { it.first == key && authState.value.sessionKey() == key }?.second
    }

    fun pinnedFilesFor(
        userId: Long,
        sessionId: MobileAuthSessionId,
        create: (CoroutineScope) -> MobilePinnedFiles,
    ): MobilePinnedFiles? =
        pinnedFiles.valueFor(MobileSessionKey(userId, sessionId)) {
            val parent = viewModelScope.coroutineContext
            create(CoroutineScope(parent + SupervisorJob(parent[Job])))
        }

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
        preparedContinueWatchingEntry = null
        preparingContinueWatching = null
        mutablePreparedContinueWatching.value = null
        session.clear()
        continueWatching.clear()
        pinnedFiles.clear()
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
