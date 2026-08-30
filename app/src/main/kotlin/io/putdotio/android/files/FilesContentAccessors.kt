package io.putdotio.android.files

internal fun FilesContent.items(): List<FilesItem> =
    when (this) {
        is FilesContent.Ready -> items
        is FilesContent.Empty,
        is FilesContent.Failed,
        is FilesContent.Loading,
        -> emptyList()
    }

internal fun FilesContent.paging(): FilesPaging? =
    when (this) {
        is FilesContent.Empty -> paging
        is FilesContent.Ready -> paging
        is FilesContent.Failed,
        is FilesContent.Loading,
        -> null
    }

internal fun FilesContent.viewport(): FilesViewportPosition =
    when (this) {
        is FilesContent.Empty -> viewport
        is FilesContent.Ready -> viewport
        is FilesContent.Failed,
        is FilesContent.Loading,
        -> FilesViewportPosition()
    }

internal fun FilesContent.withPaging(paging: FilesPaging): FilesContent? =
    when (this) {
        is FilesContent.Empty -> copy(paging = paging)
        is FilesContent.Ready -> copy(paging = paging)
        is FilesContent.Failed,
        is FilesContent.Loading,
        -> null
    }

internal fun FilesContent.withViewport(viewport: FilesViewportPosition): FilesContent? =
    when (this) {
        is FilesContent.Empty -> copy(viewport = viewport)
        is FilesContent.Ready -> copy(viewport = viewport)
        is FilesContent.Failed,
        is FilesContent.Loading,
        -> null
    }

internal fun FilesContent.withoutActivePagingRequest(): FilesContent =
    when (this) {
        is FilesContent.Empty -> copy(paging = paging.withoutActiveRequest())
        is FilesContent.Ready -> copy(paging = paging.withoutActiveRequest())
        is FilesContent.Failed,
        is FilesContent.Loading,
        -> this
    }

private fun FilesPaging.withoutActiveRequest(): FilesPaging =
    when (this) {
        is FilesPaging.Loading -> FilesPaging.Available(cursor)
        is FilesPaging.Available,
        is FilesPaging.Failed,
        FilesPaging.Complete,
        -> this
    }

internal fun <T> List<T>.replaceLast(value: T): List<T> = replaceAt(lastIndex, value)

internal fun <T> List<T>.replaceAt(
    index: Int,
    value: T,
): List<T> = mapIndexed { itemIndex, item -> if (itemIndex == index) value else item }
