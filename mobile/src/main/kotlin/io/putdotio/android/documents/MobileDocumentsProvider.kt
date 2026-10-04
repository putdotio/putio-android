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

/**
 * Lists the signed-in account's put.io Files in the system file picker, read-only. Signed out there is no root.
 * Listings arrive a page at a time with [DocumentsContract.EXTRA_LOADING] and a change notification per page, and
 * an opened document is a seekable proxy descriptor over its bytes: a completed download of the original from disk,
 * otherwise the share-out download from put.io. No credential reaches a URI, column or log.
 */
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
                // Read-only: no FLAG_SUPPORTS_CREATE, and no recents or tree access.
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_SEARCH)
        }
        cursor.setNotificationUri(context.contentResolver, DocumentsContract.buildRootsUri(authority(context)))
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        // Look up first: a document that isn't there throws before any cursor exists to leak.
        val row = documents().document(documentId)
        return MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { add(row) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = checkNotNull(context)
        return documents().children(parentDocumentId).toCursor(
            projection,
            DocumentsContract.buildChildDocumentsUri(authority(context), parentDocumentId),
        )
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val context = checkNotNull(context)
        return documents().search(rootId, query).toCursor(
            projection,
            DocumentsContract.buildSearchDocumentsUri(authority(context), rootId, query),
        )
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw FileNotFoundException("put.io documents are read-only")
        }
        val storage = checkNotNull(context).getSystemService(StorageManager::class.java)
        // One thread per open document, so a read waiting on the network holds up no other document. It starts
        // only once the document opened, so a refused open leaves no thread behind.
        val thread = HandlerThread("putio-document")
        val reader = documents().open(documentId) { thread.quitSafely() }
        thread.start()
        return try {
            storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, reader, Handler(thread.looper))
        } catch (error: IOException) {
            reader.onRelease()
            throw FileNotFoundException("Could not open a descriptor").apply { initCause(error) }
        }
    }

    private fun MatrixCursor.add(row: DocumentRow) {
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, row.documentId)
            .add(Document.COLUMN_DISPLAY_NAME, row.displayName)
            .add(Document.COLUMN_MIME_TYPE, row.mimeType)
            .add(Document.COLUMN_SIZE, row.size)
            .add(Document.COLUMN_LAST_MODIFIED, row.lastModified)
            .add(Document.COLUMN_FLAGS, 0)
    }

    private fun DocumentsListing.toCursor(projection: Array<out String>?, notificationUri: Uri): Cursor {
        val context = checkNotNull(context)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        rows.forEach { cursor.add(it) }
        cursor.extras = Bundle().apply {
            putBoolean(DocumentsContract.EXTRA_LOADING, loading)
            if (failed) {
                putString(DocumentsContract.EXTRA_ERROR, context.getString(R.string.mobile_documents_load_failed))
            }
        }
        cursor.setNotificationUri(context.contentResolver, notificationUri)
        return cursor
    }

    companion object {
        /** Replaces the provider's documents, session and network included; tests reset it. */
        @VisibleForTesting
        @Volatile
        internal var documentsForTest: MobileDocuments? = null

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

        /** A session started: pickers read the roots again, so the account's root appears. */
        fun sessionStarted(context: Context) = notifyRoots(context)

        /**
         * A session ended: every grant other apps hold on put.io documents goes, persisted ones included, and pickers
         * read the roots again, so the account's root disappears.
         */
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

        /** The documents over [backend], notifying listings through [context]'s resolver. */
        internal fun documents(context: Context, backend: MobileDocumentsBackend, scope: CoroutineScope) =
            MobileDocuments(
                backend = backend,
                scope = scope,
                onListingChanged = { key -> context.contentResolver.notifyChange(listingUri(context, key), null) },
                rootName = context.getString(DesignR.string.app_name),
            )

        private fun productionDocuments(context: Context): MobileDocuments {
            val app = context.applicationContext
            val runtime = MobileOAuthRuntime.get(app)
            // A picker can start the process; restore the stored session so the root can appear.
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
                remote = RemoteOriginals(share.http, share.accessToken)::bytes,
                onAuthenticationRequired = runtime::rejectSession,
            )
            return documents(app, backend, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        }
    }
}
