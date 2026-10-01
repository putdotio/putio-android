package io.putdotio.android.files

/**
 * The most pages a folder reads to find its [FilesFolderState.revealItemId]: 10 pages of 50 rows.
 * Past it, as when the folder ends first, the folder shows the rows read so far from the top,
 * with Load more if it has more, and the item is not revealed.
 */
internal const val MAX_REVEAL_PAGES = 10

/**
 * Later pages being read to find [FilesFolderState.revealItemId]. The folder shows as loading
 * meanwhile, so it opens once at the item rather than jumping to it under the user; the request
 * belongs to the folder, so leaving it cancels the search.
 */
@ConsistentCopyVisibility
data class FilesRevealSearch internal constructor(
    internal val requestId: FilesRequestId,
    internal val cursor: FilesCursor,
    internal val items: List<FilesItem>,
)

/** A listing page for a loading folder: its first page, or the next page of a reveal search. */
internal fun FilesBrowserState.listingLoaded(
    index: Int,
    folder: FilesFolderState,
    requestId: FilesRequestId,
    page: FilesPage,
): FilesBrowserTransition {
    val search = folder.revealSearch?.takeIf { it.requestId == requestId }
    // A folder an outside open pushed takes its name from the listing (the root keeps its
    // localized title); later pages carry no folder.
    val named = if (search == null) {
        val name = folder.folder.name ?: page.parent?.takeIf { it.id != FilesFolder.Root.id }?.name
        folder.copy(folder = folder.folder.copy(sort = page.sort ?: folder.folder.sort, name = name))
    } else {
        folder
    }
    val consumedCursors = if (search == null) emptySet() else folder.consumedCursors + search.cursor
    val items = (search?.items.orEmpty() + page.items).distinctBy(FilesItem::id)
    val paging = page.nextCursor.toPaging(consumedCursors)
    return settleReveal(index, named.copy(consumedCursors = consumedCursors), items, paging)
}

/** A reveal search page failed: the rows read so far show, with a retry for the rest. */
internal fun FilesFolderState.revealPageFailed(requestId: FilesRequestId, failure: FilesFailure): FilesFolderState? {
    val search = revealSearch?.takeIf { it.requestId == requestId } ?: return null
    return copy(content = contentFor(search.items, FilesPaging.Failed(search.cursor, failure)), revealSearch = null)
}

/**
 * Shows [itemId] in the current folder, reading later pages when it is not among the loaded rows;
 * a folder still loading looks for it in what it reads.
 */
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

/**
 * Opens [folder] at its revealed item when [items] hold it, or reads the next page while the item
 * is missing, more pages exist, and the cap allows; otherwise shows [items] from the top.
 */
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
        val settled = folder.copy(content = contentFor(items, paging, viewport), revealSearch = null)
        FilesBrowserTransition(copy(stack = stack.replaceAt(index, settled)))
    }
}
