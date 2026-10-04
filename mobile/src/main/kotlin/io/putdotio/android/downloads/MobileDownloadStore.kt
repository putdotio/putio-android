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
 * One JSON document per user in private SharedPreferences. Rows hold identity,
 * name, type, rendition, status, queue time, a pending-delete mark, the confirmed
 * subtitle setting and the listing's saved position; Media3 owns bytes and URIs.
 * User and terminal writes commit before the in-memory rows advance; other
 * transitions apply asynchronously and byte progress is never written. Media3's
 * own index remains the source of truth for progress and is reconciled on start.
 */
internal class MobileDownloadStore internal constructor(
    private val preferences: SharedPreferences,
    private val key: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DownloadStore {
    constructor(context: Context, userId: Long) : this(
        preferences = downloadPreferences(context),
        key = storeKey(userId),
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

    /**
     * Engine transitions arrive on the main thread. Non-terminal ones persist
     * asynchronously; terminal states commit so a completed download survives an
     * immediate process death. Media3's own index is reconciled on start either way.
     */
    fun updateStatusBlocking(fileId: FilesItemId, transform: (DownloadEntry) -> DownloadEntry) {
        synchronized(lock) {
            val current = mutableEntries.value
            val entry = current.firstOrNull { it.fileId == fileId } ?: return
            val updated = transform(entry)
            if (updated == entry) return
            val next = current.map { if (it.fileId == fileId) updated else it }
            val terminal = updated.status is DownloadStatus.Completed || updated.status is DownloadStatus.Failed ||
                updated.status is DownloadStatus.Missing
            preferences.edit(commit = terminal) { putString(key, next.toJson()) }
            mutableEntries.value = next
        }
    }

    /** Advances a running row's bytes without a write; a stale report never overrides a transition. */
    fun updateProgressInMemory(fileId: FilesItemId, bytesDownloaded: Long, percent: Float?) {
        synchronized(lock) {
            val current = mutableEntries.value
            val entry = current.firstOrNull { it.fileId == fileId }
            val status = DownloadStatus.Downloading(bytesDownloaded, percent)
            if (entry?.status is DownloadStatus.Downloading && entry.status != status) {
                mutableEntries.value = current.map { if (it.fileId == fileId) it.copy(status = status) else it }
            }
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

    private fun readAll(): List<DownloadEntry> = preferences.readEntries(key)

    companion object {
        /**
         * One row as last written, for process-wide readers such as notifications that run
         * without this user's store. Never writes.
         */
        fun peek(context: Context, userId: Long, fileId: FilesItemId): DownloadEntry? =
            downloadPreferences(context).readEntries(storeKey(userId)).firstOrNull { it.fileId == fileId }
    }
}

/** Downloads keep their index, settings and offline positions in this one private file. */
internal fun downloadPreferences(context: Context): SharedPreferences =
    context.getSharedPreferences(DOWNLOAD_PREFERENCES_NAME, Context.MODE_PRIVATE)

private const val DOWNLOAD_PREFERENCES_NAME = "io.putdotio.android.downloads"

private fun storeKey(userId: Long): String = "user-$userId"

private fun SharedPreferences.readEntries(key: String): List<DownloadEntry> =
    getString(key, null)?.let { raw ->
        runCatching { JSONArray(raw).toEntries() }.getOrDefault(emptyList())
    }.orEmpty()

private fun List<DownloadEntry>.toJson(): String =
    JSONArray().also { array -> forEach { array.put(it.toJson()) } }.toString()

private fun DownloadEntry.toJson(): JSONObject =
    JSONObject()
        .put("fileId", fileId.value)
        .put("name", name)
        .put("type", type.raw)
        .put("artifact", artifact.name)
        .put("createdAt", createdAt)
        .put("queuedAt", queuedAt)
        .put("accepted", accepted)
        .put("removing", removing)
        .put("startFrom", startFromSeconds)
        .put("status", status.toJson())
        .also { json ->
            subtitlesHidden?.let { json.put("subtitlesHidden", it) }
            durationSeconds?.let { json.put("duration", it) }
        }

private fun DownloadStatus.toJson(): JSONObject =
    when (this) {
        DownloadStatus.Queued -> JSONObject().put("kind", "queued")
        is DownloadStatus.Paused ->
            JSONObject().put("kind", "paused").put("reason", reason.name).put("bytes", bytesDownloaded)
        is DownloadStatus.Downloading -> JSONObject().put("kind", "downloading").put("bytes", bytesDownloaded)
        is DownloadStatus.Failed ->
            JSONObject().put("kind", "failed").put("reason", reason.name).put("bytes", bytesDownloaded)
        is DownloadStatus.Completed -> JSONObject().put("kind", "completed").put("bytes", bytes)
        DownloadStatus.Missing -> JSONObject().put("kind", "missing")
    }

private fun JSONArray.toEntries(): List<DownloadEntry> =
    (0 until length()).mapNotNull { index -> optJSONObject(index)?.toEntryOrNull() }

private fun JSONObject.toEntryOrNull(): DownloadEntry? {
    val fileId = optLong("fileId", -1L).takeIf { it > 0L } ?: return null
    val type = optString("type").takeIf { it.isNotBlank() }?.let(PutioFileType::fromRaw)
    val artifact = DownloadArtifact.entries.firstOrNull { it.name == optString("artifact") }
    val status = optJSONObject("status")?.toStatusOrNull()
    return if (type == null || artifact == null || status == null) {
        null
    } else {
        val createdAt = optLong("createdAt")
        DownloadEntry(
            fileId = FilesItemId(fileId),
            name = optString("name"),
            type = type,
            artifact = artifact,
            status = status,
            createdAt = createdAt,
            accepted = optBoolean("accepted", false),
            queuedAt = optLong("queuedAt", createdAt),
            removing = optBoolean("removing", false),
            subtitlesHidden = if (has("subtitlesHidden")) optBoolean("subtitlesHidden") else null,
            startFromSeconds = optDouble("startFrom", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            durationSeconds = optDouble("duration", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
        )
    }
}

private fun JSONObject.toStatusOrNull(): DownloadStatus? =
    when (optString("kind")) {
        // Neither a transfer nor a pause survives the process; both resume from the cache as Queued.
        "queued", "waiting", "paused", "downloading" -> DownloadStatus.Queued
        "failed" -> DownloadStatus.Failed(
            DownloadFailureReason.entries.firstOrNull { it.name == optString("reason") }
                ?: DownloadFailureReason.UNEXPECTED,
            optLong("bytes"),
        )
        "completed" -> DownloadStatus.Completed(optLong("bytes"))
        "missing" -> DownloadStatus.Missing
        else -> null
    }
