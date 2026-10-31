package io.putdotio.android.tv.home

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.PinnedFiles
import io.putdotio.android.isNotFound
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
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

internal class TvHomePins(
    private val pins: PinnedFiles,
    private val resolver: FilesItemResolver,
    private val folderCounts: FilesFolderCounts?,
    scope: CoroutineScope,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    private var lastRead = clock.markNow()
    private val cards = mutableMapOf<FilesItemId, TvPinnedCard>()
    private val gone = mutableSetOf<FilesItemId>()
    private val refreshes = MutableStateFlow(0)
    private var resolvedRefresh = 0
    private val mutableRow = MutableStateFlow<TvPinnedRow>(TvPinnedRow.Loading)
    private val mutableRejected = MutableStateFlow<PutioFailure.AuthenticationRequired?>(null)

    val row: StateFlow<TvPinnedRow> = mutableRow.asStateFlow()
    val rejected: StateFlow<PutioFailure.AuthenticationRequired?> = mutableRejected.asStateFlow()

    init {
        scope.launch {
            combine(pins.pins, pins.failure, refreshes) { ids, failure, refresh -> Triple(ids, failure, refresh) }
                .collectLatest { (ids, failure, refresh) ->
                    when {
                        ids != null -> {
                            mutableRow.value = rowFor(ids, fresh = refresh != resolvedRefresh)
                            resolvedRefresh = refresh
                        }
                        failure != null -> mutableRow.value = TvPinnedRow.Failed(failure)
                    }
                }
        }
    }

    fun refresh() {
        when {
            pins.pins.value != null -> {
                if (lastRead.elapsedNow() < REFRESH_AFTER) return
                lastRead = clock.markNow()
                pins.reload()
                refreshes.update { it + 1 }
            }
            pins.failure.value != null -> pins.reload()
        }
    }

    private suspend fun rowFor(ids: List<FilesItemId>, fresh: Boolean): TvPinnedRow = coroutineScope {
        val resolved = ids.map { id -> async { resolve(id, fresh) } }.awaitAll()
        val shown = resolved.filterIsInstance<Resolved.Card>().map { it.card }
        val failure = resolved.filterIsInstance<Resolved.Failed>().firstOrNull()?.failure
        if (shown.isEmpty() && failure != null) TvPinnedRow.Failed(failure) else TvPinnedRow.Ready(shown)
    }

    private suspend fun resolve(id: FilesItemId, fresh: Boolean): Resolved {
        val known = cards[id]?.let(Resolved::Card) ?: Resolved.Gone.takeIf { id in gone }
        return if (fresh || known == null) read(id) else known
    }

    private suspend fun read(id: FilesItemId): Resolved =
        when (val result = resolver.resolveItem(id)) {
            is PutioResult.Success -> {
                val item = result.value
                val count = if (item.isFolder) folderCount(id) else null
                gone.remove(id)
                Resolved.Card(TvPinnedCard(item, count).also { cards[id] = it })
            }
            is PutioResult.Failure -> {
                val failure = result.failure
                when {
                    failure is PutioFailure.AuthenticationRequired -> {
                        mutableRejected.value = failure
                        Resolved.Failed(failure)
                    }
                    failure.isNotFound -> {
                        cards.remove(id)
                        if (gone.add(id)) pins.unpin(id)
                        Resolved.Gone
                    }
                    else -> cards[id]?.let(Resolved::Card) ?: Resolved.Failed(failure)
                }
            }
        }

    private suspend fun folderCount(id: FilesItemId): Int? =
        when (val result = folderCounts?.itemCount(id)) {
            is PutioResult.Success -> result.value
            is PutioResult.Failure -> {
                (result.failure as? PutioFailure.AuthenticationRequired)?.let { mutableRejected.value = it }
                null
            }
            null -> null
        }

    private sealed interface Resolved {
        data class Card(val card: TvPinnedCard) : Resolved

        data object Gone : Resolved

        data class Failed(val failure: PutioFailure) : Resolved
    }
}

private val REFRESH_AFTER = 1.minutes
