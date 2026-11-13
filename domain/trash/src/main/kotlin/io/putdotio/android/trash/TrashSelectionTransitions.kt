package io.putdotio.android.trash

import io.putdotio.android.files.FilesItemId

internal fun TrashMachine.restoreSelected(event: TrashEvent.RestoreSelected): TrashMachine? =
    loadedItems(event.itemIds)
        ?.takeIf { mayStartRestore && state.canRestoreAll(event.itemIds) }
        ?.let { submitAction(TrashAction.RestoreSelected(it)) }

internal fun TrashMachine.selectDeleteSelected(event: TrashEvent.SelectDeleteSelected): TrashMachine? =
    loadedItems(event.itemIds)
        ?.takeIf { request == null && state.canDeleteAll(event.itemIds) }
        ?.let { openActionConfirmation(TrashAction.DeleteSelected(it)) }

private fun TrashMachine.loadedItems(itemIds: Set<FilesItemId>): List<TrashItem>? {
    val items = (state.content as? TrashContent.Loaded)?.items.orEmpty().filter { it.id in itemIds }
    return items.takeIf { it.isNotEmpty() && it.size == itemIds.size }
}
