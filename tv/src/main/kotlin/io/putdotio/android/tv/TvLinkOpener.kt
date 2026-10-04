package io.putdotio.android.tv

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Opens a file named by system search or a Watch Next card, as a product link opens one: the
 * id resolves in the session, then [open] plays media or shows it in Files. Only the latest
 * request opens. A card whose file put.io no longer has is reported to [onCardGone].
 */
internal class TvLinkOpener(
    private val resolver: FilesItemResolver,
    private val scope: CoroutineScope,
    private val open: (item: FilesItem, continueWatching: Boolean) -> TvExternalOpen,
    private val onCardGone: (fileId: Long) -> Unit,
) {
    private var pending: Job? = null
    private val opened = Channel<TvExternalOpen>(Channel.BUFFERED)
    private val mutableFailure = MutableStateFlow<PutioFailure?>(null)

    /** What each resolved request did. */
    val opens: Flow<TvExternalOpen> = opened.receiveAsFlow()

    /** Why the last request's file could not be resolved; cleared by the next success or dismissal. */
    val failure: StateFlow<PutioFailure?> = mutableFailure.asStateFlow()

    fun open(id: FilesItemId, continueWatching: Boolean) {
        pending?.cancel()
        pending = scope.launch {
            when (val result = resolver.resolveItem(id)) {
                is PutioResult.Success -> {
                    mutableFailure.value = null
                    opened.send(open(result.value, continueWatching))
                }
                is PutioResult.Failure -> {
                    mutableFailure.value = result.failure
                    if (continueWatching && result.failure.isNotFound) onCardGone(id.value)
                }
            }
        }
    }

    /** Drops the explanation the pane showed; a 401 stays, since it is a session verdict. */
    fun dismissFailure() {
        mutableFailure.update { it?.takeIf { failure -> failure is PutioFailure.AuthenticationRequired } }
    }

    fun close() {
        pending?.cancel()
        opened.close()
    }
}

/** put.io has no such file: a 404, which a trashed file reads as too. */
internal val PutioFailure.isNotFound: Boolean
    get() = this is PutioFailure.ApiRejected && statusCode == HTTP_NOT_FOUND && httpStatusCode == HTTP_NOT_FOUND

private const val HTTP_NOT_FOUND = 404
