package io.putdotio.android.documents

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import androidx.annotation.VisibleForTesting
import io.putdotio.android.R
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.downloads.OfflineOriginals
import io.putdotio.android.files.SdkFilesRepository
import io.putdotio.android.search.SdkSearchRepository
import io.putdotio.android.share.MobileShareDependencies
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.putdotio.android.design.R as DesignR

class MobileDocumentsProvider : DocumentsProvider() {
    private val production: MobileDocuments by lazy { productionDocuments(checkNotNull(context)) }

    private fun documents(): MobileDocuments = documentsForTest ?: production

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val context = checkNotNull(context)
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        documents().root()?.let { root ->
            cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, root.rootId)
                .add(Root.COLUMN_DOCUMENT_ID, root.documentId)
                .add(Root.COLUMN_TITLE, context.getString(DesignR.string.app_name))
                .add(Root.COLUMN_SUMMARY, root.summary)
                .add(Root.COLUMN_ICON, DesignR.drawable.putio_icon)
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_SEARCH)
        }
        cursor.setNotificationUri(context.contentResolver, DocumentsContract.buildRootsUri(authority(context)))
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val row = documents().document(documentId)
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { add(row) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = checkNotNull(context)
        val uri = DocumentsContract.buildChildDocumentsUri(authority(context), parentDocumentId)
        return listingCursor(projection, uri) { documents().children(parentDocumentId) }
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val context = checkNotNull(context)
        val uri = DocumentsContract.buildSearchDocumentsUri(authority(context), rootId, query)
        return listingCursor(projection, uri) { documents().search(rootId, query) }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw FileNotFoundException("put.io documents are read-only")
        }
        val storage = checkNotNull(context).getSystemService(StorageManager::class.java)
        val thread = HandlerThread("putio-document")
        val reader = documents().open(documentId, signal) { thread.quitSafely() }
        var opened = false
        return try {
            thread.start()
            openProxyDescriptor(storage, reader, Handler(thread.looper)).also { opened = true }
        } catch (error: IOException) {
            throw FileNotFoundException("Could not open a descriptor").apply { initCause(error) }
        } finally {
            if (!opened) reader.onRelease()
        }
    }

    private fun openProxyDescriptor(
        storage: StorageManager,
        reader: ProxyFileDescriptorCallback,
        handler: Handler,
    ): ParcelFileDescriptor =
        openProxyDescriptorForTest?.invoke(storage, reader, handler)
            ?: storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, reader, handler)

    private fun MatrixCursor.add(row: DocumentRow) {
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, row.documentId)
            .add(Document.COLUMN_DISPLAY_NAME, row.displayName)
            .add(Document.COLUMN_MIME_TYPE, row.mimeType)
            .add(Document.COLUMN_SIZE, row.size)
            .add(Document.COLUMN_LAST_MODIFIED, row.lastModified)
            .add(Document.COLUMN_FLAGS, 0)
    }

    private fun listingCursor(
        projection: Array<out String>?,
        notificationUri: Uri,
        listing: () -> DocumentsListing,
    ): Cursor {
        val context = checkNotNull(context)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        cursor.setNotificationUri(context.contentResolver, notificationUri)
        var filled = false
        try {
            val rows = listing()
            rows.rows.forEach { cursor.add(it) }
            cursor.extras = Bundle().apply {
                putBoolean(DocumentsContract.EXTRA_LOADING, rows.loading)
                if (rows.failed) {
                    putString(DocumentsContract.EXTRA_ERROR, context.getString(R.string.mobile_documents_load_failed))
                }
            }
            filled = true
            return cursor
        } finally {
            if (!filled) cursor.close()
        }
    }

    companion object {
        @VisibleForTesting
        @Volatile
        internal var documentsForTest: MobileDocuments? = null

        @VisibleForTesting
        @Volatile
        internal var openProxyDescriptorForTest:
            ((StorageManager, ProxyFileDescriptorCallback, Handler) -> ParcelFileDescriptor)? = null

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
        )

        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        internal fun authority(context: Context): String = "${context.packageName}.documents"

        fun sessionStarted(context: Context) = notifyRoots(context)

        fun sessionLeft(context: Context) {
            val everyDocument = Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority(context))
                .build()
            context.revokeUriPermission(
                everyDocument,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            notifyRoots(context)
        }

        private fun notifyRoots(context: Context) {
            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(context)), null)
        }

        internal fun listingUri(context: Context, key: DocumentsListingKey): Uri =
            when (key) {
                is DocumentsListingKey.Folder ->
                    DocumentsContract.buildChildDocumentsUri(authority(context), key.folder.value)
                is DocumentsListingKey.Search ->
                    DocumentsContract.buildSearchDocumentsUri(authority(context), key.rootId, key.query)
            }

        internal fun documents(
            context: Context,
            backend: MobileDocumentsBackend,
            scope: CoroutineScope,
            clock: () -> Long = SystemClock::elapsedRealtime,
        ) = MobileDocuments(
            backend = backend,
            scope = scope,
            onListingChanged = { key -> context.contentResolver.notifyChange(listingUri(context, key), null) },
            rootName = context.getString(DesignR.string.app_name),
            clock = clock,
        )

        private fun productionDocuments(context: Context): MobileDocuments {
            val app = context.applicationContext
            val runtime = MobileOAuthRuntime.get(app)
            runtime.ensureSessionRestored {}
            val share = MobileShareDependencies.from(app)
            val offline by lazy { OfflineOriginals.from(app) }
            val backend = MobileDocumentsBackend(
                authState = runtime.authController.state,
                files = SdkFilesRepository(runtime.putioClient),
                search = SdkSearchRepository(runtime.putioClient),
                offline = { userId, fileId ->
                    offline.find(userId, fileId)?.let { copy ->
                        DocumentBytes(copy.length, onDevice = true) { offset -> OpenedBytes(copy.open(offset)) }
                    }
                },
                remote = RemoteOriginals(share.http, share.originals)::bytes,
                onAuthenticationRequired = runtime::rejectSession,
            )
            return documents(app, backend, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        }
    }
}
