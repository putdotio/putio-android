package io.putdotio.android.documents

import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.parsePutioTimestamp
import io.putdotio.android.search.SearchTerm
import java.io.InputStream
import java.util.Locale

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

internal fun rootIdOf(userId: Long): String = userId.toString()

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

internal data class DocumentRow(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val size: Long?,
    val lastModified: Long?,
)

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
        mimeType = if (isFolder) {
            Document.MIME_TYPE_DIR
        } else {
            mimeTypeOf(name, contentType)
        },
        size = sizeBytes.takeUnless { isFolder },
        lastModified = parsePutioTimestamp(updatedAt ?: createdAt)?.toEpochMilli(),
    )

internal fun mimeTypeOf(name: String, contentType: String?): String =
    contentType?.normalizedFileMimeType()
        ?: name.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        ?: GENERIC_MIME_TYPE

private fun String.normalizedFileMimeType(): String? {
    val essence = substringBefore(';').trim().lowercase(Locale.ROOT)
    if (essence == Document.MIME_TYPE_DIR || essence == GENERIC_MIME_TYPE) return null
    return essence.takeIf(MIME_TYPE_ESSENCE::matches)
}

private val MIME_TYPE_ESSENCE = Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+""")
private const val GENERIC_MIME_TYPE = "application/octet-stream"

internal class DocumentBytes(
    val size: Long,
    val onDevice: Boolean,
    val open: (offset: Long) -> OpenedBytes,
)

internal class OpenedBytes(
    val input: InputStream,
    val interrupt: () -> Unit = {},
)
