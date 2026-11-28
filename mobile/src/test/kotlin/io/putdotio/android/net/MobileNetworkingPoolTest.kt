package io.putdotio.android.net

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.putdotio.android.PutioApplication
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioHttpClient
import io.putdotio.android.PutioResult
import io.putdotio.android.toPutioFailure
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.mobileHttpClient
import io.putdotio.android.downloads.DownloadArtifact
import io.putdotio.android.downloads.MobileDownloadCache
import io.putdotio.android.downloads.apiUrl
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.share.sharedHttp
import io.putdotio.android.transfers.SdkTransfersRepository
import io.putdotio.android.transfers.TorrentUpload
import io.putdotio.android.transfers.TransferAddRequest
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioTransportException
import io.putdotio.sdk.errors.PutioTransportFailureKind
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileNetworkingPoolTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        MobileOAuthRuntime.resetForTests()
        SingletonImageLoader.reset()
        PutioHttpClient.shared.connectionPool.evictAll()
    }

    @Test
    fun mobileHttpClientsShareBasePoolDispatcherAndRespectiveTimeouts() {
        val apiClient = mobileHttpClient()
        val downloadClient = MobileDownloadCache.get(context).httpClient
        val shareClient = sharedHttp

        for (client in listOf(apiClient, downloadClient, shareClient)) {
            assertSame(PutioHttpClient.shared.connectionPool, client.connectionPool)
            assertSame(PutioHttpClient.shared.dispatcher, client.dispatcher)
        }
        assertEquals(EXPECTED_API_CALL_TIMEOUT_MILLIS, apiClient.callTimeoutMillis)
        assertEquals(0, downloadClient.callTimeoutMillis)
        assertEquals(0, shareClient.callTimeoutMillis)
    }

    @Test
    fun runtimeSdkClientSharesConnectionPoolWithBaseHttpClient() = runBlocking {
        PutioHttpClient.shared.connectionPool.evictAll()
        KeepAliveLoopbackServer().use { server ->
            val runtime = MobileOAuthRuntime.get(context)
            val apiTransport = runtime.putioClient.transportHttpClient()
            val uploadTransport = runtime.uploadClient.transportHttpClient()
            assertSame(PutioHttpClient.shared.connectionPool, apiTransport.connectionPool)
            assertSame(PutioHttpClient.shared.dispatcher, apiTransport.dispatcher)
            assertSame(PutioHttpClient.shared.connectionPool, uploadTransport.connectionPool)
            assertSame(PutioHttpClient.shared.dispatcher, uploadTransport.dispatcher)
            assertEquals(EXPECTED_API_CALL_TIMEOUT_MILLIS, apiTransport.callTimeoutMillis)
            assertEquals(0, uploadTransport.callTimeoutMillis)
            assertSame(runtime.putioClient.config, runtime.uploadClient.config)
            runtime.putioClient.config.baseUrl = server.baseUrl
            runtime.putioClient.config.uploadBaseUrl = server.baseUrl
            runtime.putioClient.setAccessToken("probe")

            assertTrue(runtime.putioClient.auth.validateToken().result)
            val uploadResult = SdkTransfersRepository(runtime.putioClient, runtime.uploadClient).add(
                TransferAddRequest.Torrent(TorrentUpload("sample.torrent", TORRENT_BYTES), saveParentId = 0L),
            )
            assertTrue(uploadResult is PutioResult.Success)
            probeSharedPool(server.baseUrl)

            assertEquals(1, server.acceptedConnections.get())
            assertEquals(RUNTIME_POOL_REQUESTS, server.requestsServed.get())
        }
    }

    @Test
    fun mobileClientBoundsRegularApiCallsWhileExemptingMultipartTorrentUploads() = runBlocking {
        KeepAliveLoopbackServer(delayMillis = SERVER_DELAY_MILLIS).use { server ->
            val boundedClient = mobileHttpClient(callTimeoutMillis = SHORT_DEADLINE_MILLIS)
            assertSame(PutioHttpClient.shared.connectionPool, boundedClient.connectionPool)
            assertSame(PutioHttpClient.shared.dispatcher, boundedClient.dispatcher)

            val apiSdk = PutioClient(
                PutioConfig(accessToken = "test-token", baseUrl = server.baseUrl, uploadBaseUrl = server.baseUrl),
                okHttpClient = boundedClient,
            )
            val uploadSdk = PutioClient(apiSdk.config, okHttpClient = PutioHttpClient.shared)
            val torrentRequest = TransferAddRequest.Torrent(
                file = TorrentUpload("sample.torrent", TORRENT_BYTES),
                saveParentId = 0L,
            )

            val apiFailure = runCatching { apiSdk.auth.validateToken() }.exceptionOrNull()
            val transportFailure = (apiFailure as? PutioOperationException)?.underlyingError
                as? PutioTransportException
            assertTrue("Expected timeout failure, got $apiFailure", transportFailure != null)
            assertEquals(PutioTransportFailureKind.INTERRUPTED, transportFailure?.failureKind)
            assertTrue((apiFailure as? PutioException)?.toPutioFailure() is PutioFailure.Timeout)

            val singleClientUpload = SdkTransfersRepository(apiSdk, apiSdk).add(torrentRequest)
            assertTrue(singleClientUpload is PutioResult.Failure)
            assertTrue((singleClientUpload as PutioResult.Failure).failure is PutioFailure.Timeout)

            val uploadResult = SdkTransfersRepository(apiSdk, uploadSdk).add(torrentRequest)
            assertTrue(uploadResult is PutioResult.Success)
            val outcome = (uploadResult as PutioResult.Success).value
            assertEquals(UPLOADED_TRANSFER_ID, outcome.added.single().id.value)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun applicationImplementsSingletonImageLoaderFactoryBackedBySharedPool() = runBlocking {
        assertTrue(context.applicationContext is SingletonImageLoader.Factory)
        assertTrue(context.applicationContext is PutioApplication)
        SingletonImageLoader.reset()
        PutioHttpClient.shared.connectionPool.evictAll()

        KeepAliveLoopbackServer().use { server ->
            val request = ImageRequest.Builder(context)
                .data("${server.baseUrl}/thumb.png")
                .build()
            val result = SingletonImageLoader.get(context).execute(request)

            val error = (result as? ErrorResult)?.throwable
            assertTrue("Expected SuccessResult, got $result ($error)", result is SuccessResult)
            probeSharedPool(server.baseUrl)
            assertEquals(1, server.acceptedConnections.get())
            assertEquals(2, server.requestsServed.get())
        }
    }

    @Test
    fun downloadArtifactApiUrlFormatsAgainstInjectedFilesApiWithoutLeakingClient() {
        val customBaseUrl = "https://staging.example.invalid/v2"
        val customFiles = PutioClient(
            PutioConfig(baseUrl = customBaseUrl),
            okHttpClient = PutioHttpClient.shared,
        ).files

        val url = DownloadArtifact.HLS.apiUrl(FilesItemId(TEST_FILE_ID), customFiles)

        assertTrue(url.startsWith("$customBaseUrl/files/42/"))
    }

    private fun probeSharedPool(baseUrl: String) {
        val request = Request.Builder().url("$baseUrl/probe").build()
        PutioHttpClient.shared.newCall(request).execute().use { response ->
            assertEquals(HTTP_OK, response.code)
        }
    }

    private fun PutioClient.transportHttpClient(): OkHttpClient {
        val field = PutioClient::class.java.getDeclaredField("httpClientDelegate")
        field.isAccessible = true
        return (field.get(this) as Lazy<*>).value as OkHttpClient
    }

    private companion object {
        const val HTTP_OK = 200
        const val EXPECTED_API_CALL_TIMEOUT_MILLIS = 30_000
        const val SHORT_DEADLINE_MILLIS = 150L
        const val SERVER_DELAY_MILLIS = 350L
        const val RUNTIME_POOL_REQUESTS = 3
        const val UPLOADED_TRANSFER_ID = 7L
        const val TEST_FILE_ID = 42L
        val TORRENT_BYTES = "d4:info0:e".encodeToByteArray()
    }
}

private class KeepAliveLoopbackServer(
    private val delayMillis: Long = 0L,
) : Closeable {
    private val server = ServerSocket(0, SERVER_BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val sockets = mutableListOf<Socket>()
    val acceptedConnections = AtomicInteger()
    val requestsServed = AtomicInteger()
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "mobile-loopback-accept") {
            while (!server.isClosed) {
                val socket = acceptOrNull() ?: break
                acceptedConnections.incrementAndGet()
                synchronized(sockets) { sockets += socket }
                thread(isDaemon = true, name = "mobile-loopback-conn") { serveSocket(socket) }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
    }

    private fun acceptOrNull(): Socket? =
        try {
            server.accept()
        } catch (_: IOException) {
            null
        }

    private fun serveSocket(socket: Socket) {
        socket.soTimeout = SOCKET_READ_TIMEOUT_MILLIS
        socket.use { client ->
            val input = client.getInputStream()
            val out = client.getOutputStream()
            runCatching {
                while (!client.isClosed) {
                    val path = readRequestPath(input) ?: break
                    if (delayMillis > 0L) Thread.sleep(delayMillis)
                    requestsServed.incrementAndGet()
                    writeResponse(out, path)
                }
            }
        }
    }

    private fun readRequestPath(input: InputStream): String? {
        val requestLine = readHttpLine(input) ?: return null
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        var contentLength = 0
        var chunked = false
        var header = readHttpLine(input)
        while (!header.isNullOrEmpty()) {
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            } else if (header.startsWith("Transfer-Encoding:", ignoreCase = true) &&
                header.contains("chunked", ignoreCase = true)
            ) {
                chunked = true
            }
            header = readHttpLine(input)
        }
        if (header != null) {
            if (chunked) drainChunkedBody(input) else if (contentLength > 0) drainBytes(input, contentLength)
        }
        return path.takeIf { header != null }
    }

    private fun drainChunkedBody(input: InputStream) {
        while (true) {
            val sizeLine = readHttpLine(input) ?: throw EOFException("Unexpected EOF in chunk header")
            val chunkSize = sizeLine.substringBefore(';').trim().toInt(HEX_RADIX)
            if (chunkSize == 0) {
                readHttpLine(input)
                return
            }
            drainBytes(input, chunkSize)
            readHttpLine(input)
        }
    }

    private fun drainBytes(input: InputStream, count: Int) {
        var remaining = count
        val scratch = ByteArray(minOf(count, SCRATCH_SIZE))
        while (remaining > 0) {
            val read = input.read(scratch, 0, minOf(remaining, scratch.size))
            if (read < 0) throw EOFException("Unexpected EOF reading request body")
            remaining -= read
        }
    }

    private fun readHttpLine(input: InputStream): String? {
        val bytes = ArrayList<Byte>(INITIAL_LINE_CAPACITY)
        var current = input.read()
        if (current < 0) return null
        while (current >= 0) {
            if (current == '\n'.code) break
            if (current != '\r'.code) bytes.add(current.toByte())
            current = input.read()
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun writeResponse(out: OutputStream, path: String) {
        val (contentType, body) = when {
            path.endsWith(".png") -> "image/png" to PNG_1X1
            path.endsWith("/oauth2/validate") -> "application/json" to VALIDATE_JSON
            path.endsWith("/files/upload") -> "application/json" to UPLOAD_JSON
            else -> "application/json" to OK_JSON
        }
        val header = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: keep-alive\r\n\r\n"
            ).encodeToByteArray()
        out.write(header)
        out.write(body)
        out.flush()
    }

    private companion object {
        const val SERVER_BACKLOG = 8
        const val SOCKET_READ_TIMEOUT_MILLIS = 5_000
        const val HEX_RADIX = 16
        const val SCRATCH_SIZE = 4_096
        const val INITIAL_LINE_CAPACITY = 64
        val VALIDATE_JSON = """{"result":true,"user_id":1}""".encodeToByteArray()
        val UPLOAD_JSON = (
            """{"status":"OK","transfer":{"id":7,"name":"sample.torrent",""" +
                """"status":"WAITING","save_parent_id":0,"created_at":"2026-09-30T08:00:00"}}"""
            ).encodeToByteArray()
        val OK_JSON = """{"status":"OK"}""".encodeToByteArray()
        val PNG_1X1 = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte(),
        )
    }
}
