package io.putdotio.android.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Serves a file dragged out of Files to the app it was dropped on. Not exported: only the drop's read
 * grant reaches it. Opening hands out a read-only proxy descriptor at once; reads stream the original
 * from put.io with ranges, as the system file picker's documents do, only while the session that
 * started the drag is the signed-in one.
 */
class MobileDragProvider : ContentProvider() {
    private val dependencies by lazy { MobileFileShareService.shareDependencies(requireNotNull(context)) }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = drag(uri)?.mimeType

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val drag = drag(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> drag.name
                        OpenableColumns.SIZE -> drag.sizeBytes.takeIf { it >= 0L }
                        else -> null
                    }
                },
            )
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw SecurityException("Dragged files are read-only")
        }
        val key = uri.pathSegments.firstOrNull() ?: throw FileNotFoundException("No such drag")
        val storage = requireNotNull(context).getSystemService(StorageManager::class.java)
        // One thread per open file, so a read waiting on the network holds up no other; it starts only once
        // the drag opened, so a refused open leaves no thread behind.
        val thread = HandlerThread("putio-drag")
        val reader = MobileFileDrags.open(key, dependencies) { thread.quitSafely() }
        thread.start()
        return try {
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, reader, Handler(thread.looper))
        } catch (error: IOException) {
            reader.onRelease()
            throw FileNotFoundException("Could not open a descriptor").apply { initCause(error) }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun drag(uri: Uri): MobileFileDrags.Drag? = uri.pathSegments.firstOrNull()?.let { MobileFileDrags[it] }
}
