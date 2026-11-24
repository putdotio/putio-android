package io.putdotio.android.tv.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.putdotio.android.PutioApplication
import io.putdotio.android.PutioHttpClient
import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.PutioAuthTokenRevoker
import io.putdotio.android.auth.TokenRevocationResult
import io.putdotio.android.tv.auth.TvAuthRuntime
import io.putdotio.android.tv.auth.tvHttpClient
import io.putdotio.android.tv.files.viewerHttp
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
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
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TvNetworkingPoolTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        TvAuthRuntime.resetForTests()
        SingletonImageLoader.reset()
        PutioHttpClient.shared.connectionPool.evictAll()
    }

    @Test
    fun tvApiAndViewerClientsShareBasePoolAndDispatcher() {
        val apiClient = tvHttpClient()

        assertSame(PutioHttpClient.shared.connectionPool, apiClient.connectionPool)
        assertSame(PutioHttpClient.shared.dispatcher, apiClient.dispatcher)
        assertSame(PutioHttpClient.shared.connectionPool, viewerHttp.connectionPool)
        assertSame(PutioHttpClient.shared.dispatcher, viewerHttp.dispatcher)
        assertEquals(TV_CALL_TIMEOUT_MILLIS, apiClient.callTimeoutMillis)
        assertEquals(0, viewerHttp.callTimeoutMillis)
    }

    @Test
    fun tvRuntimeSdkClientSharesConnectionPoolWithBaseHttpClient() = runBlocking {
        PutioHttpClient.shared.connectionPool.evictAll()
        TvKeepAliveLoopbackServer().use { server ->
            val runtime = TvAuthRuntime.get(context)
            val apiTransport = runtime.putioClient.transportHttpClient()
            assertSame(PutioHttpClient.shared.connectionPool, apiTransport.connectionPool)
            assertSame(PutioHttpClient.shared.dispatcher, apiTransport.dispatcher)
            assertEquals(TV_CALL_TIMEOUT_MILLIS, apiTransport.callTimeoutMillis)
            runtime.putioClient.config.baseUrl = server.baseUrl

            val validation = runtime.putioClient.auth.validateToken("probe")
            assertTrue(validation.result)
            probeSharedPool(server.baseUrl)

            assertEquals(1, server.acceptedConnections.get())
            assertEquals(2, server.requestsServed.get())
        }
    }

    @Test
    fun tvTokenRevokerUsesSharedTvClientAndPreservesLiveToken() = runBlocking {
        val sent = mutableListOf<Request>()
        val tvDerived = tvHttpClient()
            .newBuilder()
            .addInterceptor(
                Interceptor { chain ->
                    sent += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(HTTP_OK)
                        .message("OK")
                        .body("""{"status":"OK"}""".toResponseBody())
                        .build()
                },
            )
            .build()
        val client = PutioClient(PutioConfig(accessToken = LIVE_TOKEN), okHttpClient = tvDerived)
        val result = PutioAuthTokenRevoker(client).revoke(REVOKED_TOKEN)

        assertEquals(TokenRevocationResult.REVOKED, result)
        assertEquals(LIVE_TOKEN, client.config.accessToken)
        assertEquals(TV_CALL_TIMEOUT_MILLIS, tvDerived.callTimeoutMillis)
        assertSame(PutioHttpClient.shared.connectionPool, tvDerived.connectionPool)
        assertEquals("Token ${REVOKED_TOKEN.reveal()}", sent.single().header("Authorization"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tvApplicationImplementsSingletonImageLoaderFactoryBackedBySharedPool() = runBlocking {
        assertTrue(context.applicationContext is SingletonImageLoader.Factory)
        assertTrue(context.applicationContext is PutioApplication)
        SingletonImageLoader.reset()
        PutioHttpClient.shared.connectionPool.evictAll()

        TvKeepAliveLoopbackServer().use { server ->
            val request = ImageRequest.Builder(context)
                .data("${server.baseUrl}/poster.png")
                .build()
            val result = SingletonImageLoader.get(context).execute(request)

            val error = (result as? ErrorResult)?.throwable
            assertTrue("Expected SuccessResult, got $result ($error)", result is SuccessResult)
            probeSharedPool(server.baseUrl)
            assertEquals(1, server.acceptedConnections.get())
            assertEquals(2, server.requestsServed.get())
        }
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
        const val TV_CALL_TIMEOUT_MILLIS = 10_000
        const val LIVE_TOKEN = "tv-live-token"
        val REVOKED_TOKEN = checkNotNull(AccessToken.parse("tv-revoked-token"))
    }
}

private class TvKeepAliveLoopbackServer : Closeable {
    private val server = ServerSocket(0, SERVER_BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val sockets = mutableListOf<Socket>()
    val acceptedConnections = AtomicInteger()
    val requestsServed = AtomicInteger()
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "tv-loopback-accept") {
            while (!server.isClosed) {
                val socket = acceptOrNull() ?: break
                acceptedConnections.incrementAndGet()
                synchronized(sockets) { sockets += socket }
                thread(isDaemon = true, name = "tv-loopback-conn") { serveSocket(socket) }
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
        var header = readHttpLine(input)
        while (!header.isNullOrEmpty()) {
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            header = readHttpLine(input)
        }
        if (header != null && contentLength > 0) drainBytes(input, contentLength)
        return path.takeIf { header != null }
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
        const val SCRATCH_SIZE = 4_096
        const val INITIAL_LINE_CAPACITY = 64
        val VALIDATE_JSON = """{"result":true,"user_id":1}""".encodeToByteArray()
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
