package io.putdotio.android.share

import android.content.ClipDescription
import android.content.Context
import android.provider.OpenableColumns
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val reader = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        useReadyWindow(5.seconds)
    }

    @After
    fun tearDown() {
        reader.shutdownNow()
        MobileDragExports.retainOnly(null)
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
    fun aDropOpensTheFileOnceItsExportLandsAndOnlyForReading() {
        val key = MobileDragExports.begin(SESSION, "poster.jpg", 6L, "image/jpeg")
        val uri = mobileFileDragClip(authority, key, "poster.jpg", "image/jpeg").getItemAt(0).uri

        val read = reader.submit<String> {
            requireNotNull(context.contentResolver.openInputStream(uri)).use { String(it.readBytes()) }
        }
        Thread.sleep(WAIT_MILLIS)
        assertFalse(read.isDone)
        MobileDragExports.ready(key, export("poster"))

        assertEquals("poster", read.get(5, TimeUnit.SECONDS))
        context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("poster.jpg", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(6L, cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        assertEquals("image/jpeg", context.contentResolver.getType(uri))
        assertThrows(SecurityException::class.java) { context.contentResolver.openFileDescriptor(uri, "w") }
    }

    @Test
    fun anotherSessionACancelledDragOrAnUnknownKeyReadsNothing() {
        val key = MobileDragExports.begin(SESSION, "poster.jpg", 6L, "image/jpeg")
        val uri = mobileFileDragClip(authority, key, "poster.jpg", "image/jpeg").getItemAt(0).uri
        MobileDragExports.ready(key, export("poster"))

        auth.value = signedIn(MobileAuthSessionId(2L))
        assertThrows(FileNotFoundException::class.java) { context.contentResolver.openInputStream(uri) }

        auth.value = signedIn(SESSION)
        MobileDragExports.cancel(key)
        assertThrows(FileNotFoundException::class.java) { context.contentResolver.openInputStream(uri) }

        val unknown = mobileFileDragClip(authority, "unknown", "poster.jpg", "image/jpeg").getItemAt(0).uri
        assertThrows(FileNotFoundException::class.java) { context.contentResolver.openInputStream(unknown) }
    }

    @Test
    fun aFileStillDownloadingAfterTheReadyWindowReadsNothing() {
        useReadyWindow(100.milliseconds)
        val key = MobileDragExports.begin(SESSION, "poster.jpg", 6L, "image/jpeg")
        val uri = mobileFileDragClip(authority, key, "poster.jpg", "image/jpeg").getItemAt(0).uri

        assertThrows(FileNotFoundException::class.java) { context.contentResolver.openInputStream(uri) }
    }

    @Test
    fun leavingASessionEndsOnlyItsOwnDrags() {
        val departed = MobileDragExports.begin(SESSION, "a.jpg", 1L, ClipDescription.MIMETYPE_UNKNOWN)
        val next = MobileDragExports.begin(MobileAuthSessionId(2L), "b.jpg", 1L, ClipDescription.MIMETYPE_UNKNOWN)

        MobileFileShareService.endSession(context, SESSION)

        assertEquals(null, MobileDragExports[departed])
        assertTrue(MobileDragExports[next] != null)
    }

    private fun useReadyWindow(window: kotlin.time.Duration) {
        MobileFileShareService.dependenciesForTest = MobileShareDependencies(
            http = OkHttpClient(),
            authState = auth,
            accessToken = { null },
            readyTimeout = window,
        )
        Robolectric.setupContentProvider(MobileDragProvider::class.java, authority)
    }

    private fun export(content: String): File =
        File.createTempFile("drag", ".jpg", context.cacheDir).apply { writeText(content) }

    private companion object {
        const val WAIT_MILLIS = 200L
        val SESSION = MobileAuthSessionId(1L)

        fun signedIn(session: MobileAuthSessionId) = MobileAuthState.SignedIn(
            account = MobileAccount(userId = 1L, username = "someone", email = "someone@example.com"),
            sessionId = session,
        )
    }
}
