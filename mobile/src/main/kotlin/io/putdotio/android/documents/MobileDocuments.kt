package io.putdotio.android.documents

import android.os.SystemClock
import android.provider.DocumentsContract.Document
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileSessionKey
import io.putdotio.android.auth.sessionKey
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.search.SearchTerm
import java.io.FileNotFoundException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** What the documents surface reads: the session, put.io through the Files and Search repositories, and bytes. */
internal class MobileDocumentsBackend(
    val authState: StateFlow<MobileAuthState>,
    val files: FilesRepository,
    val search: SearchRepository,
    /** A completed download of the file's original bytes, read from disk, or null. */
    val offline: (userId: Long, fileId: FilesItemId) -> DocumentBytes?,
    val remote: (fileId: FilesItemId, size: Long) -> DocumentBytes,
    /** put.io rejected the session with a 401; the app signs it out as anywhere else. */
    val onAuthenticationRequired: (MobileAuthSessionId) -> Unit,
)

/**
 * The signed-in account's Files tree as read-only documents. Listings page in through [DocumentListings]; a single
 * document is read from them or from put.io. Everything held here belongs to one session and goes, with every
 * reader it opened, when that session ends. A document id names its account, so nothing resolves under another.
 */
internal class MobileDocuments(
    private val backend: MobileDocumentsBackend,
    private val scope: CoroutineScope,
    private val onListingChanged: (DocumentsListingKey) -> Unit,
    private val rootName: String,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Any()
    private var session: Session? = null

    init {
        scope.launch {
            backend.authState.map { it.sessionKey() }.distinctUntilChanged().collect { enter(it) }
        }
    }

    /** The account's root while signed in; signed out there is none, as the platform's guide asks. */
    fun root(): DocumentsRoot? {
        val signedIn = backend.authState.value as? MobileAuthState.SignedIn ?: return null
        val userId = signedIn.account.userId
        return DocumentsRoot(
            rootId = rootIdOf(userId),
            documentId = PutioDocumentId.root(userId).value,
            summary = signedIn.account.username.takeIf(String::isNotBlank),
        )
    }

    fun document(documentId: String): DocumentRow {
        val (session, id) = signedIn(documentId)
        if (id.isRoot) {
            return DocumentRow(id.value, rootName, Document.MIME_TYPE_DIR, size = null, lastModified = null)
        }
        return session.item(id.fileId).toDocumentRow(session.key.userId)
    }

    fun children(parentDocumentId: String): DocumentsListing {
        val (session, id) = signedIn(parentDocumentId)
        return session.listing(DocumentsListingKey.Folder(id))
    }

    fun search(rootId: String, query: String): DocumentsListing {
        val session = awaitSession()?.takeIf { rootId == rootIdOf(it.key.userId) }
            ?: throw FileNotFoundException("Not the signed-in account's root")
        val term = query.trim().takeIf(String::isNotEmpty)?.let(::SearchTerm) ?: return DocumentsListing.Empty
        return session.listing(DocumentsListingKey.Search(session.key.userId, query, term))
    }

    /** A completed download of the original is read from disk; anything else streams from put.io. */
    fun open(documentId: String, onReleased: () -> Unit = {}): DocumentReader {
        val (session, id) = signedIn(documentId)
        val bytes = session.bytes(id)
        lateinit var reader: DocumentReader
        reader = DocumentReader(
            bytes = bytes,
            session = session.key,
            isCurrent = { backend.authState.value.sessionKey() == session.key },
            onReleased = {
                synchronized(lock) { session.readers -= reader }
                onReleased()
            },
        )
        synchronized(lock) {
            if (this.session !== session) throw FileNotFoundException("The session ended")
            session.readers += reader
        }
        return reader
    }

    private fun signedIn(documentId: String): Pair<Session, PutioDocumentId> {
        val id = PutioDocumentId.parse(documentId)
        val session = awaitSession()
        if (id == null || session == null || session.key.userId != id.userId) {
            throw FileNotFoundException("Not a document of the signed-in account")
        }
        return session to id
    }

    /**
     * The settled session. A query can arrive before a cold start has restored it; that wait is bounded, and a
     * session still unsettled afterwards reads as signed out.
     */
    private fun awaitSession(): Session? {
        val state = backend.authState.value.takeIf { it.isSettled() }
            ?: runBlocking { withTimeoutOrNull(SETTLE_TIMEOUT) { backend.authState.first { it.isSettled() } } }
        return enter(state?.sessionKey())
    }

    /** Leaving a session drops what it loaded and stops every reader it opened. */
    private fun enter(key: MobileSessionKey?): Session? =
        synchronized(lock) {
            val current = session
            if (current?.key == key) return@synchronized current
            current?.listings?.close()
            current?.readers?.forEach(DocumentReader::revoke)
            current?.readers?.clear()
            key?.let(::Session).also { session = it }
        }

    private inner class Session(val key: MobileSessionKey) {
        val resolved = mutableMapOf<FilesItemId, FilesItem>()
        val readers = mutableSetOf<DocumentReader>()
        val listings = DocumentListings(backend.files, backend.search, scope, clock) { listing, failure ->
            failure?.let(::report)
            onListingChanged(listing)
        }

        fun listing(key: DocumentsListingKey): DocumentsListing {
            val snapshot = listings.query(key)
            return DocumentsListing(
                rows = snapshot.items.map { it.toDocumentRow(this.key.userId) },
                loading = snapshot.loading,
                failed = snapshot.failed,
            )
        }

        /** From a listing this session holds, else one exact-ID read from put.io, kept for the session. */
        fun item(fileId: FilesItemId): FilesItem =
            listings.find(fileId) ?: synchronized(lock) { resolved[fileId] } ?: resolve(fileId)

        private fun resolve(fileId: FilesItemId): FilesItem {
            val result = runBlocking { withTimeoutOrNull(RESOLVE_TIMEOUT) { backend.files.resolveItem(fileId) } }
            if (result is PutioResult.Failure) report(result.failure)
            val item = (result as? PutioResult.Success)?.value
                ?: throw FileNotFoundException("put.io did not return it")
            synchronized(lock) { resolved[fileId] = item }
            return item
        }

        fun bytes(id: PutioDocumentId): DocumentBytes {
            val fileId = id.fileId.takeUnless { id.isRoot } ?: throw FileNotFoundException("A folder has no bytes")
            backend.offline(key.userId, fileId)?.let { return it }
            val file = item(fileId).takeUnless(FilesItem::isFolder)
                ?: throw FileNotFoundException("A folder has no bytes")
            return backend.remote(file.id, file.sizeBytes)
        }

        fun report(failure: PutioFailure) {
            if (failure is PutioFailure.AuthenticationRequired) backend.onAuthenticationRequired(key.sessionId)
        }
    }

    private companion object {
        val SETTLE_TIMEOUT: Duration = 15.seconds
        val RESOLVE_TIMEOUT: Duration = 15.seconds
    }
}

/** Signed in, signed out or waiting on the viewer; the restore and validation states still change on their own. */
private fun MobileAuthState.isSettled(): Boolean =
    when (this) {
        MobileAuthState.Initializing,
        MobileAuthState.RestoringSession,
        is MobileAuthState.ValidatingSession,
        MobileAuthState.SigningOut,
        -> false
        else -> true
    }
