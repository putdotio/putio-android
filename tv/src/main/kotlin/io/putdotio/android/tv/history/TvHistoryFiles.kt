package io.putdotio.android.tv.history

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
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

internal data class TvHistoryFile(val sizeBytes: Long, val savedTo: TvSavedTo?)

internal class TvHistoryFiles(private val resolver: FilesItemResolver?) {
    private val files = mutableMapOf<FilesItemId, TvHistoryFile>()

    fun known(id: HistoryFileId): TvHistoryFile? = files[id.toFilesItemId()]

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
        return PutioResult.Success(TvHistoryFile(item.sizeBytes, savedTo).also { files[item.id] = it })
    }

    companion object {
        val None = TvHistoryFiles(null)
    }
}
