package io.putdotio.android.documents

import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.parsePutioTimestamp
import io.putdotio.android.search.SearchTerm
import java.io.InputStream

/**
 * A document is one account's put.io file: `<userId>:<fileId>`, the root folder being file 0. The account in the id
 * keeps a URI granted under one account from resolving under another; nothing in it is a credential.
 */
internal data class PutioDocumentId(
    val userId: Long,
    val fileId: FilesItemId,
) {
    val value: String get() = "$userId:${fileId.value}"
    val isRoot: Boolean get() = fileId == ROOT_FOLDER

    companion object {
        val ROOT_FOLDER = FilesItemId(0L)

        fun root(userId: Long): PutioDocumentId = PutioDocumentId(userId, ROOT_FOLDER)

        fun parse(value: String): PutioDocumentId? {
            val parts = value.split(':')
            val userId = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0L }
            val fileId = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0L }
            return if (parts.size == 2 && userId != null && fileId != null) {
                PutioDocumentId(userId, FilesItemId(fileId))
            } else {
                null
            }
        }
    }
}

/** The account's root: one per signed-in account, named by its user id. */
internal fun rootIdOf(userId: Long): String = userId.toString()

/** What a picker lists: a folder's pages or a search's pages, each with its own change notification. */
internal sealed interface DocumentsListingKey {
    data class Folder(val folder: PutioDocumentId) : DocumentsListingKey

    data class Search(val userId: Long, val query: String, val term: SearchTerm) : DocumentsListingKey {
        val rootId: String get() = rootIdOf(userId)
    }
}

internal data class DocumentsRoot(
    val rootId: String,
    val documentId: String,
    val summary: String?,
)

/** One row of document metadata; every document is read-only, so none carries a flag. */
internal data class DocumentRow(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val size: Long?,
    val lastModified: Long?,
)

/** The rows loaded so far; [loading] while another page is on its way, [failed] when the last page failed. */
internal data class DocumentsListing(
    val rows: List<DocumentRow>,
    val loading: Boolean,
    val failed: Boolean,
) {
    companion object {
        val Empty = DocumentsListing(emptyList(), loading = false, failed = false)
    }
}

internal fun FilesItem.toDocumentRow(userId: Long): DocumentRow =
    DocumentRow(
        documentId = PutioDocumentId(userId, id).value,
        displayName = name,
        mimeType = if (isFolder) Document.MIME_TYPE_DIR else mimeTypeOf(name),
        size = sizeBytes.takeUnless { isFolder },
        lastModified = parsePutioTimestamp(updatedAt ?: createdAt)?.toEpochMilli(),
    )

/** By extension, as FileProvider types its files; SDK 1.0.0 does not carry the API's `content_type`. */
internal fun mimeTypeOf(name: String): String =
    name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        ?: GENERIC_MIME_TYPE

private const val GENERIC_MIME_TYPE = "application/octet-stream"

/**
 * A document's bytes: their length and a reader from any offset. [OpenedBytes.interrupt] stops a read blocked on
 * another thread; closing the stream releases it.
 */
internal class DocumentBytes(
    val size: Long,
    val onDevice: Boolean,
    val open: (offset: Long) -> OpenedBytes,
)

internal class OpenedBytes(
    val input: InputStream,
    val interrupt: () -> Unit = {},
)
