package io.putdotio.android.files

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A video started on this device and not yet finished, as the Files root's Continue watching row shows it. */
internal data class ContinueWatchingEntry(
    val fileId: FilesItemId,
    val name: String,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val watchedAt: Long,
) {
    val fraction: Float
        get() = (positionSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()

    val remainingSeconds: Long
        get() = (durationSeconds - positionSeconds).coerceAtLeast(0.0).toLong()

    /** Enough of a Files item to open the player, which offers to resume from the saved position. */
    fun toFilesItem(): FilesItem =
        FilesItem(
            id = fileId,
            parentId = null,
            name = name,
            type = PutioFileType.VIDEO,
            sizeBytes = 0L,
            createdAt = "",
            playback = FilesPlaybackProgress(positionSeconds, durationSeconds),
        )
}

/**
 * A video is in progress above 0 % and below 95 % of its duration, and more than 10 s before the
 * end, the same bounds as TV's Watch Next row: the player starts a nearer position over.
 */
internal fun isInProgress(positionSeconds: Double, durationSeconds: Double?): Boolean {
    val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return false
    return positionSeconds.isFinite() && positionSeconds > 0.0 &&
        positionSeconds < duration * COMPLETED_FRACTION && positionSeconds < duration - FINISHED_WITHIN_SECONDS
}

/** Where one user's row persists between launches. */
internal interface ContinueWatchingStore {
    fun read(): List<ContinueWatchingEntry>

    fun write(entries: List<ContinueWatchingEntry>)
}

/**
 * put.io keeps a position per file but has no list of started videos, so the row comes from videos
 * played on this device. It records a video once the player has resolved its name and duration and
 * put.io has accepted a position for it, newest first, at most [MAX_ENTRIES]. Finished, unwatched
 * and deleted videos leave. Main-thread only.
 */
internal class MobileContinueWatching(
    private val store: ContinueWatchingStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Media(val name: String, val durationSeconds: Double)

    /** Videos the player resolved this session, for positions put.io accepts later. */
    private val media = mutableMapOf<FilesItemId, Media>()
    private val mutableEntries = MutableStateFlow(store.read())
    private var reconciled = false

    val entries: StateFlow<List<ContinueWatchingEntry>> = mutableEntries.asStateFlow()

    /** The player resolved a video's name and duration. */
    fun opened(fileId: FilesItemId, name: String, durationSeconds: Double?) {
        val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return
        media[fileId] = Media(name, duration)
        val current = mutableEntries.value
        if (current.any { it.fileId == fileId }) {
            update(current.map { if (it.fileId == fileId) it.copy(name = name, durationSeconds = duration) else it })
        }
    }

    /** put.io accepted [seconds] for [fileId], from playback or the offline sync. */
    fun positionSaved(fileId: FilesItemId, seconds: Double) {
        val current = mutableEntries.value
        val existing = current.firstOrNull { it.fileId == fileId }
        val known = media[fileId] ?: existing?.let { Media(it.name, it.durationSeconds) } ?: return
        val others = current.filterNot { it.fileId == fileId }
        if (isInProgress(seconds, known.durationSeconds)) {
            val entry = ContinueWatchingEntry(fileId, known.name, seconds, known.durationSeconds, clock())
            update(listOf(entry) + others)
        } else if (existing != null) {
            update(others)
        }
    }

    /**
     * Reads each entry's file again, once per session: a file put.io no longer has leaves, and a
     * position saved on another device moves the entry or, when finished, removes it. Other
     * failures keep the entry; a rejected session stops the pass.
     */
    suspend fun reconcile(resolve: suspend (FilesItemId) -> PutioResult<FilesItem>) {
        if (reconciled) return
        for (entry in mutableEntries.value) {
            when (val result = resolve(entry.fileId)) {
                is PutioResult.Success -> refresh(entry.fileId, result.value)
                is PutioResult.Failure -> when {
                    result.failure is PutioFailure.AuthenticationRequired -> break
                    result.failure.isNotFound -> update(mutableEntries.value.filterNot { it.fileId == entry.fileId })
                }
            }
        }
        // Set only once the pass ends, so a pass cancelled with its screen runs again.
        reconciled = true
    }

    private fun refresh(fileId: FilesItemId, item: FilesItem) {
        val current = mutableEntries.value
        val entry = current.firstOrNull { it.fileId == fileId } ?: return
        val duration = item.playback?.durationSeconds ?: entry.durationSeconds
        val position = item.playback?.startFromSeconds ?: 0.0
        update(
            if (isInProgress(position, duration)) {
                current.map {
                    if (it.fileId != fileId) {
                        it
                    } else {
                        it.copy(name = item.name, positionSeconds = position, durationSeconds = duration)
                    }
                }
            } else {
                current.filterNot { it.fileId == fileId }
            },
        )
    }

    private fun update(next: List<ContinueWatchingEntry>) {
        val kept = next.sortedByDescending { it.watchedAt }.take(MAX_ENTRIES)
        if (kept == mutableEntries.value) return
        mutableEntries.value = kept
        store.write(kept)
    }

    companion object {
        const val MAX_ENTRIES = 10
    }
}

/** One JSON document per user in private SharedPreferences; sign-out clears every user's. */
internal class MobileContinueWatchingStore internal constructor(
    private val preferences: SharedPreferences,
    private val key: String,
) : ContinueWatchingStore {
    constructor(context: Context, userId: Long) : this(preferences(context), "user-$userId")

    override fun read(): List<ContinueWatchingEntry> =
        preferences.getString(key, null)?.let { raw ->
            try {
                JSONArray(raw).toEntries()
            } catch (_: JSONException) {
                null
            }
        }.orEmpty()

    override fun write(entries: List<ContinueWatchingEntry>) {
        preferences.edit { putString(key, entries.toJson().toString()) }
    }

    companion object {
        private const val PREFERENCES_NAME = "io.putdotio.android.files.continue-watching"

        private fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        fun clearAll(context: Context) {
            preferences(context).edit { clear() }
        }
    }
}

private fun List<ContinueWatchingEntry>.toJson(): JSONArray =
    JSONArray().also { array ->
        forEach {
            array.put(
                JSONObject()
                    .put("id", it.fileId.value)
                    .put("name", it.name)
                    .put("position", it.positionSeconds)
                    .put("duration", it.durationSeconds)
                    .put("watchedAt", it.watchedAt),
            )
        }
    }

// A damaged entry is dropped rather than shown with made-up progress.
private fun JSONArray.toEntries(): List<ContinueWatchingEntry> =
    (0 until length()).mapNotNull { index ->
        val entry = optJSONObject(index) ?: return@mapNotNull null
        val id = entry.optLong("id", -1L)
        val position = entry.optDouble("position", Double.NaN)
        val duration = entry.optDouble("duration", Double.NaN)
        val name = entry.optString("name")
        if (id <= 0L || name.isEmpty() || !isInProgress(position, duration)) {
            null
        } else {
            ContinueWatchingEntry(FilesItemId(id), name, position, duration, entry.optLong("watchedAt", 0L))
        }
    }

/** put.io has no such file: a 404, which a trashed file reads as too. */
private val PutioFailure.isNotFound: Boolean
    get() = this is PutioFailure.ApiRejected && statusCode == HTTP_NOT_FOUND && httpStatusCode == HTTP_NOT_FOUND

private const val HTTP_NOT_FOUND = 404
private const val COMPLETED_FRACTION = 0.95
private const val FINISHED_WITHIN_SECONDS = 10.0
