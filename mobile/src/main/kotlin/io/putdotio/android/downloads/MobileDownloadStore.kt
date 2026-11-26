package io.putdotio.android.downloads

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
    private val document = preferences.readDocument(key)
    private val mutableEntries = MutableStateFlow(document.entries)

    private var unreadable = document.unreadable

    override val entries: StateFlow<List<DownloadEntry>> = mutableEntries.asStateFlow()

    override suspend fun upsert(entry: DownloadEntry) = write { current ->
        current.filterNot { it.fileId == entry.fileId } + entry
    }

    override suspend fun remove(fileId: FilesItemId) = write { current ->
        forgetUnreadable(fileId)
        current.filterNot { it.fileId == fileId }
    }

    suspend fun updateStatus(fileId: FilesItemId, transform: (DownloadEntry) -> DownloadEntry) = write { current ->
        current.map { if (it.fileId == fileId) transform(it) else it }
    }

    fun updateStatusBlocking(fileId: FilesItemId, transform: (DownloadEntry) -> DownloadEntry) {
        synchronized(lock) {
            val current = mutableEntries.value
            val entry = current.firstOrNull { it.fileId == fileId } ?: return
            val updated = transform(entry)
            if (updated == entry) return
            val next = current.map { if (it.fileId == fileId) updated else it }
            preferences.edit { putString(key, next.toJson(unreadable)) }
            mutableEntries.value = next
        }
    }

    fun addBlocking(entry: DownloadEntry) {
        synchronized(lock) {
            if (mutableEntries.value.any { it.fileId == entry.fileId }) return
            val next = mutableEntries.value + entry
            preferences.edit { putString(key, next.toJson(unreadable)) }
            mutableEntries.value = next
        }
    }

    fun updateProgressInMemory(fileId: FilesItemId, bytesDownloaded: Long, percent: Float?, contentLength: Long?) {
        synchronized(lock) {
            val current = mutableEntries.value
            val entry = current.firstOrNull { it.fileId == fileId }
            val status = DownloadStatus.Downloading(bytesDownloaded, percent, contentLength)
            if (entry?.status is DownloadStatus.Downloading && entry.status != status) {
                mutableEntries.value = current.map { if (it.fileId == fileId) it.copy(status = status) else it }
            }
        }
    }

    fun removeBlocking(fileId: FilesItemId) {
        synchronized(lock) {
            forgetUnreadable(fileId)
            val next = mutableEntries.value.filterNot { it.fileId == fileId }
            preferences.edit { putString(key, next.toJson(unreadable)) }
            mutableEntries.value = next
        }
    }

    private fun forgetUnreadable(fileId: FilesItemId) {
        unreadable = unreadable.filterNot { (it as? JSONObject)?.optLong("fileId") == fileId.value }
    }

    private suspend fun write(transform: (List<DownloadEntry>) -> List<DownloadEntry>) =
        withContext(ioDispatcher) {
            synchronized(lock) {
                val next = transform(mutableEntries.value)
                preferences.edit { putString(key, next.toJson(unreadable)) }
                mutableEntries.value = next
            }
            preferences.flush()
        }

    companion object {
        private val shared = WeakHashMap<Context, MutableMap<Long, MobileDownloadStore>>()

        internal fun existingShared(context: Context, userId: Long): MobileDownloadStore? {
            val application = context.applicationContext
            return synchronized(shared) { shared[application]?.get(userId) }
        }

        fun shared(context: Context, userId: Long): MobileDownloadStore {
            val application = context.applicationContext
            existingShared(application, userId)?.let { return it }
            val created = MobileDownloadStore(application, userId)
            return synchronized(shared) {
                shared.getOrPut(application) { mutableMapOf() }
                    .getOrPut(userId) { created }
            }
        }

        fun peek(context: Context, userId: Long, fileId: FilesItemId): DownloadEntry? {
            val loaded = existingShared(context, userId)
            return if (loaded != null) {
                loaded.find(fileId)
            } else {
                downloadPreferences(context).readDocument(storeKey(userId))
                    .entries.firstOrNull { it.fileId == fileId }
            }
        }

        @VisibleForTesting
        internal fun clearSharedForTest(context: Context) {
            val application = context.applicationContext
            synchronized(shared) { shared.remove(application) }
        }
    }
}

internal fun downloadPreferences(context: Context): SharedPreferences =
    context.getSharedPreferences(DOWNLOAD_PREFERENCES_NAME, Context.MODE_PRIVATE)

private const val DOWNLOAD_PREFERENCES_NAME = "io.putdotio.android.downloads"

private fun storeKey(userId: Long): String = "user-$userId"

private fun SharedPreferences.flush() {
    edit(commit = true) {}
}

private class StoredRows(val entries: List<DownloadEntry>, val unreadable: List<Any>)

private fun SharedPreferences.readDocument(key: String): StoredRows {
    val raw = getString(key, null)
    val parsed = raw?.let { runCatching { JSONArray(it) }.getOrNull() }
    if (raw != null && parsed == null && !contains("$key.unreadable")) edit { putString("$key.unreadable", raw) }
    val array = parsed ?: JSONArray()
    val entries = mutableListOf<DownloadEntry>()
    val unreadable = mutableListOf<Any>()
    for (index in 0 until array.length()) {
        val item = array.opt(index)
        val entry = runCatching { (item as? JSONObject)?.toEntryOrNull() }.getOrNull()
        if (entry == null) item?.let(unreadable::add) else entries += entry
    }
    val rows = entries.groupBy { it.fileId }.values.map { rows -> rows.firstOrNull { !it.recovered } ?: rows.first() }
    return StoredRows(rows, unreadable)
}

private fun List<DownloadEntry>.toJson(unreadable: List<Any>): String =
    JSONArray().also { array ->
        forEach { array.put(it.toJson()) }
        unreadable.forEach(array::put)
    }.toString()

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
        .put("recovered", recovered)
        .put("startFrom", startFromSeconds)
        .put("status", status.toJson())
        .also { json ->
            subtitlesHidden?.let { json.put("subtitlesHidden", it) }
            durationSeconds?.let { json.put("duration", it) }
            if (pauseRequested) json.put("pauseRequested", true)
            if (subtitles.isNotEmpty()) json.put("subtitles", subtitles.toJson())
            if (directPlayChosen) json.put("directPlayChosen", true)
            if (directPlayUnanswered) json.put("directPlayUnanswered", true)
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

private fun JSONObject.toEntryOrNull(): DownloadEntry? {
    val fileId = optLong("fileId", -1L).takeIf { it > 0L } ?: return null
    val type = optString("type").takeIf { it.isNotBlank() }?.let(PutioFileType::fromRaw)
    val artifact = DownloadArtifact.entries.firstOrNull { it.name == optString("artifact") }
    val pauseRequested = optBoolean("pauseRequested", false)
    val status = optJSONObject("status")?.toStatusOrNull()?.let { status ->
        if (pauseRequested && status == DownloadStatus.Queued) {
            DownloadStatus.Paused(DownloadPauseReason.USER, optJSONObject("status")?.optLong("bytes") ?: 0L)
        } else {
            status
        }
    }
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
            recovered = optBoolean("recovered", false),
            subtitlesHidden = if (has("subtitlesHidden")) optBoolean("subtitlesHidden") else null,
            startFromSeconds = optDouble("startFrom", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            durationSeconds = optDouble("duration", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
            pauseRequested = pauseRequested,
            subtitles = optJSONArray("subtitles").toDownloadSubtitles(),
            directPlayChosen = optBoolean("directPlayChosen", false),
            directPlayUnanswered = optBoolean("directPlayUnanswered", false),
        )
    }
}

private fun JSONObject.toStatusOrNull(): DownloadStatus? =
    when (optString("kind")) {
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
