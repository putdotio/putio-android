package io.putdotio.android.tv

import io.putdotio.android.files.ContinueWatching
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.confirmedGone
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.SearchFilter
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.tv.files.isConfirmedTrash
import io.putdotio.android.tv.history.TvHistoryFiles
import io.putdotio.android.tv.home.TvHomePins
import io.putdotio.android.tv.watchnext.TvWatchNextPlayback
import io.putdotio.android.tv.watchnext.TvWatchNextRecorder
import io.putdotio.android.tv.watchnext.toWatchNextMedia

internal sealed interface TvItemChange {
    data class Edited(val item: FilesItem) : TvItemChange

    data class Renamed(val itemId: FilesItemId, val name: String) : TvItemChange

    data class Moved(val itemId: FilesItemId, val destinationId: FilesItemId) : TvItemChange

    data class Removed(val outcome: FilesDeleteOutcome, val byFiles: Boolean) : TvItemChange

    data class Restored(val outcome: FilesDeleteOutcome) : TvItemChange

    data class PositionSaved(val itemId: FilesItemId, val seconds: Double, val media: FilesItem? = null) :
        TvItemChange
}

internal class TvItemChangeBus {
    private val listeners = mutableListOf<(TvItemChange) -> Unit>()

    fun listen(listener: (TvItemChange) -> Unit) {
        listeners += listener
    }

    fun publish(change: TvItemChange) {
        listeners.forEach { it(change) }
    }
}

internal fun FilesBrowserController.hear(change: TvItemChange) {
    when (change) {
        is TvItemChange.Edited -> invalidateAll()
        is TvItemChange.Removed -> if (!change.byFiles) invalidateAll()
        is TvItemChange.PositionSaved ->
            dispatch(FilesBrowserEvent.PlaybackPositionReported(change.itemId, change.seconds))
        is TvItemChange.Renamed, is TvItemChange.Moved, is TvItemChange.Restored -> Unit
    }
}

private fun FilesBrowserController.invalidateAll() {
    dispatch(FilesBrowserEvent.InvalidateAllFolders)
    dispatch(FilesBrowserEvent.ReloadIfStale)
}

internal class TvSearchChanges(private val search: SearchController) : (TvItemChange) -> Unit {
    private val removals = mutableMapOf<FilesRequestId, List<SearchRemoval>>()

    override fun invoke(change: TvItemChange) {
        when (change) {
            is TvItemChange.Edited -> search.itemUpdated(change.item)
            is TvItemChange.Renamed -> result(change.itemId)?.let { search.itemUpdated(it.copy(name = change.name)) }
            is TvItemChange.Moved ->
                result(change.itemId)?.let { search.itemUpdated(it.copy(parentId = change.destinationId)) }
            is TvItemChange.Removed -> removed(change.outcome)
            is TvItemChange.Restored -> removals.remove(change.outcome.requestId)?.forEach {
                search.itemRestored(it.item, it.index, it.term, it.filter)
            }
            is TvItemChange.PositionSaved -> search.positionSaved(change.itemId, change.seconds)
        }
    }

    private fun removed(outcome: FilesDeleteOutcome) {
        val gone = outcome.confirmedGone().toSet()
        val results = search.state.value.content as? SearchContent.Ready
        val dropped = results?.items.orEmpty().withIndex()
            .filter { (_, item) -> item.id in gone || item.parentId in gone }
        if (results != null && dropped.isNotEmpty() && outcome.isConfirmedTrash) {
            val filter = search.state.value.filter
            removals[outcome.requestId] =
                dropped.map { (index, item) -> SearchRemoval(item, index, results.term, filter) }
        }
        (gone + dropped.map { it.value.id }).forEach(search::itemRemoved)
    }

    private fun result(itemId: FilesItemId): FilesItem? =
        (search.state.value.content as? SearchContent.Ready)?.items?.firstOrNull { it.id == itemId }
}

private class SearchRemoval(val item: FilesItem, val index: Int, val term: SearchTerm, val filter: SearchFilter)

internal fun TvHistoryFiles.hear(change: TvItemChange) {
    when (change) {
        is TvItemChange.PositionSaved -> forget(change.itemId)
        is TvItemChange.Moved -> forget(change.itemId)
        is TvItemChange.Edited -> forgetWithChildren(change.item.id)
        is TvItemChange.Renamed -> forgetWithChildren(change.itemId)
        is TvItemChange.Removed, is TvItemChange.Restored -> forgetAll()
    }
}

internal fun TvHomePins.hear(change: TvItemChange) {
    when (change) {
        is TvItemChange.Edited -> itemEdited(change.item.id)
        is TvItemChange.Renamed -> itemEdited(change.itemId)
        is TvItemChange.Removed -> change.outcome.confirmedGone().forEach(::itemRemoved)
        is TvItemChange.Restored -> change.outcome.intent.itemIds.forEach(::itemRestored)
        is TvItemChange.Moved, is TvItemChange.PositionSaved -> Unit
    }
}

internal fun ContinueWatching.hear(change: TvItemChange) {
    when (change) {
        is TvItemChange.Edited -> renamed(change.item.id, change.item.name)
        is TvItemChange.Renamed -> renamed(change.itemId, change.name)
        is TvItemChange.Removed -> removed(listOf(change.outcome))
        is TvItemChange.PositionSaved -> positionSaved(change.itemId, change.seconds)
        is TvItemChange.Moved, is TvItemChange.Restored -> Unit
    }
}

internal fun TvWatchNextRecorder.hear(change: TvItemChange, playback: TvWatchNextPlayback) {
    when (change) {
        is TvItemChange.Removed -> change.outcome.confirmedGone().forEach { fileGone(it.value) }
        is TvItemChange.PositionSaved -> change.media?.let { positionSaved(it.toWatchNextMedia(), change.seconds) }
            ?: playback.saved(change.itemId.value, change.seconds)
        is TvItemChange.Edited, is TvItemChange.Renamed, is TvItemChange.Moved, is TvItemChange.Restored -> Unit
    }
}

internal fun TrashController.hear(change: TvItemChange) {
    val changesTrash = when (change) {
        is TvItemChange.Removed ->
            change.outcome.intent.mode == FilesDeleteMode.TRASH && change.outcome.confirmedGone().isNotEmpty()
        is TvItemChange.Restored -> true
        else -> false
    }
    if (changesTrash && state.value.content is TrashContent.Loaded) dispatch(TrashEvent.Refresh)
}
