package io.putdotio.android

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Synthetic SDK responses and an empty live playlist keep source-ownership proof independent of decoding. */
internal class PlaybackConfigHttpFixture : Closeable {
    val resolutions = ConcurrentLinkedQueue<String>()
    val mediaRequests = ConcurrentLinkedQueue<String>()
    val unexpectedRequests = ConcurrentLinkedQueue<String>()
    private val releaseMedia = CountDownLatch(1)
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@PlaybackConfigHttpFixture.executor
        createContext("/v2/", ::respond)
        start()
    }
    val baseUrl = "http://127.0.0.1:${server.address.port}/v2/"
    @Volatile private var format = "hls"

    private fun respond(exchange: HttpExchange) {
        exchange.use {
            val path = exchange.requestURI.path
            if (path == "/v2/files/42/mp4/stream") {
                mediaRequests.add(path)
                check(releaseMedia.await(30, TimeUnit.SECONDS)) { "Media fixture was not released" }
                return
            }
            val body = if (path == "/v2/files/42/hls/media.m3u8") {
                mediaRequests.add(path)
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n"
            } else {
                responseBody(exchange)
            }
            if (body == null) {
                unexpectedRequests.add("${exchange.requestMethod} $path")
                exchange.sendResponseHeaders(404, -1)
            } else {
                val bytes = body.toByteArray(Charsets.UTF_8)
                val contentType = if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "application/json"
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
    }

    private fun responseBody(exchange: HttpExchange): String? =
        when ("${exchange.requestMethod} ${exchange.requestURI.path}") {
            "GET /v2/config" -> """{"status":"OK","config":{"video_playback_type":"$format"}}"""
            "PUT /v2/config/video_playback_type" -> {
                val body = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                format = body.getValue("value").jsonPrimitive.content
                """{"status":"OK"}"""
            }
            "GET /v2/account/settings" -> """{"status":"OK","settings":{"sort_by":"NAME_ASC"}}"""
            "GET /v2/account/info" -> ACCOUNT_RESPONSE
            "GET /v2/files/list" -> """{"status":"OK","parent":null,"files":[$FILE],"cursor":null}"""
            "GET /v2/files/42" -> {
                resolutions.add(exchange.requestURI.path)
                """{"status":"OK","file":$FILE}"""
            }
            "GET /v2/files/42/subtitles" -> """{"status":"OK","subtitles":[]}"""
            "GET /v2/transfers/list" -> """{"status":"OK","transfers":[]}"""
            else -> null
        }

    override fun close() {
        releaseMedia.countDown()
        try {
            server.stop(0)
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(2, TimeUnit.SECONDS)) { "HTTP fixture threads did not stop" }
        }
    }

    private companion object {
        const val FILE = """{
            "id":42,"name":"episode.mkv","parent_id":0,"size":42,
            "created_at":"2026-09-01T00:00:00Z","file_type":"VIDEO",
            "is_mp4_available":true,"need_convert":false,"start_from":0
        }"""
        const val ACCOUNT_RESPONSE = """{
            "status":"OK","info":{
                "user_id":42,"username":"test-user","mail":"test@example.invalid",
                "avatar_url":"","account_status":"active","download_token":"synthetic-media-token",
                "disk":{"avail":90,"size":100,"used":10},"settings":{"sort_by":"NAME_ASC"}
            }
        }"""
    }
}
