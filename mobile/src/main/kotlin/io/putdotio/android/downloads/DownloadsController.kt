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
internal class DownloadsController(
    private val store: DownloadStore,
    private val engine: DownloadEngine,
    parentScope: CoroutineScope,
    private val progressInterval: Duration = 1.seconds,
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
    private val progress = ProgressWatch(scope, engine, progressInterval)

    val state: StateFlow<DownloadsState> = mutableState.asStateFlow()

    init {
        scope.launch {
            store.entries.collect { entries ->
                synchronized(lock) { mutableState.value = mutableState.value.withEntries(entries) }
            }
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
            DownloadsEvent.ConfirmRemoval -> confirmRemoval()
            is DownloadsEvent.SetConcurrency -> setConcurrency(event.limit)
            // Files and Downloads stop offering a row whose bytes are gone; Download again fetches them.
            is DownloadsEvent.LocalCopyMissing -> {
                enqueue {
                    store.find(event.fileId)?.takeIf { it.isCompleted }
                        ?.let { store.upsert(it.copy(status = DownloadStatus.Missing)) }
                }
                true
            }
            is DownloadsEvent.Focus -> update { it.copy(focus = event.fileId) }
            DownloadsEvent.FocusHandled -> update { it.copy(focus = null) }
            DownloadsEvent.Shown -> progress.watch(true)
            DownloadsEvent.Hidden -> progress.watch(false)
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
            val queued = entry.copy(status = DownloadStatus.Queued, queuedAt = clock())
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
     * Each row is marked before the engine deletes its bytes, so a process death in between
     * finishes the removal on the next start. Only local copies are touched: the controller
     * has no way to reach the put.io originals.
     */
    private fun confirmRemoval(): Boolean {
        val pending = synchronized(lock) {
            val current = mutableState.value
            val removal = current.removal ?: return false
            mutableState.value = current.copy(removal = null, removing = current.removing + removal.fileIds)
            removal
        }
        enqueue {
            for (fileId in pending.fileIds) {
                store.find(fileId)?.let { store.upsert(it.copy(removing = true)) }
                engine.remove(fileId)
            }
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
) {
    private var job: Job? = null

    @Synchronized
    fun watch(visible: Boolean): Boolean {
        if (visible == (job != null)) return false
        job = if (visible) {
            scope.launch {
                while (true) {
                    engine.refreshProgress()
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

/** Video downloads the HLS rendition the player streams; audio has only the original. */
private fun PutioFileType.downloadArtifact(): DownloadArtifact? =
    when (this) {
        PutioFileType.VIDEO -> DownloadArtifact.HLS
        PutioFileType.AUDIO -> DownloadArtifact.ORIGINAL
        else -> null
    }
