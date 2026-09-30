package io.putdotio.android.history

import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesRepositoryResult
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Resolves the file a history row names into the item Files opens and hands it to [deliver].
 * It runs in the session rather than the pane so a row chosen just before the pane is disposed
 * still opens. Only the latest choice counts: a second tap while the first still resolves
 * must not open two folders in turn.
 */
class HistoryFileOpener(
    requests: Flow<HistoryEffect.NavigateToFile>,
    private val resolver: FilesItemResolver,
    private val deliver: suspend (FilesItem) -> Unit,
    parentScope: CoroutineScope,
) : Closeable {
    private val openerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + openerJob)
    private val mutableFailure = MutableStateFlow<FilesFailure?>(null)

    /** Why the last file could not be resolved; cleared by the next success or dismissal. */
    val failure: StateFlow<FilesFailure?> = mutableFailure.asStateFlow()

    init {
        scope.launch {
            requests.collectLatest { request -> open(FilesItemId(request.fileId.value)) }
        }
    }

    /** Resolves and delivers one file outside the history list, such as a product link. */
    suspend fun open(fileId: FilesItemId) {
        when (val result = resolver.resolveItem(fileId)) {
            is FilesRepositoryResult.Success -> {
                mutableFailure.value = null
                deliver(result.value)
            }
            is FilesRepositoryResult.Failure -> mutableFailure.value = result.failure
        }
    }

    /** Drops the explanation the pane showed; a 401 stays, since it is a session verdict. */
    fun dismissFailure() {
        mutableFailure.update { it?.takeIf { failure -> failure is FilesFailure.AuthenticationRequired } }
    }

    override fun close() {
        scope.cancel()
    }
}
