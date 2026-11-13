package io.putdotio.android.tv.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.isNotFound
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.toFilesItemId

internal sealed interface TvSavedTo {
    data object YourFiles : TvSavedTo

    data class Folder(val name: String) : TvSavedTo
}

internal data class TvHistoryFile(val item: FilesItem, val savedTo: TvSavedTo?) {
    val sizeBytes: Long get() = item.sizeBytes
}

internal sealed interface TvHistoryLookup {
    data object Pending : TvHistoryLookup

    data class Found(val file: TvHistoryFile) : TvHistoryLookup

    data object Gone : TvHistoryLookup

    data object Unknown : TvHistoryLookup
}

internal class TvHistoryFiles(private val resolver: FilesItemResolver?) {
    private val files = mutableMapOf<FilesItemId, TvHistoryFile>()
    private val gone = mutableSetOf<FilesItemId>()
    private var changes by mutableLongStateOf(0L)

    val changesRead: Long get() = changes

    fun known(id: HistoryFileId): TvHistoryLookup? {
        val itemId = id.toFilesItemId()
        return when {
            itemId in gone -> TvHistoryLookup.Gone
            else -> files[itemId]?.let(TvHistoryLookup::Found)
        }
    }

    fun forget(id: FilesItemId) {
        val dropped = files.remove(id) != null
        if (gone.remove(id) || dropped) changes++
    }

    fun forgetWithChildren(id: FilesItemId) {
        val children = files.filterValues { it.item.parentId == id }.keys
        files.keys.removeAll(children)
        forget(id)
        if (children.isNotEmpty()) changes++
    }

    fun forgetAll() {
        files.clear()
        gone.clear()
        changes++
    }

    suspend fun lookup(id: HistoryFileId, onRejected: suspend () -> Unit): TvHistoryLookup =
        when (val result = load(id)) {
            null -> TvHistoryLookup.Unknown
            is PutioResult.Success -> TvHistoryLookup.Found(result.value)
            is PutioResult.Failure -> if (result.failure.isNotFound) {
                gone += id.toFilesItemId()
                TvHistoryLookup.Gone
            } else {
                if (result.failure is PutioFailure.AuthenticationRequired) onRejected()
                TvHistoryLookup.Unknown
            }
        }

    suspend fun load(id: HistoryFileId): PutioResult<TvHistoryFile>? {
        val itemId = id.toFilesItemId()
        val known = files[itemId]
        return when {
            known != null -> PutioResult.Success(known)
            resolver == null -> null
            else -> when (val result = resolver.resolveItem(itemId)) {
                is PutioResult.Success -> describe(resolver, result.value)
                is PutioResult.Failure -> result
            }
        }
    }

    private suspend fun describe(resolver: FilesItemResolver, item: FilesItem): PutioResult<TvHistoryFile> {
        val parent = item.parentId?.takeUnless { it == FilesFolder.Root.id }?.let { resolver.resolveItem(it) }
        if (parent is PutioResult.Failure && parent.failure is PutioFailure.AuthenticationRequired) return parent
        val savedTo = when {
            item.parentId == null -> null
            parent == null -> TvSavedTo.YourFiles
            parent is PutioResult.Success -> TvSavedTo.Folder(parent.value.name)
            else -> null
        }
        return PutioResult.Success(TvHistoryFile(item, savedTo).also { files[item.id] = it })
    }

    companion object {
        val None = TvHistoryFiles(null)
    }
}
