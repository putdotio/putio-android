package io.putdotio.android.downloads

import android.content.SharedPreferences
import androidx.core.content.edit
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.retryable
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** put.io's saved-position calls; [io.putdotio.android.playback.SdkPlaybackPositionRepository] in the app. */
internal interface PositionRemote {
    suspend fun read(fileId: Long): PlaybackRepositoryResult<Double>

    suspend fun write(fileId: Long, seconds: Double): PlaybackRepositoryResult<Unit>

    suspend fun resumeEnabled(): PlaybackRepositoryResult<Boolean>
}

/** A position this device played that put.io has not confirmed yet. */
internal data class PendingPosition(
    val fileId: Long,
    val seconds: Double,
    /** The server position this device last knew when the first unsent position was saved. */
    val expectedRemote: Double?,
    /** A write already sent for this file, which may have landed without a reply. */
    val attempted: Double?,
    val revision: Long,
)

/**
 * One user's saved positions for downloaded files, in the downloads preferences file. A known
 * position is the last one put.io confirmed or reported; a pending one is what this device
 * played while put.io could not take it. Only files offline playback has opened are tracked.
 */
// Each function is one sync transition over a single snapshot under one lock; splitting them
// across classes would share that snapshot between them.
@Suppress("TooManyFunctions")
internal class OfflinePositionStore(private val preferences: SharedPreferences, userId: Long) {
    private val key = "positions-$userId"
    private var snapshot = read()

    /** `use_start_from` as this device last confirmed it; offline playback falls back to it. */
    @get:Synchronized
    val resumeSetting: Boolean? get() = snapshot.resume

    @Synchronized
    fun rememberResumeSetting(enabled: Boolean) = write(snapshot.copy(resume = enabled))

    @Synchronized
    fun tracks(fileId: Long): Boolean = fileId in snapshot.known || fileId in snapshot.pending

    @Synchronized
    fun known(fileId: Long): Double? = snapshot.known[fileId]

    @Synchronized
    fun pending(fileId: Long): PendingPosition? = snapshot.pending[fileId]

    @Synchronized
    fun pending(): List<PendingPosition> = snapshot.pending.values.sortedBy { it.revision }

    /** put.io reported or accepted [seconds]; an unsent local position stays pending. */
    @Synchronized
    fun remote(fileId: Long, seconds: Double) =
        write(snapshot.copy(known = snapshot.known + (fileId to seconds)))

    /** put.io took this device's newest position; nothing is left to send. */
    @Synchronized
    fun accepted(fileId: Long, seconds: Double) =
        write(snapshot.copy(known = snapshot.known + (fileId to seconds), pending = snapshot.pending - fileId))

    /** put.io could not take [seconds]; it waits for the next sync. */
    @Synchronized
    fun keep(fileId: Long, seconds: Double) {
        val previous = snapshot.pending[fileId]
        val next = PendingPosition(
            fileId = fileId,
            seconds = seconds,
            expectedRemote = previous?.expectedRemote ?: snapshot.known[fileId],
            attempted = previous?.attempted,
            revision = snapshot.nextRevision,
        )
        write(
            snapshot.copy(pending = snapshot.pending + (fileId to next), nextRevision = snapshot.nextRevision + 1),
        )
    }

    /** put.io now holds [remote] for [update]. True when a newer local position still waits. */
    @Synchronized
    fun synced(update: PendingPosition, remote: Double): Boolean {
        val current = snapshot.pending[update.fileId]
        val known = snapshot.known + (update.fileId to remote)
        if (current == null || current.revision == update.revision) {
            write(snapshot.copy(known = known, pending = snapshot.pending - update.fileId))
            return false
        }
        write(snapshot.copy(known = known, pending = snapshot.pending + (update.fileId to current.copy(
            expectedRemote = remote,
            attempted = null,
        ))))
        return true
    }

    @Synchronized
    fun attempted(update: PendingPosition) = replaceIfCurrent(update) { it.copy(attempted = update.seconds) }

    /** An earlier write of this device landed; compare later positions against it. */
    @Synchronized
    fun rebase(update: PendingPosition, remote: Double) =
        replaceIfCurrent(update) { it.copy(expectedRemote = remote, attempted = null) }

    /** Another device saved [remote] since this one went offline: theirs is newer, this one is dropped. */
    @Synchronized
    fun yieldTo(update: PendingPosition, remote: Double) {
        if (snapshot.pending[update.fileId]?.revision != update.revision) return
        val known = snapshot.known + (update.fileId to remote)
        write(snapshot.copy(known = known, pending = snapshot.pending - update.fileId))
    }

    /** Resume is off for the account: nothing this device played may reach put.io. */
    @Synchronized
    fun dropPending() = write(snapshot.copy(pending = emptyMap()))

    private fun replaceIfCurrent(update: PendingPosition, transform: (PendingPosition) -> PendingPosition) {
        val current = snapshot.pending[update.fileId]?.takeIf { it.revision == update.revision } ?: return
        write(snapshot.copy(pending = snapshot.pending + (update.fileId to transform(current))))
    }

    private fun write(next: Snapshot) {
        snapshot = next
        preferences.edit { putString(key, next.toJson().toString()) }
    }

    private fun read(): Snapshot =
        preferences.getString(key, null)?.let { raw -> runCatching { JSONObject(raw).toSnapshot() }.getOrNull() }
            ?: Snapshot()

    private data class Snapshot(
        val resume: Boolean? = null,
        val known: Map<Long, Double> = emptyMap(),
        val pending: Map<Long, PendingPosition> = emptyMap(),
        val nextRevision: Long = 1L,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("nextRevision", nextRevision)
            .put("known", JSONObject().also { json ->
                known.forEach { (id, seconds) -> json.put(id.toString(), seconds) }
            })
            .put("pending", JSONArray().also { array ->
                pending.values.forEach { update ->
                    array.put(JSONObject()
                        .put("fileId", update.fileId)
                        .put("seconds", update.seconds)
                        .put("revision", update.revision)
                        .also { json ->
                            update.expectedRemote?.let { json.put("expectedRemote", it) }
                            update.attempted?.let { json.put("attempted", it) }
                        })
                }
            })
            .also { json -> resume?.let { json.put("resume", it) } }
    }

    private fun JSONObject.toSnapshot(): Snapshot {
        val knownJson = optJSONObject("known")
        val known = knownJson?.keys()?.asSequence()?.mapNotNull { id ->
            val seconds = knownJson.optDouble(id, Double.NaN)
            id.toLongOrNull()?.takeIf { seconds.isValidPosition() }?.let { it to seconds }
        }?.toMap().orEmpty()
        val pendingJson = optJSONArray("pending") ?: JSONArray()
        val pending = (0 until pendingJson.length()).mapNotNull { index ->
            pendingJson.optJSONObject(index)?.let { json ->
                val fileId = json.optLong("fileId", -1L).takeIf { it > 0L } ?: return@let null
                val seconds = json.optDouble("seconds", Double.NaN).takeIf { it.isValidPosition() } ?: return@let null
                PendingPosition(
                    fileId = fileId,
                    seconds = seconds,
                    expectedRemote = json.optDouble("expectedRemote", Double.NaN).takeIf { it.isValidPosition() },
                    attempted = json.optDouble("attempted", Double.NaN).takeIf { it.isValidPosition() },
                    revision = json.optLong("revision"),
                )
            }
        }.associateBy { it.fileId }
        return Snapshot(
            resume = if (has("resume")) optBoolean("resume") else null,
            known = known,
            pending = pending,
            nextRevision = optLong("nextRevision", 1L)
                .coerceAtLeast((pending.values.maxOfOrNull { it.revision } ?: 0L) + 1L),
        )
    }
}

/**
 * Saved positions for downloaded files, kept on the device first so offline playback resumes and
 * its progress reaches put.io once it answers again. The sync follows iOS main's
 * `OfflineVideoPlaybackPositionSynchronizer`: a position another device saved since this one
 * went offline wins; otherwise this device's newest position is written. A pass runs for the
 * signed-in user only, stops when that changes, and drops pending positions if the account has
 * resume turned off. Application-owned: playback reports and the sync outlive any screen.
 */
internal class OfflinePlaybackPositions(
    private val preferences: SharedPreferences,
    private val signedInUser: () -> Long?,
    private val remote: PositionRemote,
    private val scope: CoroutineScope,
    private val onSynced: (fileId: Long, seconds: Double) -> Unit = { _, _ -> },
) {
    private val stores = mutableMapOf<Long, OfflinePositionStore>()
    private val passes = Mutex()

    @Volatile
    private var passRequested = false

    @Synchronized
    fun store(userId: Long): OfflinePositionStore =
        stores.getOrPut(userId) { OfflinePositionStore(preferences, userId) }

    /**
     * After a reporting write for a downloaded file: an accepted position becomes the known one,
     * and one put.io could not take for a transient reason waits for the next sync.
     */
    fun afterWrite(userId: Long, fileId: Long, seconds: Double, result: PlaybackRepositoryResult<Unit>) {
        val store = store(userId)
        if (!store.tracks(fileId)) return
        when (result) {
            is PlaybackRepositoryResult.Success -> {
                store.accepted(fileId, seconds)
                requestSync()
            }
            is PlaybackRepositoryResult.Failure -> if (result.failure.retryable) store.keep(fileId, seconds)
        }
    }

    /**
     * Where offline playback of [entry] starts: a position not yet sent, else what put.io reports
     * within [REFRESH_TIMEOUT_MILLIS], else the last known one, else the listing's at download time.
     */
    suspend fun resumePosition(userId: Long, entry: DownloadEntry): Double {
        val store = store(userId)
        val fileId = entry.fileId.value
        val pending = store.pending(fileId)
        if (pending != null) requestSync()
        return pending?.seconds ?: when (val read = withTimeoutOrNull(REFRESH_TIMEOUT_MILLIS) { remote.read(fileId) }) {
            is PlaybackRepositoryResult.Success -> read.value.also { store.remote(fileId, it) }
            else -> store.known(fileId) ?: entry.startFromSeconds.also { store.remote(fileId, it) }
        }
    }

    /** Starts a pass unless one is running; a request during a pass runs one more after it. */
    fun requestSync() {
        passRequested = true
        scope.launch {
            if (!passes.tryLock()) return@launch
            try {
                while (passRequested) {
                    passRequested = false
                    signedInUser()?.let { sync(it) }
                }
            } finally {
                passes.unlock()
            }
            // A request that landed between the last check and the unlock.
            if (passRequested) requestSync()
        }
    }

    internal suspend fun syncNow(userId: Long) = passes.withLock { sync(userId) }

    private suspend fun sync(userId: Long) {
        val store = store(userId)
        if (store.pending().isEmpty() || signedInUser() != userId || !resumeStillOn(store)) return
        for (update in store.pending()) {
            if (signedInUser() != userId) break
            // A newer local position replacing this one since the pass began goes in the next pass.
            val current = store.pending(update.fileId)?.takeIf { it.revision == update.revision }
            val read = current?.let { remote.read(it.fileId) as? PlaybackRepositoryResult.Success }
            if (current != null && read != null) syncOne(store, current, read.value)
        }
    }

    /** Resume turned off on the account: nothing this device played may reach put.io. */
    private suspend fun resumeStillOn(store: OfflinePositionStore): Boolean {
        val setting = (remote.resumeEnabled() as? PlaybackRepositoryResult.Success)?.value ?: return false
        store.rememberResumeSetting(setting)
        if (!setting) store.dropPending()
        return setting
    }

    private suspend fun syncOne(store: OfflinePositionStore, update: PendingPosition, current: Double) {
        val attempted = update.attempted
        val expected = update.expectedRemote
        when {
            samePosition(current, update.seconds) -> settle(store, update, current)
            // An earlier write landed without a reply; compare later positions against it.
            attempted != null && samePosition(current, attempted) -> {
                store.rebase(update, current)
                passRequested = true
            }
            expected != null && !samePosition(current, expected) -> store.yieldTo(update, current)
            else -> {
                store.attempted(update)
                if (remote.write(update.fileId, update.seconds) is PlaybackRepositoryResult.Success) {
                    settle(store, update, update.seconds)
                }
            }
        }
    }

    private fun settle(store: OfflinePositionStore, update: PendingPosition, seconds: Double) {
        if (store.synced(update, seconds)) passRequested = true
        onSynced(update.fileId, seconds)
    }

    private companion object {
        const val REFRESH_TIMEOUT_MILLIS = 3_000L
    }
}

// put.io may round what it stores; positions within a second are the same position.
private fun samePosition(first: Double, second: Double): Boolean = abs(first - second) < 1.0

private fun Double.isValidPosition(): Boolean = isFinite() && this >= 0.0
