package io.putdotio.android.documents

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.search.SearchRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One session's folder and search listings, loaded a page at a time through the app's repositories. A query returns
 * the items that have landed and asks for the next page; each landed page reports its listing so the picker queries
 * again. A failed page is reported once and asked for again on the query after that, so a page that keeps failing
 * cannot loop the picker. An idle listing older than [FRESH_FOR] starts again from its first page, showing its old
 * rows until that page lands.
 *
 * A picker registers for changes only after its query returns, so a page that lands in that gap would go unheard;
 * a landed page is announced again every [RENOTIFY_AFTER], up to [RENOTIFY_TIMES] times, until a query reads it.
 */
internal class DocumentListings(
    private val files: FilesRepository,
    private val search: SearchRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    /** A page landed, with its failure the first time it is announced; runs outside the lock. */
    private val onLanded: (DocumentsListingKey, PutioFailure?) -> Unit,
) {
    private val lock = Any()
    private var closed = false
    private val listings = LinkedHashMap<DocumentsListingKey, Listing>(MAX_LISTINGS, LOAD_FACTOR, true)

    fun query(key: DocumentsListingKey): Snapshot =
        synchronized(lock) {
            val existing = listings[key]
            val current = existing?.takeUnless { it.isStale(clock()) }
            val next = when {
                closed -> Listing()
                // A stale listing keeps showing its rows until the fresh first page replaces them.
                current == null -> load(key, Listing(items = existing?.items.orEmpty()), cursor = null)
                current.job != null -> current
                current.failure != null && !current.failureShown -> current.copy(failureShown = true)
                current.failure != null -> load(key, current, current.failedCursor)
                current.next != null -> load(key, current, current.next)
                else -> current
            }
            if (!closed) listings[key] = next.copy(delivered = true)
            trim()
            Snapshot(next.items, loading = next.job != null, failed = next.failure != null)
        }

    /** An item this session has listed. */
    fun find(fileId: FilesItemId): FilesItem? =
        synchronized(lock) {
            listings.values.firstNotNullOfOrNull { listing -> listing.items.firstOrNull { it.id == fileId } }
        }

    /** The session ended: stop every load and forget every listing. */
    fun close() {
        synchronized(lock) {
            closed = true
            listings.values.forEach { it.job?.cancel() }
            listings.clear()
        }
    }

    /** Starts loading [cursor]'s page, or the first page without one; the caller holds [lock]. */
    private fun load(key: DocumentsListingKey, base: Listing, cursor: FilesCursor?): Listing {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = fetch(key, cursor)
            val own = currentCoroutineContext()[Job]
            val landing = synchronized(lock) {
                val current = listings[key]?.takeIf { it.job === own }
                if (closed || current == null) return@launch
                current.landed(result, cursor, clock()).also { listings[key] = it }.landings
            }
            onLanded(key, (result as? PutioResult.Failure)?.failure)
            repeat(RENOTIFY_TIMES) {
                delay(RENOTIFY_AFTER)
                if (!awaitsQuery(key, landing)) return@launch
                onLanded(key, null)
            }
        }
        listings[key] = base.copy(job = job, failure = null, failureShown = false)
        job.start()
        // A load that finished while starting has already stored what landed.
        return checkNotNull(listings[key])
    }

    private fun awaitsQuery(key: DocumentsListingKey, landing: Int): Boolean =
        synchronized(lock) { !closed && listings[key]?.let { it.landings == landing && !it.delivered } == true }

    private suspend fun fetch(key: DocumentsListingKey, cursor: FilesCursor?): PutioResult<Page> =
        when (key) {
            is DocumentsListingKey.Folder -> if (cursor == null) {
                files.loadFolder(key.folder.fileId)
            } else {
                files.loadNextPage(cursor)
            }.map { Page(it.items, it.nextCursor) }
            is DocumentsListingKey.Search -> if (cursor == null) {
                search.search(key.term)
            } else {
                search.loadNextPage(cursor)
            }.map { Page(it.items, it.nextCursor) }
        }

    /** Keeps the most recently read listings; the caller holds [lock]. */
    private fun trim() {
        val eldest = listings.entries.iterator()
        while (listings.size > MAX_LISTINGS && eldest.hasNext()) {
            eldest.next().value.job?.cancel()
            eldest.remove()
        }
    }

    internal data class Snapshot(val items: List<FilesItem>, val loading: Boolean, val failed: Boolean)

    private data class Page(val items: List<FilesItem>, val next: FilesCursor?)

    private data class Listing(
        val items: List<FilesItem> = emptyList(),
        val next: FilesCursor? = null,
        val job: Job? = null,
        val failure: PutioFailure? = null,
        val failedCursor: FilesCursor? = null,
        val failureShown: Boolean = false,
        val updatedAt: Long = 0L,
        /** How many pages have landed, so a later announcement can tell which landing it repeats. */
        val landings: Int = 0,
        /** A query has returned the latest landing. */
        val delivered: Boolean = true,
    ) {
        fun isStale(now: Long): Boolean = job == null && now - updatedAt > FRESH_FOR.inWholeMilliseconds

        fun landed(result: PutioResult<Page>, cursor: FilesCursor?, now: Long): Listing =
            when (result) {
                is PutioResult.Success -> copy(
                    items = if (cursor == null) {
                        result.value.items
                    } else {
                        (items + result.value.items).distinctBy { it.id }
                    },
                    next = result.value.next,
                    job = null,
                    updatedAt = now,
                )
                is PutioResult.Failure ->
                    copy(job = null, failure = result.failure, failedCursor = cursor, updatedAt = now)
            }.copy(landings = landings + 1, delivered = false)
    }

    private companion object {
        const val MAX_LISTINGS = 32
        const val LOAD_FACTOR = 0.75f
        val FRESH_FOR: Duration = 30.seconds
        val RENOTIFY_AFTER: Duration = 500.milliseconds
        const val RENOTIFY_TIMES = 3
    }
}

private inline fun <T, R> PutioResult<T>.map(transform: (T) -> R): PutioResult<R> =
    when (this) {
        is PutioResult.Success -> PutioResult.Success(transform(value))
        is PutioResult.Failure -> this
    }
