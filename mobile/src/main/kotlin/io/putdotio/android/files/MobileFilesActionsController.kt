package io.putdotio.android.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.net.toUri
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.apiReason
import io.putdotio.android.trash.TrashRepository
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** A line the Files snackbar shows: an item action that did not go through, or a copied link. */
internal class MobileFilesNotice(
    @param:StringRes val message: Int,
    val name: String,
    val failure: PutioFailure? = null,
) {
    fun text(context: Context): String {
        val reason = failure?.let { it.apiReason ?: context.getString(it.mobileMessageResource()) }
        return context.getString(message, name, reason.orEmpty()).trim()
    }
}

/** The put.io reads and writes behind the sheet's own actions. */
internal class MobileFilesActionSources(
    val watched: FilesWatchedRepository,
    val streamUrls: FilesStreamUrls,
    val trash: TrashRepository,
)

/**
 * The actions the sheet runs outside the browser's reducer: the watched toggle, Open in VLC and
 * Undo for a Trash move. It lives with the session, so a rotation doesn't cancel a request put.io
 * may already have applied; [close] cancels what still runs. Main-thread only, like the Continue
 * watching row [onPositionSaved] updates.
 */
internal class MobileFilesActionsController(
    private val scope: CoroutineScope,
    private val sources: MobileFilesActionSources,
    private val onFilesEvent: (FilesBrowserEvent) -> Boolean,
    /** put.io accepted a position from the watched toggle: Continue watching and offline positions follow. */
    private val onPositionSaved: (FilesItemId, Double) -> Unit,
    private val onAuthenticationRequired: suspend () -> Unit,
    private val handoff: MobileVlcHandoff,
) {
    private val watchedJobs = mutableMapOf<FilesItemId, Job>()
    private var vlcHandoff: Job? = null
    private val mutableNotices = MutableSharedFlow<MobileFilesNotice>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** What went wrong, for the Files snackbar; nothing is replayed to a later screen. */
    val notices: SharedFlow<MobileFilesNotice> = mutableNotices.asSharedFlow()

    fun itemActions(watchedToggle: Boolean, onSelect: ((FilesItem) -> Unit)? = null): MobileFilesItemActions =
        MobileFilesItemActions(
            onOpenInVlc = { openInVlc(it.id, it.name) },
            onSelect = onSelect,
            onSetWatched = if (watchedToggle) ::setWatched else null,
            onUndoTrash = ::undoTrash,
            notices = notices,
            onLinkCopied = { mutableNotices.tryEmit(MobileFilesNotice(R.string.mobile_public_links_link_copied, "")) },
        )

    /**
     * Watched is the file's saved position at its end; unwatched drops the position. A newer choice
     * for the same file supersedes an unfinished one, so a slow write cannot settle the row last.
     */
    fun setWatched(item: FilesItem, watched: Boolean) {
        val seconds = if (watched) item.playback?.durationSeconds ?: return else 0.0
        // A write already sent can't be called back, so a newer choice waits for it: put.io applies
        // them in order, and each settles the row in that order, so the last one put.io took shows.
        val previous = watchedJobs[item.id]
        // Started lazily so the write is registered before its body can compare itself.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            val result = if (watched) {
                sources.watched.setPosition(item.id, seconds)
            } else {
                sources.watched.clearPosition(item.id)
            }
            // A closed session settles nothing: a late 401 must not reject the session after it.
            ensureActive()
            if (watchedJobs[item.id] === coroutineContext[Job]) watchedJobs.remove(item.id)
            when (result) {
                is PutioResult.Success -> {
                    onFilesEvent(FilesBrowserEvent.PlaybackPositionReported(item.id, seconds))
                    onPositionSaved(item.id, seconds)
                }
                is PutioResult.Failure -> {
                    val message = if (watched) {
                        R.string.mobile_files_mark_watched_failed
                    } else {
                        R.string.mobile_files_mark_unwatched_failed
                    }
                    report(message, item.name, result.failure)
                }
            }
        }
        watchedJobs[item.id] = job
        job.start()
    }

    /** Hands VLC the original with the account's download token, never the session's access token. */
    fun openInVlc(id: FilesItemId, name: String) {
        // One handoff at a time: repeated taps during the token lookup would open VLC once per tap.
        if (id.value <= 0L || vlcHandoff?.isActive == true) return
        vlcHandoff = scope.launch {
            val stream = sources.streamUrls.originalStreamUrl(id)
            ensureActive()
            when (stream) {
                is FilesStreamUrlResult.Ready -> if (!handoff.open(stream.url, name)) {
                    mutableNotices.tryEmit(MobileFilesNotice(R.string.mobile_files_vlc_missing, name))
                }
                FilesStreamUrlResult.DownloadTokenUnavailable ->
                    mutableNotices.tryEmit(MobileFilesNotice(R.string.mobile_files_vlc_unavailable, name))
                is FilesStreamUrlResult.Failure ->
                    report(R.string.mobile_files_vlc_failed, name, stream.failure)
            }
        }
    }

    /**
     * Restores a Trash move the snackbar announced. put.io queues the restore, so the folder reads
     * again now and lists the item once put.io has put it back.
     */
    fun undoTrash(outcome: FilesDeleteOutcome) {
        if (outcome.intent.mode != FilesDeleteMode.TRASH) return
        scope.launch {
            val result = sources.trash.restore(outcome.intent.itemId)
            ensureActive()
            when (result) {
                is PutioResult.Success -> {
                    // Another delete may have replaced the outcome meanwhile; the item still comes back somewhere.
                    if (!onFilesEvent(FilesBrowserEvent.TrashUndone(outcome))) {
                        onFilesEvent(FilesBrowserEvent.InvalidateAllFolders)
                    }
                    onFilesEvent(FilesBrowserEvent.ReloadIfStale)
                }
                is PutioResult.Failure -> report(R.string.mobile_files_undo_failed, outcome.itemName, result.failure)
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun report(@StringRes message: Int, name: String, failure: PutioFailure) {
        // A 401 is the session's verdict; the shell signs out instead of explaining it here.
        if (failure is PutioFailure.AuthenticationRequired) {
            onAuthenticationRequired()
        } else {
            mutableNotices.tryEmit(MobileFilesNotice(message, name, failure))
        }
    }
}

/** Opens a stream in VLC; false when VLC is not installed. */
internal fun interface MobileVlcHandoff {
    fun open(streamUrl: String, name: String): Boolean
}

/** VLC by package, with the file's name as its title; the URL goes only into the intent, never a log. */
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
}

/** Open in VLC reads the original video, which put.io serves to any player. */
internal val FilesItem.opensInVlc: Boolean
    get() = type == PutioFileType.VIDEO && id.value > 0L

internal const val VLC_PACKAGE = "org.videolan.vlc"
