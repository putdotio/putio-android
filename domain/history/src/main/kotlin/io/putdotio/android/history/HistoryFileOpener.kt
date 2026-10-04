package io.putdotio.android.history

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesOpenOrigin
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
public class HistoryFileOpener(
    requests: Flow<HistoryEffect.NavigateToFile>,
    private val resolver: FilesItemResolver,
    private val deliver: suspend (FilesItem, FilesOpenOrigin) -> Unit,
    parentScope: CoroutineScope,
) : Closeable {
    private val openerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + openerJob)
    private val mutableFailure = MutableStateFlow<PutioFailure?>(null)

    /** Why the last file could not be resolved; cleared by the next success or dismissal. */
    public val failure: StateFlow<PutioFailure?> = mutableFailure.asStateFlow()

    init {
        scope.launch {
            requests.collectLatest { request -> open(FilesItemId(request.fileId.value), FilesOpenOrigin.HISTORY) }
        }
    }

    /** Resolves and delivers one file; [origin] says whether a history row or a product link named it. */
    public suspend fun open(fileId: FilesItemId, origin: FilesOpenOrigin) {
        when (val result = resolver.resolveItem(fileId)) {
            is PutioResult.Success -> {
                mutableFailure.value = null
                deliver(result.value, origin)
            }
            is PutioResult.Failure -> mutableFailure.value = result.failure
        }
    }

    /** Drops the explanation the pane showed; a 401 stays, since it is a session verdict. */
    public fun dismissFailure() {
        mutableFailure.update { it?.takeIf { failure -> failure is PutioFailure.AuthenticationRequired } }
    }

    override fun close() {
        scope.cancel()
    }
}
