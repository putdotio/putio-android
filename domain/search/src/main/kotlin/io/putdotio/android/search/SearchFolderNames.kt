package io.putdotio.android.search

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The names of the folders search results sit in. Results carry only their parent's id, so each
 * folder is read once, at most [MAX_PARALLEL_READS] at a time, and its name kept while the session
 * lasts. The search's own scope and folders among the results name themselves without a read, and
 * a read that answers later never replaces them; the root has no name to read. A failed read is
 * tried again when the next page or search lands, and a 401 is kept for the session to act on.
 */
public class SearchFolderNames internal constructor(
    private val resolve: suspend (FilesItemId) -> PutioResult<FilesItem>,
    search: StateFlow<SearchState>,
    parentScope: CoroutineScope,
) : Closeable {
    public constructor(
        resolver: FilesItemResolver,
        search: StateFlow<SearchState>,
        parentScope: CoroutineScope,
    ) : this(resolver::resolveItem, search, parentScope)

    private val lock = Any()
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val reads = Semaphore(MAX_PARALLEL_READS)
    private val requested = mutableSetOf<FilesItemId>()
    private val mutableNames = MutableStateFlow<Map<FilesItemId, String>>(emptyMap())
    private val mutableAuthenticationFailure = MutableStateFlow<PutioFailure?>(null)

    /** Folder names by id, as far as they are known. */
    public val names: StateFlow<Map<FilesItemId, String>> = mutableNames.asStateFlow()

    /** put.io's 401 to a folder read, which ends the session as any other 401 does. */
    public val authenticationFailure: StateFlow<PutioFailure?> = mutableAuthenticationFailure.asStateFlow()

    init {
        scope.launch {
            // Results land when a search or a page succeeds, even a page that adds no rows; a page
            // asked for or failed is no new result, so it retries nothing.
            search
                .map { state -> state.scope to (state.content as? SearchContent.Ready)?.takeIf { it.paging.landed } }
                // Each landing is a new Ready, even one equal to the last, as a repeat search's is.
                .distinctUntilChanged { old, new -> old.first == new.first && old.second === new.second }
                .collect { (searchScope, content) -> learn(searchScope, content?.items.orEmpty()) }
        }
    }

    private fun learn(
        searchScope: SearchScope?,
        items: List<FilesItem>,
    ) {
        val named = buildMap {
            searchScope?.folderName?.let { put(searchScope.folderId, it) }
            items.filter(FilesItem::isFolder).forEach { put(it.id, it.name) }
        }
        if (named.isNotEmpty()) mutableNames.update { it + named }
        val toRead =
            synchronized(lock) {
                items
                    .mapNotNull(FilesItem::parentId)
                    .distinct()
                    .filter { it != FilesFolder.Root.id && it !in mutableNames.value && requested.add(it) }
            }
        toRead.forEach { folderId -> scope.launch { read(folderId) } }
    }

    private suspend fun read(folderId: FilesItemId) {
        val name = reads.withPermit {
            // Results or the scope may have named it while this read waited for a permit.
            if (folderId in mutableNames.value) null else readName(folderId)
        }
        if (name != null) {
            mutableNames.update { if (folderId in it) it else it + (folderId to name) }
        } else if (folderId !in mutableNames.value) {
            synchronized(lock) { requested.remove(folderId) }
        }
    }

    // A name is decoration: an injected resolver that throws leaves the row without one.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun readName(folderId: FilesItemId): String? =
        try {
            when (val result = resolve(folderId)) {
                is PutioResult.Success -> result.value.name
                is PutioResult.Failure -> {
                    if (result.failure is PutioFailure.AuthenticationRequired) {
                        mutableAuthenticationFailure.value = result.failure
                    }
                    null
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    override fun close() {
        scope.cancel()
    }
}

private val SearchPaging.landed: Boolean
    get() = this is SearchPaging.Available || this == SearchPaging.Complete

private const val MAX_PARALLEL_READS = 4
