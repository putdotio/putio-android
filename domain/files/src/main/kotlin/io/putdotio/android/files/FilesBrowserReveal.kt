package io.putdotio.android.files

import io.putdotio.android.PutioFailure

internal const val MAX_REVEAL_PAGES = 10

@ConsistentCopyVisibility
public data class FilesRevealSearch internal constructor(
    internal val requestId: FilesRequestId,
    internal val cursor: FilesCursor,
    internal val items: List<FilesItem>,
)

internal fun FilesBrowserState.listingLoaded(
    index: Int,
    folder: FilesFolderState,
    requestId: FilesRequestId,
    page: FilesPage,
): FilesBrowserTransition {
    val search = folder.revealSearch?.takeIf { it.requestId == requestId }
    val named = if (search == null) {
        val name = folder.folder.name ?: page.parent?.takeIf { it.id != FilesFolder.Root.id }?.name
        folder.copy(folder = folder.folder.copy(sort = page.sort ?: folder.folder.sort, name = name))
    } else {
        folder
    }
    val consumedCursors = if (search == null) emptySet() else folder.consumedCursors + search.cursor
    val summarized = named.copy(summary = page.summary() ?: named.summary)
    val items = (search?.items.orEmpty() + page.items).distinctBy(FilesItem::id)
    val paging = page.nextCursor.toPaging(consumedCursors)
    return settleReveal(index, summarized.copy(consumedCursors = consumedCursors), items, paging)
}

internal fun FilesFolderState.revealPageFailed(requestId: FilesRequestId, failure: PutioFailure): FilesFolderState? {
    val search = revealSearch?.takeIf { it.requestId == requestId } ?: return null
    return copy(
        content = contentFor(search.items, FilesPaging.Failed(search.cursor, failure)),
        revealItemId = null,
        revealSearch = null,
    )
}

internal fun FilesFolderState.withoutReveal(): FilesFolderState {
    val search = revealSearch
    val shown = if (search == null) content else contentFor(search.items, FilesPaging.Available(search.cursor))
    return copy(content = shown, revealItemId = null, revealSearch = null)
}

internal fun FilesBrowserState.revealItem(folderId: FilesItemId, itemId: FilesItemId): FilesBrowserTransition {
    val content = current.content
    val paging = content.paging()
    return when {
        current.folder.id != folderId || current.operation != FilesFolderOperation.Idle ->
            FilesBrowserTransition(this, consumed = false)
        content is FilesContent.Loading ->
            FilesBrowserTransition(copy(stack = stack.replaceLast(current.copy(revealItemId = itemId))))
        content.items().any { it.id == itemId } -> FilesBrowserTransition(this)
        paging !is FilesPaging.Available || !current.canReadAnotherPage ->
            FilesBrowserTransition(this, consumed = false)
        else -> settleReveal(stack.lastIndex, current.copy(revealItemId = itemId), content.items(), paging)
    }
}

private val FilesFolderState.canReadAnotherPage: Boolean
    get() = consumedCursors.size + 1 < MAX_REVEAL_PAGES

private fun FilesBrowserState.settleReveal(
    index: Int,
    folder: FilesFolderState,
    items: List<FilesItem>,
    paging: FilesPaging,
): FilesBrowserTransition {
    val target = folder.revealItemId
    val revealIndex = target?.let { items.indexOfFirst { item -> item.id == it } } ?: -1
    val next = (paging as? FilesPaging.Available)?.takeIf { folder.canReadAnotherPage }
    return if (target != null && revealIndex < 0 && next != null) {
        val requestId = FilesRequestId(nextRequestValue)
        val searching = folder.copy(
            content = FilesContent.Loading(requestId),
            revealSearch = FilesRevealSearch(requestId, next.cursor, items),
        )
        FilesBrowserTransition(
            state = copy(stack = stack.replaceAt(index, searching), nextRequestValue = nextRequestValue + 1),
            effect = FilesBrowserEffect.LoadNextPage(next.cursor, requestId),
        )
    } else {
        val viewport = FilesViewportPosition(revealIndex.coerceAtLeast(0))
        val settled = folder.copy(
            content = contentFor(items, paging, viewport),
            revealItemId = target?.takeIf { revealIndex >= 0 },
            revealSearch = null,
        )
        FilesBrowserTransition(copy(stack = stack.replaceAt(index, settled)))
    }
}
