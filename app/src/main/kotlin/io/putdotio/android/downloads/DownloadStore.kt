package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import kotlinx.coroutines.flow.StateFlow

/**
 * Persisted download index for one signed-in user. Implementations never store
 * URLs or credentials; a row is identity, name, type, rendition and status.
 */
interface DownloadStore {
    val entries: StateFlow<List<DownloadEntry>>

    suspend fun upsert(entry: DownloadEntry)

    suspend fun remove(fileId: FilesItemId)

    fun find(fileId: FilesItemId): DownloadEntry? = entries.value.firstOrNull { it.fileId == fileId }
}
