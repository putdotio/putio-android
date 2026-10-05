package io.putdotio.android.share

import android.content.Context
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileSessionKey
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileDragProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val auth = MutableStateFlow<MobileAuthState>(signedIn(SESSION))
    private val authority = mobileDragAuthority(context)
    private val original = ByteArray(ORIGINAL_SIZE) { (it % 251).toByte() }
    private val requests = CopyOnWriteArrayList<Request>()
    private val dependencies = MobileShareDependencies(
        http = OkHttpClient.Builder().addInterceptor { chain -> ranged(chain.request()) }.build(),
        authState = auth,
        accessToken = { "session-token" },
    )

    @Before
    fun setUp() {
        MobileFileShareService.dependenciesForTest = dependencies
        Robolectric.setupContentProvider(MobileDragProvider::class.java, authority)
    }

    @After
    fun tearDown() {
        MobileFileDrags.clearForTest()
        MobileFileShareService.dependenciesForTest = null
    }

    @Test
    fun theDragCarriesOnlyATokenFreeProviderUriWithAReadOnlyGrant() {
        val clip = mobileFileDragClip(authority, "drag-key", "Harbor/film.mkv", "video/x-matroska")

        assertEquals(1, clip.itemCount)
        val uri = clip.getItemAt(0).uri
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.drag", uri.authority)
        assertEquals(listOf("drag-key", "Harbor_film.mkv"), uri.pathSegments)
        assertEquals("video/x-matroska", clip.description.getMimeType(0))
        val everything = "$uri ${clip.description} ${clip.getItemAt(0).text} ${clip.getItemAt(0).intent}"
        listOf("token", "oauth", "api.put.io", "http").forEach { assertFalse(everything.contains(it)) }

        assertEquals(View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ, MOBILE_FILE_DRAG_FLAGS)
        val wider = View.DRAG_FLAG_GLOBAL_URI_WRITE or View.DRAG_FLAG_GLOBAL_PERSISTABLE_URI_PERMISSION or
            View.DRAG_FLAG_GLOBAL_PREFIX_URI_PERMISSION
        assertEquals(0, MOBILE_FILE_DRAG_FLAGS and wider)
    }

    @Test
    fun openingReturnsAtOnceAndReadsStreamTheOriginalInRanges() {
        val key = MobileFileDrags.begin(SESSION, POSTER)

        val reader = MobileFileDrags.open(key, dependencies) {}
        assertTrue("Nothing is fetched until the drop target reads", requests.isEmpty())
        assertEquals(ORIGINAL_SIZE.toLong(), reader.onGetSize())

        val buffer = ByteArray(16)
        assertEquals(16, reader.onRead(0L, 16, buffer))
        assertArrayEquals(original.copyOfRange(0, 16), buffer)
        assertEquals(16, reader.onRead(FAR_OFFSET, 16, buffer))
        assertArrayEquals(original.copyOfRange(FAR_OFFSET.toInt(), FAR_OFFSET.toInt() + 16), buffer)

        assertEquals(listOf("bytes=0-", "bytes=$FAR_OFFSET-"), requests.map { it.header("Range") })
        requests.forEach { request ->
            assertEquals("Token session-token", request.header("Authorization"))
            assertFalse(request.url.toString().contains("session-token"))
            assertEquals("/v2/files/${POSTER.id.value}/download", request.url.encodedPath)
        }
    }

    @Test
    fun theProviderNamesTypesAndRefusesWritingADrag() {
        val key = MobileFileDrags.begin(SESSION, POSTER)
        val uri = mobileFileDragClip(authority, key, POSTER.name, "image/jpeg").getItemAt(0).uri

        requireNotNull(context.contentResolver.query(uri, null, null, null, null)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("poster.jpg", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(ORIGINAL_SIZE.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        assertEquals("image/jpeg", context.contentResolver.getType(uri))
        assertThrows(SecurityException::class.java) { context.contentResolver.openFileDescriptor(uri, "w") }
    }

    @Test
    fun endingTheSessionStopsReadsAndOnlyItsSessionOpensADrag() {
        val key = MobileFileDrags.begin(SESSION, POSTER)
        val reader = MobileFileDrags.open(key, dependencies) {}
        assertEquals(4, reader.onRead(0L, 4, ByteArray(4)))

        auth.value = signedIn(OTHER)
        assertThrows(ErrnoException::class.java) { reader.onRead(4L, 4, ByteArray(4)) }
        assertThrows(FileNotFoundException::class.java) { MobileFileDrags.open(key, dependencies) {} }

        auth.value = signedIn(SESSION)
        val again = MobileFileDrags.open(key, dependencies) {}
        MobileFileDrags.endSession(SESSION.sessionId)
        assertThrows(ErrnoException::class.java) { again.onRead(0L, 4, ByteArray(4)) }
        assertThrows(FileNotFoundException::class.java) { MobileFileDrags.open(key, dependencies) {} }
    }

    @Test
    fun aDragNobodyTookIsForgottenAndAnotherSessionsDragsStay() {
        val untaken = MobileFileDrags.begin(SESSION, POSTER)
        val later = MobileFileDrags.begin(OTHER, POSTER)

        MobileFileDrags.forget(untaken)
        MobileFileDrags.endSession(SESSION.sessionId)

        assertNull(MobileFileDrags[untaken])
        assertTrue(MobileFileDrags[later] != null)
        assertTrue(requests.isEmpty())
    }

    /** put.io's download endpoint answering a range from the request's offset. */
    private fun ranged(request: Request): Response {
        requests += request
        val start = request.header("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(PARTIAL_CONTENT)
            .message("Partial Content")
            .header("Content-Range", "bytes $start-${original.size - 1}/${original.size}")
            .body(original.copyOfRange(start, original.size).toResponseBody("image/jpeg".toMediaType()))
            .build()
    }

    private companion object {
        const val ORIGINAL_SIZE = 600_000
        const val FAR_OFFSET = 500_000L
        const val PARTIAL_CONTENT = 206
        val SESSION = MobileSessionKey(1L, MobileAuthSessionId(1L))
        val OTHER = MobileSessionKey(1L, MobileAuthSessionId(2L))
        val POSTER = FilesItem(
            FilesItemId(9L), FilesItemId(0L), "poster.jpg", PutioFileType.IMAGE, ORIGINAL_SIZE.toLong(), "2026-10-04",
        )

        fun signedIn(session: MobileSessionKey) = MobileAuthState.SignedIn(
            account = MobileAccount(userId = session.userId, username = "someone", email = "someone@example.com"),
            sessionId = session.sessionId,
        )
    }
}
