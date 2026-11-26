package io.putdotio.android.files

import androidx.annotation.StringRes
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.auth.openStoreListing
import io.putdotio.android.apiReason
import io.putdotio.android.mobileUnknownMessage
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.trash.undoTrashMove
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

internal class MobileFilesNotice(
    val message: Int,
    val name: String,
    val failure: PutioFailure? = null,
    val count: Int? = null,
    val action: (() -> Unit)? = null,
    @param:StringRes val actionLabel: Int = R.string.mobile_action_retry,
) {
    fun text(context: Context): String {
        val reason = failure?.let {
            it.apiReason ?: it.mobileUnknownMessage(context, context.getString(it.mobileMessageResource()))
        }.orEmpty()
        val text = if (count == null) {
            context.getString(message, name, reason)
        } else {
            context.resources.getQuantityString(message, count, count, reason)
        }
        return text.trim()
    }
}

internal class MobileFilesActionSources(
    val watched: FilesWatchedRepository,
    val streamUrls: FilesStreamUrls,
    val trash: TrashRepository,
    val positionWrites: suspend (List<FilesItemId>, suspend () -> PutioResult<Unit>) -> PutioResult<Unit> =
        { _, write -> write() },
)

internal class MobileFilesActionsController(
    private val scope: CoroutineScope,
    private val sources: MobileFilesActionSources,
    private val onFilesEvent: (FilesBrowserEvent) -> Boolean,
    private val onPositionSaved: (FilesItemId, Double) -> Unit,
    private val onAuthenticationRequired: suspend () -> Unit,
    private val handoff: MobileVlcHandoff,
) {
    private val watchedJobs = mutableMapOf<FilesItemId, Deferred<PutioResult<Unit>>>()
    private val watchedPermits = Semaphore(WATCHED_CONCURRENCY)
    private var vlcHandoff: Job? = null
    private val mutableNotices = MutableSharedFlow<MobileFilesNotice>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val notices: SharedFlow<MobileFilesNotice> = mutableNotices.asSharedFlow()

    fun itemActions(watchedToggle: Boolean, vlcInstalled: Boolean): MobileFilesItemActions =
        MobileFilesItemActions(
            onOpenInVlc = if (vlcInstalled) { item -> openInVlc(item.id, item.name) } else null,
            onSetWatched = if (watchedToggle) { items, watched -> setWatched(items, watched) } else null,
            onUndoTrash = ::undoTrash,
            notices = notices,
            onLinkCopied = { mutableNotices.tryEmit(MobileFilesNotice(R.string.mobile_public_links_link_copied, "")) },
        )

    fun setWatched(item: FilesItem, watched: Boolean) = setWatched(listOf(item), watched)

    fun setWatched(items: List<FilesItem>, watched: Boolean) {
        val targets = if (watched) items else items.distinctBy { it.id }
        if (!watched && targets.size > 1) {
            val write = writeBulkUnwatched(targets)
            scope.launch {
                val failure = (write.await() as? PutioResult.Failure)?.failure ?: return@launch
                report(
                    watchedRefusal(false, targets.first(), failure, targets.size) {
                        setWatched(targets, false)
                    },
                )
            }
            return
        }
        val writes = targets.mapNotNull { item -> writeWatched(item, watched)?.let { item to it } }
        if (writes.isEmpty()) return
        writes.forEach { (_, write) ->
            scope.launch {
                val failure = (write.await() as? PutioResult.Failure)?.failure
                if (failure is PutioFailure.AuthenticationRequired) onAuthenticationRequired()
            }
        }
        scope.launch {
            val refused = writes.mapNotNull { (item, write) ->
                (write.await() as? PutioResult.Failure)?.failure
                    ?.takeUnless { it is PutioFailure.AuthenticationRequired }
                    ?.let { item to it }
            }
            val (item, failure) = refused.firstOrNull() ?: return@launch
            val failed = refused.map { it.first }
            val retry = { setWatched(failed, watched) }.takeIf { writes.size > 1 }
            report(watchedRefusal(watched, item, failure, refused.size, retry))
        }
    }

    private fun writeBulkUnwatched(items: List<FilesItem>): Deferred<PutioResult<Unit>> {
        val ids = items.map { it.id }
        val orderedIds = ids.sortedBy(FilesItemId::value)
        val previous = ids.mapNotNull { watchedJobs[it] }.distinct()
        val write = scope.async(start = CoroutineStart.LAZY) {
            previous.forEach { it.join() }
            val result = watchedPermits.withPermit {
                sources.positionWrites(orderedIds) {
                    sources.watched.clearPositions(ids)
                }
            }
            ensureActive()
            val current = coroutineContext[Job]
            ids.forEach { id ->
                if (watchedJobs[id] === current) watchedJobs.remove(id)
            }
            if (result is PutioResult.Success) {
                ids.forEach { id ->
                    onFilesEvent(FilesBrowserEvent.PlaybackPositionReported(id, 0.0))
                    onPositionSaved(id, 0.0)
                }
            }
            result
        }
        ids.forEach { id -> watchedJobs[id] = write }
        write.start()
        return write
    }

    private fun writeWatched(item: FilesItem, watched: Boolean): Deferred<PutioResult<Unit>>? {
        val seconds = if (watched) item.playback?.durationSeconds ?: return null else 0.0
        val previous = watchedJobs[item.id]
        val write = scope.async(start = CoroutineStart.LAZY) {
            previous?.join()
            val result = watchedPermits.withPermit {
                sources.positionWrites(listOf(item.id)) {
                    if (watched) {
                        sources.watched.setPosition(item.id, seconds)
                    } else {
                        sources.watched.clearPosition(item.id)
                    }
                }
            }
            ensureActive()
            if (watchedJobs[item.id] === coroutineContext[Job]) watchedJobs.remove(item.id)
            if (result is PutioResult.Success) {
                onFilesEvent(FilesBrowserEvent.PlaybackPositionReported(item.id, seconds))
                onPositionSaved(item.id, seconds)
            }
            result
        }
        watchedJobs[item.id] = write
        write.start()
        return write
    }

    fun openInVlc(id: FilesItemId, name: String) {
        if (id.value <= 0L || vlcHandoff?.isActive == true) return
        vlcHandoff = scope.launch {
            val stream = sources.streamUrls.originalStreamUrl(id)
            ensureActive()
            when (stream) {
                is FilesStreamUrlResult.Ready -> if (!handoff.open(stream.url, name)) {
                    mutableNotices.tryEmit(
                        MobileFilesNotice(
                            R.string.mobile_files_vlc_missing,
                            name,
                            action = handoff::install,
                            actionLabel = R.string.mobile_files_get_vlc,
                        ),
                    )
                }
                FilesStreamUrlResult.DownloadTokenUnavailable ->
                    mutableNotices.tryEmit(MobileFilesNotice(R.string.mobile_files_vlc_unavailable, name))
                is FilesStreamUrlResult.Failure ->
                    report(MobileFilesNotice(R.string.mobile_files_vlc_failed, name, stream.failure))
            }
        }
    }

    fun undoTrash(outcome: FilesDeleteOutcome) {
        if (outcome.intent.mode != FilesDeleteMode.TRASH) return
        val itemIds = outcome.intent.itemIds
        scope.launch {
            val result = sources.trash.undoTrashMove(itemIds)
            ensureActive()
            when (result) {
                is PutioResult.Success -> {
                    if (!onFilesEvent(FilesBrowserEvent.TrashUndone(outcome))) {
                        onFilesEvent(FilesBrowserEvent.InvalidateAllFolders)
                    }
                    onFilesEvent(FilesBrowserEvent.ReloadIfStale)
                }
                is PutioResult.Failure -> report(
                    if (itemIds.size == 1) {
                        MobileFilesNotice(R.string.mobile_files_undo_failed, outcome.itemName, result.failure)
                    } else {
                        MobileFilesNotice(R.plurals.mobile_files_undo_failed_many, "", result.failure, itemIds.size)
                    },
                )
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun report(notice: MobileFilesNotice) {
        if (notice.failure is PutioFailure.AuthenticationRequired) {
            onAuthenticationRequired()
        } else {
            mutableNotices.tryEmit(notice)
        }
    }
}

private fun watchedRefusal(
    watched: Boolean,
    item: FilesItem,
    failure: PutioFailure,
    count: Int,
    retry: (() -> Unit)?,
) = when {
    count == 1 && watched ->
        MobileFilesNotice(R.string.mobile_files_mark_watched_failed, item.name, failure, action = retry)
    count == 1 -> MobileFilesNotice(R.string.mobile_files_mark_unwatched_failed, item.name, failure, action = retry)
    watched -> MobileFilesNotice(R.plurals.mobile_files_mark_watched_failed_many, "", failure, count, retry)
    else -> MobileFilesNotice(R.plurals.mobile_files_mark_unwatched_failed_many, "", failure, count, retry)
}

internal interface MobileVlcHandoff {
    fun open(streamUrl: String, name: String): Boolean

    fun install()
}

internal fun vlcIntent(streamUrl: String, name: String): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(streamUrl.toUri(), "video/*")
        .setPackage(VLC_PACKAGE)
        .putExtra("title", name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal class MobileVlcIntentHandoff(private val context: Context) : MobileVlcHandoff {
    override fun open(streamUrl: String, name: String): Boolean =
        try {
            context.startActivity(vlcIntent(streamUrl, name))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    override fun install() {
        openStoreListing(context, VLC_PACKAGE)
    }
}

internal val FilesItem.opensInVlc: Boolean
    get() = type == PutioFileType.VIDEO && id.value > 0L

internal const val VLC_PACKAGE = "org.videolan.vlc"

internal fun Context.hasVlc(): Boolean = packageManager.getLaunchIntentForPackage(VLC_PACKAGE) != null

@Composable
internal fun rememberVlcInstalled(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateFlow.collectAsState()
    return remember(context, resumed == Lifecycle.State.RESUMED) { context.hasVlc() }
}

private const val WATCHED_CONCURRENCY = 4
