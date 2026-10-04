package io.putdotio.android.documents

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.system.ErrnoException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.search.SearchTerm
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.PutioFileType
import java.io.FileNotFoundException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class MobileDocumentsProviderTest {
    private val fixture = DocumentsFixture()

    @After
    fun tearDown() = fixture.close()

    @Test
    fun theRootListsOnlyWhileSignedInAndIsReadOnly() {
        assertEquals(0, fixture.roots().use { it.count })

        fixture.auth.value = signedIn(ALICE, session = 1)
        fixture.roots().use { roots ->
            assertEquals(1, roots.count)
            roots.moveToFirst()
            assertEquals("$ALICE", roots.string(Root.COLUMN_ROOT_ID))
            assertEquals("$ALICE:0", roots.string(Root.COLUMN_DOCUMENT_ID))
            assertEquals("put.io", roots.string(Root.COLUMN_TITLE))
            assertEquals("alice", roots.string(Root.COLUMN_SUMMARY))
            // Search only: nothing can be created, and the root offers no recents or tree access.
            assertEquals(Root.FLAG_SUPPORTS_SEARCH, roots.int(Root.COLUMN_FLAGS))
            assertEquals(DocumentsContract.buildRootsUri(fixture.authority), roots.notificationUri)
        }
        fixture.document("$ALICE:0").use { root ->
            assertEquals(Document.MIME_TYPE_DIR, root.string(Document.COLUMN_MIME_TYPE))
            assertEquals("put.io", root.string(Document.COLUMN_DISPLAY_NAME))
        }

        fixture.auth.value = MobileAuthState.SignedOut()
        assertEquals(0, fixture.roots().use { it.count })
        assertNull(fixture.documentOrNull("$ALICE:0"))
    }

    @Test
    fun aFolderPagesInWithLoadingExtrasAndAChangePerPage() {
        fixture.auth.value = signedIn(ALICE, session = 1)
        val firstPage = listOf(folder(2L, "Movies"), file(7L, "Clip.MP4", size = 30_704_510L)) +
            (1L..48L).map { file(100L + it, "notes-$it.txt") }
        fixture.files.folders[ROOT] = PutioResult.Success(FilesPage(firstPage, FilesCursor("page-2")))
        fixture.files.continuations["page-2"] =
            PutioResult.Success(FilesPage((49L..68L).map { file(100L + it, "notes-$it.txt") }, null))
        val folderUri = DocumentsContract.buildChildDocumentsUri(fixture.authority, "$ALICE:0")

        fixture.children("$ALICE:0").use { loading ->
            assertEquals(0, loading.count)
            assertTrue(loading.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
            assertEquals(folderUri, loading.notificationUri)
        }
        fixture.settle()
        assertEquals(listOf(folderUri), fixture.notified())

        fixture.children("$ALICE:0").use { firstLanded ->
            assertEquals(50, firstLanded.count)
            assertTrue(firstLanded.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
            assertEquals(Document.MIME_TYPE_DIR, firstLanded.row("$ALICE:2").string(Document.COLUMN_MIME_TYPE))
            assertTrue(firstLanded.isNull(firstLanded.getColumnIndexOrThrow(Document.COLUMN_SIZE)))
            val clip = firstLanded.row("$ALICE:7")
            assertEquals("Clip.MP4", clip.string(Document.COLUMN_DISPLAY_NAME))
            assertEquals("video/mp4", clip.string(Document.COLUMN_MIME_TYPE))
            assertEquals(30_704_510L, clip.long(Document.COLUMN_SIZE))
            assertEquals(Instant.parse("2026-09-30T15:41:33Z").toEpochMilli(), clip.long(Document.COLUMN_LAST_MODIFIED))
            assertEquals(0, clip.int(Document.COLUMN_FLAGS))
        }
        fixture.settle()
        assertEquals(listOf(folderUri, folderUri), fixture.notified())

        fixture.children("$ALICE:0").use { complete ->
            assertEquals(70, complete.count)
            assertFalse(complete.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
        }
        fixture.children("$ALICE:0").close()
        fixture.settle()
        // Each page was asked for once, in order.
        assertEquals(listOf(ROOT), fixture.files.folderLoads)
        assertEquals(listOf("page-2"), fixture.files.continuationLoads)
    }

    @Test
    fun aFailedPageIsReportedOnceThenAskedForAgainAndA401SignsOut() {
        fixture.auth.value = signedIn(ALICE, session = 1)
        fixture.files.folders[ROOT] = PutioResult.Failure(PutioFailure.ServerUnavailable(503, apiError(503)))

        fixture.children("$ALICE:0").close()
        fixture.settle()
        fixture.children("$ALICE:0").use { failed ->
            assertEquals(0, failed.count)
            assertFalse(failed.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
            assertEquals(
                "Couldn’t load from put.io. Open it again to retry.",
                failed.extras.getString(DocumentsContract.EXTRA_ERROR),
            )
        }
        fixture.settle()
        // Showing the failure starts nothing, so a page that keeps failing cannot loop the picker.
        assertEquals(listOf(ROOT), fixture.files.folderLoads)
        assertTrue(fixture.rejected.isEmpty())

        fixture.files.folders[ROOT] = PutioResult.Failure(PutioFailure.AuthenticationRequired(apiError(401)))
        fixture.children("$ALICE:0").use { retrying ->
            assertTrue(retrying.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
        }
        fixture.settle()
        assertEquals(listOf(ROOT, ROOT), fixture.files.folderLoads)
        assertEquals(listOf(MobileAuthSessionId(1L)), fixture.rejected)
    }

    @Test
    fun aPageLandingBeforeThePickerListensIsAnnouncedAgainUntilQueried() {
        fixture.auth.value = signedIn(ALICE, session = 1)
        fixture.files.folders[ROOT] = PutioResult.Success(FilesPage(listOf(file(7L, "alice.txt")), null))
        val folderUri = DocumentsContract.buildChildDocumentsUri(fixture.authority, "$ALICE:0")

        fixture.children("$ALICE:0").close()
        fixture.settle()
        assertEquals(listOf(folderUri), fixture.notified())
        // The picker registered after the page landed and heard nothing; the page is announced again.
        fixture.advance(500L)
        assertEquals(listOf(folderUri, folderUri), fixture.notified())

        assertEquals(1, fixture.children("$ALICE:0").use { it.count })
        fixture.advance(5_000L)
        assertEquals(listOf(folderUri, folderUri), fixture.notified())
    }

    @Test
    fun searchPagesThroughTheSearchRepository() {
        fixture.auth.value = signedIn(ALICE, session = 1)
        fixture.search.results["harbor"] = SearchPage(listOf(file(9L, "harbor.mp3")), FilesCursor("more"), total = 2)
        fixture.search.continuations["more"] = SearchPage(listOf(folder(3L, "Harbor")), null, total = 2)
        val searchUri = DocumentsContract.buildSearchDocumentsUri(fixture.authority, "$ALICE", " harbor")

        assertTrue(fixture.search("$ALICE", " harbor")!!.use { it.extras.getBoolean(DocumentsContract.EXTRA_LOADING) })
        fixture.settle()
        assertEquals(listOf(searchUri), fixture.notified())
        fixture.search("$ALICE", " harbor")!!.close()
        fixture.settle()
        fixture.search("$ALICE", " harbor")!!.use { results ->
            assertEquals(listOf("$ALICE:9", "$ALICE:3"), results.ids())
            assertFalse(results.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
            assertEquals(searchUri, results.notificationUri)
        }
        assertEquals(listOf(SearchTerm("harbor")), fixture.search.searched)
        assertEquals(0, fixture.search("$ALICE", "  ")!!.use { it.count })
        assertNull(fixture.search("$BOB", "harbor"))
    }

    @Test
    fun signOutAndAnAccountSwitchLeaveNothingOfThePreviousAccount() {
        fixture.auth.value = signedIn(ALICE, session = 1)
        fixture.files.folders[ROOT] = PutioResult.Success(FilesPage(listOf(file(7L, "alice.txt", size = 8L)), null))
        fixture.remoteBytes = "alice's!".toByteArray()
        fixture.children("$ALICE:0").close()
        fixture.settle()
        assertEquals(1, fixture.children("$ALICE:0").use { it.count })
        val reader = fixture.documents.open("$ALICE:7")
        val buffer = ByteArray(4)
        assertEquals(4, reader.onRead(0L, 4, buffer))

        fixture.auth.value = signedIn(BOB, session = 2)
        fixture.settle()

        // Alice's ids do not resolve under Bob's session, even for a document she had open.
        assertNull(fixture.documentOrNull("$ALICE:7"))
        assertNull(fixture.childrenOrNull("$ALICE:0"))
        assertThrows(ErrnoException::class.java) { reader.onRead(4L, 4, buffer) }
        assertThrows(FileNotFoundException::class.java) { fixture.documents.open("$ALICE:7") }
        // Bob's root reads put.io afresh rather than anything Alice listed.
        fixture.children("$BOB:0").use { bobs ->
            assertEquals(0, bobs.count)
            assertTrue(bobs.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
        }
        fixture.roots().use { roots ->
            roots.moveToFirst()
            assertEquals("$BOB", roots.string(Root.COLUMN_ROOT_ID))
        }

        fixture.auth.value = MobileAuthState.SignedOut()
        fixture.settle()
        assertEquals(0, fixture.roots().use { it.count })
        assertNull(fixture.childrenOrNull("$BOB:0"))
    }

    @Test
    fun aDownloadedOriginalIsReadFromTheDeviceAndAnythingElseStreamsFromPutio() {
        fixture.auth.value = signedIn(ALICE, session = 1)
        fixture.offline[FilesItemId(9L)] = "local original".toByteArray()

        val local = fixture.documents.open("$ALICE:9")
        assertTrue(local.onDevice)
        assertArrayEquals("local original".toByteArray(), local.readAll())
        assertTrue(fixture.requests.isEmpty())

        val remoteBytes = ByteArray(1_000) { (it % 251).toByte() }
        fixture.remoteBytes = remoteBytes
        fixture.files.items[FilesItemId(10L)] = file(10L, "clip.mp4", size = remoteBytes.size.toLong())
        val remote = fixture.documents.open("$ALICE:10")
        assertFalse(remote.onDevice)
        assertEquals(1_000L, remote.onGetSize())
        val buffer = ByteArray(16)
        assertEquals(16, remote.onRead(0L, 16, buffer))
        assertArrayEquals(remoteBytes.copyOfRange(0, 16), buffer)
        // Reading on, or a short jump ahead, keeps the same request.
        assertEquals(16, remote.onRead(16L, 16, buffer))
        assertEquals(16, remote.onRead(200L, 16, buffer))
        assertArrayEquals(remoteBytes.copyOfRange(200, 216), buffer)
        // The end of the file reads short; a jump back asks again from that offset.
        assertEquals(10, remote.onRead(990L, 16, buffer))
        assertArrayEquals(remoteBytes.copyOfRange(990, 1_000), buffer.copyOf(10))
        assertEquals(4, remote.onRead(4L, 4, buffer))
        assertArrayEquals(remoteBytes.copyOfRange(4, 8), buffer.copyOf(4))

        assertEquals(listOf("bytes=0-", "bytes=4-"), fixture.requests.map { it.header("Range") })
        fixture.requests.forEach { request ->
            assertEquals("https://api.put.io/v2/files/10/download", request.url.toString())
            assertEquals("Token secret", request.header("Authorization"))
        }
        remote.onRelease()
        local.onRelease()
    }

    @Test
    fun writesAreRefused() {
        fixture.auth.value = signedIn(ALICE, session = 1)
        fixture.offline[FilesItemId(9L)] = "local original".toByteArray()
        for (mode in listOf("w", "rw", "wa", "rwt")) {
            assertThrows(FileNotFoundException::class.java) { fixture.provider.openDocument("$ALICE:9", mode, null) }
        }
        // Refused before the document is looked up, so no write mode reaches its bytes.
        assertEquals(0, fixture.offlineLookups)
    }

    @Test
    fun aSessionEndRevokesEveryGrantAndRefreshesTheRoots() {
        val revoked = mutableListOf<Pair<Uri, Int>>()
        val context = object : ContextWrapper(fixture.context) {
            override fun revokeUriPermission(uri: Uri, modeFlags: Int) {
                revoked += uri to modeFlags
            }
        }

        MobileDocumentsProvider.sessionLeft(context)

        assertEquals(
            listOf(
                Uri.parse("content://${fixture.authority}") to
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
            ),
            revoked,
        )
        assertEquals(listOf(DocumentsContract.buildRootsUri(fixture.authority)), fixture.notified())
    }

    private class DocumentsFixture {
        val context: Context = ApplicationProvider.getApplicationContext()
        val authority = "${context.packageName}.documents"
        private val scheduler = TestCoroutineScheduler()
        private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val auth = MutableStateFlow<MobileAuthState>(MobileAuthState.SignedOut())
        val files = ScriptedFiles()
        val search = ScriptedSearch()
        val offline = mutableMapOf<FilesItemId, ByteArray>()
        var offlineLookups = 0
        val rejected = mutableListOf<MobileAuthSessionId>()
        val requests = mutableListOf<Request>()
        var remoteBytes = ByteArray(0)
        private val http = OkHttpClient.Builder().addInterceptor { chain -> serve(chain.request()) }.build()
        val documents = MobileDocumentsProvider.documents(
            context,
            MobileDocumentsBackend(
                authState = auth,
                files = files,
                search = search,
                offline = { userId, fileId ->
                    offlineLookups += 1
                    offline[fileId]?.takeIf { userId == ALICE }?.let { bytes ->
                        DocumentBytes(bytes.size.toLong(), onDevice = true) { offset ->
                            OpenedBytes(bytes.inputStream(offset.toInt(), bytes.size - offset.toInt()))
                        }
                    }
                },
                remote = RemoteOriginals(http) { "secret" }::bytes,
                onAuthenticationRequired = { rejected += it },
            ),
            scope,
        )
        val provider: MobileDocumentsProvider = Robolectric.buildContentProvider(MobileDocumentsProvider::class.java)
            .create(
                ProviderInfo().apply {
                    authority = this@DocumentsFixture.authority
                    exported = true
                    grantUriPermissions = true
                    readPermission = Manifest.permission.MANAGE_DOCUMENTS
                    writePermission = Manifest.permission.MANAGE_DOCUMENTS
                },
            )
            .get()

        init {
            MobileDocumentsProvider.documentsForTest = documents
            settle()
        }

        fun settle() = scheduler.runCurrent()

        fun advance(millis: Long) {
            scheduler.advanceTimeBy(millis)
            scheduler.runCurrent()
        }

        // DocumentsProvider answers only the Bundle form the platform has used since Android O.
        fun query(uri: Uri): Cursor? = context.contentResolver.query(uri, null, Bundle(), null)

        /** DocumentsUI sends the search term as a query argument; the URI only names the root. */
        fun search(rootId: String, term: String): Cursor? =
            context.contentResolver.query(
                DocumentsContract.buildSearchDocumentsUri(authority, rootId, term),
                null,
                Bundle().apply { putString(DocumentsContract.QUERY_ARG_DISPLAY_NAME, term) },
                null,
            )

        fun roots(): Cursor = checkNotNull(query(DocumentsContract.buildRootsUri(authority)))

        fun childrenOrNull(documentId: String): Cursor? =
            query(DocumentsContract.buildChildDocumentsUri(authority, documentId))

        fun children(documentId: String): Cursor = checkNotNull(childrenOrNull(documentId))

        fun documentOrNull(documentId: String): Cursor? =
            query(DocumentsContract.buildDocumentUri(authority, documentId))?.apply { moveToFirst() }

        fun document(documentId: String): Cursor = checkNotNull(documentOrNull(documentId))

        fun notified(): List<Uri> = shadowOf(context.contentResolver).notifiedUris.map { it.uri }

        private fun serve(request: Request): Response {
            requests += request
            val offset = request.header("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .header("Content-Range", "bytes $offset-${remoteBytes.size - 1}/${remoteBytes.size}")
                .body(remoteBytes.copyOfRange(offset, remoteBytes.size).toResponseBody())
                .build()
        }

        fun close() {
            MobileDocumentsProvider.documentsForTest = null
            scope.cancel()
        }
    }

    private class ScriptedFiles : StubFilesRepository() {
        val folders = mutableMapOf<FilesItemId, PutioResult<FilesPage>>()
        val continuations = mutableMapOf<String, PutioResult<FilesPage>>()
        val items = mutableMapOf<FilesItemId, FilesItem>()
        val folderLoads = mutableListOf<FilesItemId>()
        val continuationLoads = mutableListOf<String>()

        override suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage> {
            folderLoads += folderId
            return folders[folderId] ?: PutioResult.Success(FilesPage(emptyList(), null))
        }

        override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<FilesPage> {
            continuationLoads += cursor.value
            return checkNotNull(continuations[cursor.value])
        }

        override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> =
            items[itemId]?.let { PutioResult.Success(it) }
                ?: PutioResult.Failure(PutioFailure.ApiRejected(404, "NotFound", apiError(404)))
    }

    private class ScriptedSearch : SearchRepository {
        val results = mutableMapOf<String, SearchPage>()
        val continuations = mutableMapOf<String, SearchPage>()
        val searched = mutableListOf<SearchTerm>()

        override suspend fun search(term: SearchTerm): PutioResult<SearchPage> {
            searched += term
            return PutioResult.Success(checkNotNull(results[term.value]))
        }

        override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage> =
            PutioResult.Success(checkNotNull(continuations[cursor.value]))
    }

    private companion object {
        const val ALICE = 101L
        const val BOB = 202L
        val ROOT = FilesItemId(0L)

        fun signedIn(userId: Long, session: Long): MobileAuthState =
            MobileAuthState.SignedIn(
                MobileAccount(userId = userId, username = if (userId == ALICE) "alice" else "bob", email = ""),
                MobileAuthSessionId(session),
            )

        fun file(id: Long, name: String, size: Long = 42L) = FilesItem(
            id = FilesItemId(id),
            parentId = ROOT,
            name = name,
            type = PutioFileType.FILE,
            sizeBytes = size,
            createdAt = "2026-08-25T14:39:02",
            updatedAt = "2026-09-30T15:41:33",
        )

        fun folder(id: Long, name: String) = file(id, name, size = 276_445_467L).copy(type = PutioFileType.FOLDER)

        fun apiError(status: Int) = PutioApiException(
            request = PutioRequestData("GET", "https://api.put.io/v2/files/list"),
            resolvedStatusCode = status,
            httpStatusCode = status,
            resolvedErrorType = "Error",
            envelope = PutioApiErrorEnvelope(statusCode = status, errorType = "Error"),
            responseBody = "{}",
            message = "Request failed",
        )

        fun Cursor.string(column: String): String? = getString(getColumnIndexOrThrow(column))

        fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

        fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

        fun Cursor.ids(): List<String> = buildList {
            moveToPosition(-1)
            while (moveToNext()) add(checkNotNull(string(Document.COLUMN_DOCUMENT_ID)))
        }

        fun Cursor.row(documentId: String): Cursor = apply {
            moveToPosition(ids().indexOf(documentId))
        }

        fun DocumentReader.readAll(): ByteArray {
            val buffer = ByteArray(onGetSize().toInt())
            return buffer.copyOf(onRead(0L, buffer.size, buffer))
        }
    }
}
