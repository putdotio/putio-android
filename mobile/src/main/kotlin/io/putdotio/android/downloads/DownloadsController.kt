package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import java.io.Closeable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One controller per signed-in user. Rows come from the store, which the engine
 * updates as transfers progress; this class turns UI intents into store writes
 * and engine calls without holding any URL or credential.
 */
// One handler per family of DownloadsEvent, each of which writes the store and calls the engine.
@Suppress("TooManyFunctions")
internal class DownloadsController(
    private val store: DownloadStore,
    private val engine: DownloadEngine,
    parentScope: CoroutineScope,
    private val progressInterval: Duration = 1.seconds,
    /** A monotonic clock for speed intervals, which a wall-clock change must not bend. */
    private val elapsedMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val mutableState = MutableStateFlow(
        DownloadsState(concurrency = engine.concurrency).withEntries(store.entries.value),
    )
    private val mutations = Mutex()
    private var closed = false
    private val speeds = SpeedWatch()

    // Speed is measured on progress ticks alone: a stalled transfer, whose unchanged bytes the store
    // does not re-emit, falls to no speed, and another row's change never moves a baseline.
    private val progress = ProgressWatch(scope, engine, progressInterval) { sampleSpeeds(store.entries.value) }

    val state: StateFlow<DownloadsState> = mutableState.asStateFlow()

    init {
        scope.launch {
            store.entries.collect { entries ->
                val running = entries.filter { it.status is DownloadStatus.Downloading }
                    .mapTo(mutableSetOf()) { it.fileId }
                synchronized(lock) {
                    val next = mutableState.value.withEntries(entries)
                    mutableState.value = next.copy(speeds = next.speeds.filterKeys { it in running })
                }
            }
        }
    }

    private fun sampleSpeeds(entries: List<DownloadEntry>) {
        synchronized(lock) {
            mutableState.value = mutableState.value.copy(speeds = speeds.sample(entries, elapsedMillis()))
        }
    }

    fun dispatch(event: DownloadsEvent): Boolean {
        synchronized(lock) {
            if (closed) return false
        }
        return when (event) {
            is DownloadsEvent.Start -> start(event.request)
            is DownloadsEvent.Retry -> retry(event)
            is DownloadsEvent.RequestRemoval -> requestRemoval(event)
            DownloadsEvent.CancelRemoval -> update { it.copy(removal = null) }
            DownloadsEvent.ConfirmRemoval, is DownloadsEvent.Cancel ->
                removeNow(cancelling = (event as? DownloadsEvent.Cancel)?.fileId)
            is DownloadsEvent.Pause, is DownloadsEvent.Resume -> setPaused(event)
            is DownloadsEvent.SetConcurrency -> setConcurrency(event.limit)
            // Files and Downloads stop offering a row whose bytes are gone; Download again fetches them.
            is DownloadsEvent.LocalCopyMissing -> {
                enqueue {
                    store.find(event.fileId)?.takeIf { it.isCompleted }
                        ?.let { store.upsert(it.copy(status = DownloadStatus.Missing)) }
                }
                true
            }
            is DownloadsEvent.Focus, DownloadsEvent.FocusHandled ->
                update { it.copy(focus = (event as? DownloadsEvent.Focus)?.fileId) }
            DownloadsEvent.Shown, DownloadsEvent.Hidden -> progress.watch(event == DownloadsEvent.Shown)
        }
    }

    private fun start(request: DownloadRequest): Boolean {
        val artifact = request.type.downloadArtifact()
        if (artifact == null || !accepts(request.fileId, retry = false)) return false
        val entry = DownloadEntry(
            fileId = request.fileId,
            name = request.name,
            type = request.type,
            artifact = artifact,
            status = DownloadStatus.Queued,
            createdAt = clock(),
            subtitlesHidden = request.subtitlesHidden,
            startFromSeconds = request.startFromSeconds,
            durationSeconds = request.durationSeconds,
        )
        enqueue {
            // Re-check under the mutation lock: a removal may have landed since dispatch.
            if (!accepts(entry.fileId, retry = false)) return@enqueue
            store.upsert(entry)
            engine.start(entry)
        }
        return true
    }

    // Media3 queues a re-added failed or finished request behind everything already waiting.
    private fun retry(event: DownloadsEvent.Retry): Boolean {
        if (!accepts(event.fileId, retry = true)) return false
        enqueue {
            val entry = store.find(event.fileId)?.takeIf { accepts(it.fileId, retry = true) } ?: return@enqueue
            // A pause that raced the failure must not hold the retried request.
            val queued = entry.copy(status = DownloadStatus.Queued, queuedAt = clock(), pauseRequested = false)
            store.upsert(queued)
            engine.start(queued)
        }
        return true
    }

    /** A row being deleted takes nothing; otherwise a start needs no live or finished row, a retry a stuck one. */
    private fun accepts(fileId: FilesItemId, retry: Boolean): Boolean {
        if (engine.isRemoving(fileId) || synchronized(lock) { fileId in mutableState.value.removing }) return false
        val entry = store.find(fileId)
        return if (retry) entry?.canRetry == true else entry?.let { it.isActive || it.isCompleted } != true
    }

    private fun requestRemoval(event: DownloadsEvent.RequestRemoval): Boolean {
        val removing = synchronized(lock) { mutableState.value.removing }
        val entries = event.fileIds.mapNotNull { store.find(it) }.filterNot { it.fileId in removing }
        if (entries.isEmpty()) return false
        val removal = DownloadRemoval(entries.mapTo(mutableSetOf()) { it.fileId }, entries.singleOrNull()?.name)
        return update { it.copy(removal = removal) }
    }

    /**
     * A confirmed removal, or [cancelling] a queued row, which asks nothing since the row holds
     * nothing playable. Each row is marked before the engine deletes its bytes, so a process death
     * in between finishes the removal on the next start. Only local copies are touched: the
     * controller has no way to reach the put.io originals.
     */
    private fun removeNow(cancelling: FilesItemId?): Boolean {
        val fileIds = synchronized(lock) {
            val current = mutableState.value
            val chosen = if (cancelling == null) {
                current.removal?.fileIds
            } else {
                setOf(cancelling).takeIf {
                    store.find(cancelling)?.status == DownloadStatus.Queued &&
                        cancelling !in current.removing && !engine.isRemoving(cancelling)
                }
            } ?: return false
            val removal = if (cancelling == null) null else current.removal
            mutableState.value = current.copy(removal = removal, removing = current.removing + chosen)
            chosen
        }
        enqueue {
            for (fileId in fileIds) {
                store.find(fileId)?.let { store.upsert(it.copy(removing = true)) }
                engine.remove(fileId)
            }
        }
        return true
    }

    /** Pause holds the rows that can pause, Resume releases the viewer's paused ones; deleting rows take neither. */
    private fun setPaused(event: DownloadsEvent): Boolean {
        val paused = event is DownloadsEvent.Pause
        val fileIds = (event as? DownloadsEvent.Pause)?.fileIds ?: (event as? DownloadsEvent.Resume)?.fileIds.orEmpty()
        fun allowed(fileId: FilesItemId): Boolean =
            store.find(fileId)?.let { if (paused) it.canPause else it.isPausedByUser } == true &&
                !engine.isRemoving(fileId) && synchronized(lock) { fileId !in mutableState.value.removing }
        if (fileIds.none(::allowed)) return false
        enqueue {
            // Re-check under the mutation lock: the row may have moved on since dispatch.
            for (fileId in fileIds) if (allowed(fileId)) engine.setPaused(fileId, paused)
        }
        return true
    }

    private fun setConcurrency(limit: Int): Boolean {
        val changed = limit in DOWNLOAD_CONCURRENCY_CHOICES && update { it.copy(concurrency = limit) }
        if (changed) enqueue { engine.setConcurrency(limit) }
        return changed
    }

    /** Store writes and engine calls run one at a time, in dispatch order. */
    private fun enqueue(block: suspend () -> Unit) {
        scope.launch { mutations.withLock { block() } }
    }

    private fun update(transform: (DownloadsState) -> DownloadsState): Boolean {
        synchronized(lock) {
            val next = transform(mutableState.value)
            if (next == mutableState.value) return false
            mutableState.value = next
        }
        return true
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        scope.cancel()
    }
}

/** Progress is display-only, so it polls outside the mutation queue and dies with the controller scope. */
private class ProgressWatch(
    private val scope: CoroutineScope,
    private val engine: DownloadEngine,
    private val interval: Duration,
    private val onTick: () -> Unit,
) {
    private var job: Job? = null

    @Synchronized
    fun watch(visible: Boolean): Boolean {
        if (visible == (job != null)) return false
        job = if (visible) {
            scope.launch {
                while (true) {
                    engine.refreshProgress()
                    onTick()
                    delay(interval)
                }
            }
        } else {
            job?.cancel()
            null
        }
        return true
    }
}

/**
 * Each running row's speed over the bytes the last two progress reports carried. Reports arrive
 * about once a second while the screen watches; a gap longer than [MAX_WINDOW_MILLIS], such as the
 * time the screen was hidden, starts over rather than average over it.
 */
private class SpeedWatch {
    private class Sample(val bytes: Long, val atMillis: Long)

    private val samples = mutableMapOf<FilesItemId, Sample>()
    private var speeds = emptyMap<FilesItemId, Long>()

    fun sample(entries: List<DownloadEntry>, nowMillis: Long): Map<FilesItemId, Long> {
        val running = entries.mapNotNull { entry ->
            (entry.status as? DownloadStatus.Downloading)?.let { entry.fileId to it.bytesDownloaded }
        }.toMap()
        samples.keys.retainAll(running.keys)
        val next = mutableMapOf<FilesItemId, Long>()
        for ((fileId, bytes) in running) {
            val last = samples[fileId]
            if (last == null || bytes < last.bytes || nowMillis - last.atMillis > MAX_WINDOW_MILLIS) {
                samples[fileId] = Sample(bytes, nowMillis)
                continue
            }
            val elapsed = nowMillis - last.atMillis
            if (elapsed >= MIN_WINDOW_MILLIS) {
                next[fileId] = (bytes - last.bytes) * MILLIS_PER_SECOND / elapsed
                samples[fileId] = Sample(bytes, nowMillis)
            } else {
                speeds[fileId]?.let { next[fileId] = it }
            }
        }
        speeds = next
        return next
    }

    private companion object {
        const val MIN_WINDOW_MILLIS = 500L
        const val MAX_WINDOW_MILLIS = 5_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

private const val NANOS_PER_MILLI = 1_000_000L

/** Video downloads the HLS rendition the player streams; audio has only the original. */
private fun PutioFileType.downloadArtifact(): DownloadArtifact? =
    when (this) {
        PutioFileType.VIDEO -> DownloadArtifact.HLS
        PutioFileType.AUDIO -> DownloadArtifact.ORIGINAL
        else -> null
    }
