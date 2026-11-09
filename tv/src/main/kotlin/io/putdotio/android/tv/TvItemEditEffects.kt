package io.putdotio.android.tv

import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.SearchFilter
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.files.isConfirmedTrash
import io.putdotio.android.tv.history.TvHistoryFiles
import io.putdotio.android.tv.home.TvHomePins

internal class TvItemEditEffects(
    private val search: SearchController,
    private val files: FilesBrowserController,
    private val historyFiles: TvHistoryFiles,
    private val homePins: TvHomePins,
    private val refreshTrash: () -> Unit,
    private val onGone: (FilesItem, FilesDeleteOutcome) -> Unit,
) {
    private val searchRemovals = mutableMapOf<FilesItemId, SearchRemoval>()

    fun edited(item: FilesItem) {
        search.itemUpdated(item)
        historyFiles.forget(item.id)
        invalidateFiles()
        homePins.itemEdited(item.id)
    }

    fun removed(item: FilesItem, outcome: FilesDeleteOutcome) {
        val results = search.state.value.content as? SearchContent.Ready
        val index = results?.items?.indexOfFirst { it.id == item.id } ?: -1
        if (results != null && index >= 0 && outcome.isConfirmedTrash) {
            searchRemovals[item.id] = SearchRemoval(item, index, results.term, search.state.value.filter)
        }
        search.itemRemoved(item.id)
        historyFiles.forget(item.id)
        homePins.itemRemoved(item.id)
        invalidateFiles()
        refreshTrash()
        onGone(item, outcome)
    }

    fun undone(outcome: FilesDeleteOutcome) {
        refreshTrash()
        outcome.intent.itemIds.forEach { id ->
            searchRemovals.remove(id)?.let { search.itemRestored(it.item, it.index, it.term, it.filter) }
            historyFiles.forget(id)
            homePins.itemRestored(id)
        }
    }

    fun renamedInFiles(itemId: FilesItemId, name: String) {
        searchResult(itemId)?.let { search.itemUpdated(it.copy(name = name)) }
        historyFiles.forget(itemId)
        homePins.itemEdited(itemId)
    }

    fun movedInFiles(itemId: FilesItemId, destinationId: FilesItemId) {
        searchResult(itemId)?.let { search.itemUpdated(it.copy(parentId = destinationId)) }
        historyFiles.forget(itemId)
    }

    private fun searchResult(itemId: FilesItemId): FilesItem? =
        (search.state.value.content as? SearchContent.Ready)?.items?.firstOrNull { it.id == itemId }

    private fun invalidateFiles() {
        files.dispatch(FilesBrowserEvent.InvalidateAllFolders)
        files.dispatch(FilesBrowserEvent.ReloadIfStale)
    }
}

private class SearchRemoval(val item: FilesItem, val index: Int, val term: SearchTerm, val filter: SearchFilter)
