package io.putdotio.android.tv.home

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.PinnedFiles
import io.putdotio.android.isNotFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface TvPinnedRow {
    data object Loading : TvPinnedRow

    data class Ready(val cards: List<TvPinnedCard>) : TvPinnedRow

    data class Failed(val failure: PutioFailure) : TvPinnedRow
}

internal data class TvPinnedCard(val item: FilesItem, val itemCount: Int?)

/**
 * Home's Pinned row: each pinned id read as its file, and a folder's item count. A file put.io no
 * longer has is unpinned; other failures leave its card out until the next [refresh]. A 401 is
 * kept in [rejected] for the session to act on.
 */
internal class TvHomePins(
    private val pins: PinnedFiles,
    private val resolver: FilesItemResolver,
    private val folderCounts: FilesFolderCounts?,
    scope: CoroutineScope,
) {
    private val cards = mutableMapOf<FilesItemId, TvPinnedCard>()
    private val refreshes = MutableStateFlow(0)
    private val mutableRow = MutableStateFlow<TvPinnedRow>(TvPinnedRow.Loading)
    private val mutableRejected = MutableStateFlow<PutioFailure.AuthenticationRequired?>(null)

    val row: StateFlow<TvPinnedRow> = mutableRow.asStateFlow()
    val rejected: StateFlow<PutioFailure.AuthenticationRequired?> = mutableRejected.asStateFlow()

    init {
        scope.launch {
            combine(pins.pins, pins.failure, refreshes) { ids, failure, _ -> ids to failure }
                .collectLatest { (ids, failure) ->
                    when {
                        ids != null -> mutableRow.value = TvPinnedRow.Ready(cardsFor(ids))
                        failure != null -> mutableRow.value = TvPinnedRow.Failed(failure)
                    }
                }
        }
    }

    fun refresh() {
        if (pins.pins.value == null) {
            pins.retry()
        } else {
            cards.clear()
            refreshes.update { it + 1 }
        }
    }

    private suspend fun cardsFor(ids: List<FilesItemId>): List<TvPinnedCard> = coroutineScope {
        ids.map { id -> async { cards[id] ?: card(id)?.also { cards[id] = it } } }.awaitAll().filterNotNull()
    }

    private suspend fun card(id: FilesItemId): TvPinnedCard? =
        when (val result = resolver.resolveItem(id)) {
            is PutioResult.Success -> {
                val item = result.value
                val count = if (item.isFolder) (folderCounts?.itemCount(id) as? PutioResult.Success)?.value else null
                TvPinnedCard(item, count)
            }
            is PutioResult.Failure -> {
                val failure = result.failure
                when {
                    failure is PutioFailure.AuthenticationRequired -> mutableRejected.value = failure
                    failure.isNotFound -> pins.unpin(id)
                }
                null
            }
        }
}
