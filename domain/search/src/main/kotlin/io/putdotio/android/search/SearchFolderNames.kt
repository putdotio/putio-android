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

    public val names: StateFlow<Map<FilesItemId, String>> = mutableNames.asStateFlow()

    public val authenticationFailure: StateFlow<PutioFailure?> = mutableAuthenticationFailure.asStateFlow()

    init {
        scope.launch {
            search
                .map { state -> state.scope to (state.content as? SearchContent.Ready)?.takeIf { it.paging.landed } }
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
