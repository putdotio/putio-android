package io.putdotio.android.downloads

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One JSON document per user in private SharedPreferences. Rows hold only
 * identity, name, type, rendition and status; Media3 owns bytes and URIs.
 * Every write commits before the in-memory rows advance. Media3's own index
 * remains the source of truth for progress and is reconciled on start.
 */
internal class MobileDownloadStore internal constructor(
    private val preferences: SharedPreferences,
    private val key: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DownloadStore {
    constructor(context: Context, userId: Long) : this(
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        key = "user-$userId",
    )

    private val lock = Any()
    private val mutableEntries = MutableStateFlow(readAll())

    override val entries: StateFlow<List<DownloadEntry>> = mutableEntries.asStateFlow()

    override suspend fun upsert(entry: DownloadEntry) = write { current ->
        current.filterNot { it.fileId == entry.fileId } + entry
    }

    override suspend fun remove(fileId: FilesItemId) = write { current ->
        current.filterNot { it.fileId == fileId }
    }

    /** Engine callbacks arrive on the main thread; the status write is small and committed inline. */
    fun updateStatusBlocking(fileId: FilesItemId, transform: (DownloadEntry) -> DownloadEntry) {
        synchronized(lock) {
            val current = mutableEntries.value
            val entry = current.firstOrNull { it.fileId == fileId } ?: return
            val next = current.map { if (it.fileId == fileId) transform(entry) else it }
            preferences.edit(commit = true) { putString(key, next.toJson()) }
            mutableEntries.value = next
        }
    }

    fun removeBlocking(fileId: FilesItemId) {
        synchronized(lock) {
            val next = mutableEntries.value.filterNot { it.fileId == fileId }
            preferences.edit(commit = true) { putString(key, next.toJson()) }
            mutableEntries.value = next
        }
    }

    private suspend fun write(transform: (List<DownloadEntry>) -> List<DownloadEntry>) =
        withContext(ioDispatcher) {
            synchronized(lock) {
                val next = transform(mutableEntries.value)
                preferences.edit(commit = true) { putString(key, next.toJson()) }
                mutableEntries.value = next
            }
        }

    private fun readAll(): List<DownloadEntry> =
        preferences.getString(key, null)?.let { raw ->
            runCatching { JSONArray(raw).toEntries() }.getOrDefault(emptyList())
        }.orEmpty()

    private companion object {
        const val PREFERENCES_NAME = "io.putdotio.android.downloads"
    }
}

private fun List<DownloadEntry>.toJson(): String =
    JSONArray().also { array -> forEach { array.put(it.toJson()) } }.toString()

private fun DownloadEntry.toJson(): JSONObject =
    JSONObject()
        .put("fileId", fileId.value)
        .put("name", name)
        .put("type", type.raw)
        .put("artifact", artifact.name)
        .put("createdAt", createdAt)
        .put("status", status.toJson())

private fun DownloadStatus.toJson(): JSONObject =
    when (this) {
        DownloadStatus.Queued -> JSONObject().put("kind", "queued")
        is DownloadStatus.WaitingForNetwork -> JSONObject().put("kind", "waiting").put("bytes", bytesDownloaded)
        is DownloadStatus.Downloading ->
            JSONObject().put("kind", "downloading").put("bytes", bytesDownloaded)
                .also { totalBytes?.let { total -> it.put("total", total) } }
        is DownloadStatus.Failed ->
            JSONObject().put("kind", "failed").put("reason", reason.name).put("bytes", bytesDownloaded)
        is DownloadStatus.Completed -> JSONObject().put("kind", "completed").put("bytes", bytes)
    }

private fun JSONArray.toEntries(): List<DownloadEntry> =
    (0 until length()).mapNotNull { index -> optJSONObject(index)?.toEntryOrNull() }

private fun JSONObject.toEntryOrNull(): DownloadEntry? {
    val type = optString("type").takeIf { it.isNotBlank() }?.let(PutioFileType::fromRaw) ?: return null
    val artifact = DownloadArtifact.entries.firstOrNull { it.name == optString("artifact") } ?: return null
    val status = optJSONObject("status")?.toStatusOrNull() ?: return null
    val fileId = optLong("fileId", -1L).takeIf { it > 0L } ?: return null
    return DownloadEntry(
        fileId = FilesItemId(fileId),
        name = optString("name"),
        type = type,
        artifact = artifact,
        status = status,
        createdAt = optLong("createdAt"),
    )
}

private fun JSONObject.toStatusOrNull(): DownloadStatus? =
    when (optString("kind")) {
        // Neither a transfer nor a network wait survives the process; both resume from the cache as Queued.
        "queued", "waiting", "downloading" -> DownloadStatus.Queued
        "failed" -> DownloadStatus.Failed(
            DownloadFailureReason.entries.firstOrNull { it.name == optString("reason") }
                ?: DownloadFailureReason.UNEXPECTED,
            optLong("bytes"),
        )
        "completed" -> DownloadStatus.Completed(optLong("bytes"))
        else -> null
    }
