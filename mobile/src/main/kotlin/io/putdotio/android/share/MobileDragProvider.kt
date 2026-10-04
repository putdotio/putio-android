package io.putdotio.android.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.putdotio.android.auth.MobileAuthState
import java.io.FileNotFoundException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Serves a file dragged out of Files to the app it was dropped on. Not exported: only the drop's read
 * grant reaches it. Opening waits for the export the drag started, up to the share-out ready window,
 * then hands out a read-only descriptor, and only while the session that started the drag is the
 * signed-in one.
 */
class MobileDragProvider : ContentProvider() {
    private val dependencies by lazy { MobileFileShareService.shareDependencies(requireNotNull(context)) }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = export(uri)?.mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val export = export(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> export.name
                        OpenableColumns.SIZE -> export.sizeBytes.takeIf { it >= 0L }
                        else -> null
                    }
                },
            )
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Dragged files are read-only")
        val export = export(uri) ?: throw FileNotFoundException("No such drag")
        val file = try {
            export.file.get(dependencies.readyTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (error: ExecutionException) {
            throw FileNotFoundException("The dragged file did not download").initCause(error)
        } catch (error: TimeoutException) {
            throw FileNotFoundException("The dragged file is still downloading").initCause(error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FileNotFoundException("Interrupted").initCause(error)
        }
        val signedIn = dependencies.authState.value as? MobileAuthState.SignedIn
        if (signedIn?.sessionId != export.session || !file.isFile) throw FileNotFoundException("No such drag")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun export(uri: Uri): MobileDragExports.Export? =
        uri.pathSegments.firstOrNull()?.let { MobileDragExports[it] }
}
