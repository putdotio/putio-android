import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandFontSyncTest {
    private val font = "font bytes".toByteArray()
    private val url = "https://static.put.io/fonts/gt-america/regular.ttf"
    private val root: File = Files.createTempDirectory("brand-font-sync").toFile()
    private val downloads = mutableListOf<String>()
    private val requests = mutableListOf<String>()
    private var responses = listOf<Triple<Int, Map<String, String>, ByteArray>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val (status, headers, body) = synchronized(requests) {
                requests += exchange.requestURI.path
                responses[minOf(requests.size, responses.size) - 1]
            }
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    @After
    fun tearDown() {
        server.stop(0)
        root.deleteRecursively()
    }

    @Test
    fun syncWritesVerifiedFontsAndSkipsMatchingOnes() {
        val lock = lock()
        BrandFontSync.sync(lock, root, {}, serve(font))
        assertArrayEquals(font, root.resolve("fonts/gt_america_regular.ttf").readBytes())
        assertEquals(listOf(url), downloads)
        BrandFontSync.sync(lock, root, {}, serve(font))
        assertEquals(1, downloads.size)
    }

    @Test
    fun syncReplacesACorruptFont() {
        root.resolve("fonts").mkdirs()
        root.resolve("fonts/gt_america_regular.ttf").writeBytes("corrupt".toByteArray())
        BrandFontSync.sync(lock(), root, {}, serve(font))
        assertArrayEquals(font, root.resolve("fonts/gt_america_regular.ttf").readBytes())
    }

    @Test
    fun syncRejectsAnUnlockedDownloadWithoutWriting() {
        val error = runCatching { BrandFontSync.sync(lock(), root, {}, serve("other".toByteArray())) }.exceptionOrNull()
        assertTrue("${error?.message}", error is GradleException && error.message!!.contains("not written"))
        assertEquals(emptyList<String>(), root.resolve("fonts").list()!!.toList())
    }

    @Test
    fun fetchReturnsTheBody() {
        responses = listOf(Triple(200, emptyMap(), font))
        assertArrayEquals(font, fetch())
    }

    @Test
    fun fetchRefusesRedirects() {
        responses = listOf(Triple(302, mapOf("Location" to "/elsewhere.ttf"), ByteArray(0)), Triple(200, emptyMap(), font))
        assertTrue(fetchFailure().contains("returned 302"))
        assertEquals(listOf("/fonts/a.ttf"), requests)
    }

    @Test
    fun fetchDoesNotRetryClientErrors() {
        responses = listOf(Triple(404, emptyMap(), ByteArray(0)))
        assertTrue(fetchFailure().contains("returned 404"))
        assertEquals(1, requests.size)
    }

    @Test
    fun fetchRetriesServerErrorsABoundedNumberOfTimes() {
        responses = listOf(Triple(503, emptyMap(), ByteArray(0)))
        assertTrue(fetchFailure().contains("returned 503"))
        assertEquals(BrandFontSync.ATTEMPTS, requests.size)
        responses = listOf(Triple(503, emptyMap(), ByteArray(0)), Triple(200, emptyMap(), font))
        requests.clear()
        assertArrayEquals(font, fetch())
    }

    private fun fetch(): ByteArray = BrandFontSync.fetch("$base/fonts/a.ttf") {}

    private fun fetchFailure(): String {
        val error = runCatching { fetch() }.exceptionOrNull()
        assertTrue("$error", error is GradleException)
        return error!!.message!!
    }

    private fun serve(data: ByteArray): (String) -> ByteArray = { downloads += it; data }

    private fun lock(): BrandFonts.Lock = BrandFonts.parseLock(
        """
        {"baseUrl": "https://static.put.io", "directory": "fonts", "files": {"gt_america_regular.ttf": {
          "path": "fonts/gt-america/regular.ttf", "sha256": "${sha256(font)}", "family": "GT America", "weight": 400}}}
        """.trimIndent(),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
