package io.putdotio.android.files

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.putdotio.android.isNotFound
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.sdk.files.PutioFileType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A video started on this device and not yet finished, as a Continue watching row shows it. */
public data class ContinueWatchingEntry(
    val fileId: FilesItemId,
    val name: String,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val watchedAt: Long,
) {
    public val fraction: Float
        get() = (positionSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()

    public val remainingSeconds: Long
        get() = (durationSeconds - positionSeconds).coerceAtLeast(0.0).toLong()

    /** Enough of a Files item to open the player, which offers to resume from the saved position. */
    public fun toFilesItem(): FilesItem =
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
public interface ContinueWatchingStore {
    public fun read(): List<ContinueWatchingEntry>

    public fun write(entries: List<ContinueWatchingEntry>)
}

/**
 * put.io keeps a position per file but has no list of started videos, so the row comes from videos
 * played on this device, on the phone and on TV alike. It records a video once the player has
 * resolved its name and duration and put.io has accepted a position for it, newest first, at most
 * [MAX_ENTRIES]. Finished, unwatched and deleted videos leave. Main-thread only.
 */
public class ContinueWatching(
    private val store: ContinueWatchingStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Media(val name: String, val durationSeconds: Double)

    /** Videos the player resolved this session, for positions put.io accepts later. */
    private val media = mutableMapOf<FilesItemId, Media>()
    private val mutableEntries = MutableStateFlow(store.read())
    private val handledDeletes = mutableSetOf<FilesRequestId>()
    private val recheckRequests = MutableStateFlow(0)
    private var rechecksDone = 0
    private var reconciled = false
    private var closed = false

    public val entries: StateFlow<List<ContinueWatchingEntry>> = mutableEntries.asStateFlow()

    /** The player resolved a video's name and duration. */
    public fun opened(fileId: FilesItemId, name: String, durationSeconds: Double?) {
        val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return
        media[fileId] = Media(name, duration)
        val current = mutableEntries.value
        if (current.any { it.fileId == fileId }) {
            update(current.map { if (it.fileId == fileId) it.copy(name = name, durationSeconds = duration) else it })
        }
    }

    /** put.io accepted [seconds] for [fileId], from playback or the offline sync. */
    public fun positionSaved(fileId: FilesItemId, seconds: Double) {
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
     * failures keep the entry. A 401 stops the pass and is returned for the session to reject.
     */
    public suspend fun reconcile(
        resolve: suspend (FilesItemId) -> PutioResult<FilesItem>,
    ): PutioFailure.AuthenticationRequired? {
        if (reconciled || closed) return null
        val rejected = readEntries(resolve)
        // Set only once the pass ends, so a pass cancelled with its screen runs again.
        reconciled = true
        return rejected
    }

    /** put.io confirmed the file gone, deleted or moved to Trash from this device. */
    public fun removed(fileId: FilesItemId) {
        // Forget the media too, so a save answered after the delete can't bring the card back.
        media.remove(fileId)
        update(mutableEntries.value.filterNot { it.fileId == fileId })
    }

    /**
     * Deletes Files confirmed, each handled once per session however often the screen replays it.
     * An entry deleted leaves at once. Any other item may be a folder holding entries, which only a
     * fresh read of each entry finds: [recheckAfterDeletes] runs it.
     */
    public fun removed(deletes: List<FilesDeleteOutcome>) {
        val fresh = deletes.filter { handledDeletes.add(it.requestId) }.flatMap { it.confirmedGone() }
        val entryIds = mutableEntries.value.mapTo(mutableSetOf()) { it.fileId }
        fresh.forEach { removed(it) }
        if (fresh.any { it !in entryIds }) recheckRequests.value += 1
    }

    /**
     * Reads every entry again, as [reconcile] does, each time [removed] asks; asks that arrive
     * during a pass share the next one. A pass cancelled with its screen runs again. Returns a 401
     * for the session to reject.
     */
    public suspend fun recheckAfterDeletes(
        resolve: suspend (FilesItemId) -> PutioResult<FilesItem>,
    ): PutioFailure.AuthenticationRequired? {
        while (true) {
            val asked = recheckRequests.first { it > rechecksDone }
            val rejected = if (closed) null else readEntries(resolve)
            rechecksDone = asked
            if (rejected != null) return rejected
        }
    }

    private suspend fun readEntries(
        resolve: suspend (FilesItemId) -> PutioResult<FilesItem>,
    ): PutioFailure.AuthenticationRequired? {
        for (read in mutableEntries.value) {
            val result = resolve(read.fileId)
            val failure = (result as? PutioResult.Failure)?.failure
            // A session already closed answers for nobody, so its 401 rejects nothing.
            val rejected = (failure as? PutioFailure.AuthenticationRequired)?.takeUnless { closed }
            if (rejected != null) return rejected
            when {
                closed -> Unit
                // A position saved while the read was out is newer than what it brought back.
                result is PutioResult.Success ->
                    if (mutableEntries.value.any { it == read }) refresh(read, result.value)
                // Gone whatever was saved meanwhile; as in removed(fileId), a later save can't bring it back.
                failure?.isNotFound == true -> removed(read.fileId)
            }
        }
        return null
    }

    /** The session ended; nothing it still has in flight writes the row again. */
    public fun close() {
        closed = true
    }

    private fun refresh(entry: ContinueWatchingEntry, item: FilesItem) {
        val current = mutableEntries.value
        val fileId = entry.fileId
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
        if (closed || kept == mutableEntries.value) return
        mutableEntries.value = kept
        store.write(kept)
    }

    public companion object {
        public const val MAX_ENTRIES: Int = 10
    }
}

/** One JSON document per user in private SharedPreferences; sign-out clears every user's. */
public class DeviceContinueWatchingStore internal constructor(
    private val preferences: SharedPreferences,
    private val key: String,
) : ContinueWatchingStore {
    public constructor(context: Context, userId: Long) : this(preferences(context), "user-$userId")

    override fun read(): List<ContinueWatchingEntry> =
        preferences.getString(key, null)?.let { raw ->
            try {
                JSONArray(raw).toEntries()
            } catch (_: JSONException) {
                null
            }
        }.orEmpty()

    /** The wipe this store was made after; a later one retires it. */
    private val generation = wipes.get()

    override fun write(entries: List<ContinueWatchingEntry>) {
        // A session being torn down can still finish a read after sign-out wiped the row.
        if (wipes.get() != generation) return
        preferences.edit { putString(key, entries.toJson().toString()) }
    }

    public companion object {
        private const val PREFERENCES_NAME = "io.putdotio.android.files.continue-watching"
        private val wipes = AtomicInteger()

        private fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        public fun clearAll(context: Context) {
            wipes.incrementAndGet()
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

private const val COMPLETED_FRACTION = 0.95
private const val FINISHED_WITHIN_SECONDS = 10.0

/** The items a settled delete's read-back found gone, a partial delete's included. */
public fun FilesDeleteOutcome.confirmedGone(): List<FilesItemId> = when (status) {
    FilesDeleteStatus.NO_LONGER_AVAILABLE,
    FilesDeleteStatus.STILL_PRESENT,
    FilesDeleteStatus.SKIPPED,
    FilesDeleteStatus.TOO_LARGE_FOR_TRASH,
    -> intent.itemIds - remainingIds
    FilesDeleteStatus.CHECKING, FilesDeleteStatus.UNKNOWN -> emptyList()
}
