package io.putdotio.android.downloads

import io.putdotio.sdk.files.PutioFileType
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One controller per signed-in user. Rows come from the store, which the engine
 * updates as transfers progress; this class turns UI intents into store writes
 * and engine calls without holding any URL or credential.
 */
class DownloadsController(
    private val store: DownloadStore,
    private val engine: DownloadEngine,
    parentScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val lock = Any()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val mutableState = MutableStateFlow(DownloadsState().withEntries(store.entries.value))
    private var closed = false

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
            DownloadsEvent.CancelRemoval -> updateRemoval(null)
            DownloadsEvent.ConfirmRemoval -> confirmRemoval()
        }
    }

    private fun start(request: DownloadRequest): Boolean {
        val artifact = request.type.downloadArtifact()
        val blocked = engine.isRemoving(request.fileId) ||
            store.find(request.fileId)?.let { it.isActive || it.isCompleted } == true
        if (artifact == null || blocked) return false
        val entry = DownloadEntry(
            fileId = request.fileId,
            name = request.name,
            type = request.type,
            artifact = artifact,
            status = DownloadStatus.Queued,
            createdAt = clock(),
        )
        scope.launch {
            store.upsert(entry)
            engine.start(entry)
        }
        return true
    }

    private fun retry(event: DownloadsEvent.Retry): Boolean {
        val entry = store.find(event.fileId)?.takeIf { it.canRetry && !engine.isRemoving(it.fileId) } ?: return false
        scope.launch {
            store.upsert(entry.copy(status = DownloadStatus.Queued))
            engine.start(entry)
        }
        return true
    }

    private fun requestRemoval(event: DownloadsEvent.RequestRemoval): Boolean {
        val entry = store.find(event.fileId) ?: return false
        return updateRemoval(DownloadRemoval(entry.fileId, entry.name))
    }

    // The row leaves the store only once the engine reports the bytes are gone.
    private fun confirmRemoval(): Boolean {
        val pending = synchronized(lock) { mutableState.value.removal } ?: return false
        synchronized(lock) {
            val current = mutableState.value
            mutableState.value = current.copy(removal = null, removing = current.removing + pending.fileId)
        }
        engine.remove(pending.fileId)
        return true
    }

    private fun updateRemoval(removal: DownloadRemoval?): Boolean {
        synchronized(lock) {
            if (mutableState.value.removal == removal) return false
            mutableState.value = mutableState.value.copy(removal = removal)
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

/** Video downloads the HLS rendition the player streams; audio has only the original. */
fun PutioFileType.downloadArtifact(): DownloadArtifact? =
    when (this) {
        PutioFileType.VIDEO -> DownloadArtifact.HLS
        PutioFileType.AUDIO -> DownloadArtifact.ORIGINAL
        else -> null
    }
