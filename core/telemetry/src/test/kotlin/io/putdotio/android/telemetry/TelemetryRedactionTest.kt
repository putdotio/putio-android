package io.putdotio.android.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic placeholders only; never paste real events, URLs, or filenames. */
internal object Synthetic {
    const val TOKEN = "synthetic-token-5f1c9e"
    const val SIGNATURE = "synthetic-sig-2b7d40"
    const val TITLE = "Synthetic Feature Title"
    const val FILE_NAME = "Synthetic.Feature.Title.2001.mkv"
    const val EMAIL = "synthetic@example.invalid"
    const val IP_ADDRESS = "192.0.2.1"
    const val SIGNED_URL =
        "https://media.example.invalid/hls/$FILE_NAME/master.m3u8?oauth_token=$TOKEN&signature=$SIGNATURE"
    const val FILE_PATH = "/data/user/0/io.put.putio/files/downloads/$FILE_NAME"

    /** Substrings no sent field may contain. */
    val leaks = listOf(
        TOKEN, SIGNATURE, TITLE, FILE_NAME, "Synthetic.Feature", "/hls/", "://media.example.invalid",
        "files/downloads", EMAIL, IP_ADDRESS,
    )
}

class TelemetryRedactionTest {
    @Test
    fun urlsKeepOnlyTheirHost() {
        assertEquals(
            "Playback failed for [url:media.example.invalid] twice",
            TelemetryRedaction.scrub("Playback failed for ${Synthetic.SIGNED_URL} twice"),
        )
        assertEquals(TelemetryRedaction.PATH, TelemetryRedaction.scrub("file://${Synthetic.FILE_PATH}"))
        assertEquals("[url:example.invalid]", TelemetryRedaction.scrub("https://user:pass@example.invalid"))
        assertEquals(
            "[url:io.put.putio.documents]",
            TelemetryRedaction.scrub("content://io.put.putio.documents/document/12"),
        )
    }

    @Test
    fun credentialsPathsAndMediaFilenamesAreRemoved() {
        val token = Synthetic.TOKEN
        assertEquals("Authorization: [redacted] [redacted]", TelemetryRedaction.scrub("Authorization: Bearer $token"))
        assertEquals("?oauth_token=[redacted]&page=2", TelemetryRedaction.scrub("?oauth_token=$token&page=2"))
        assertEquals("""{"password": [redacted]}""", TelemetryRedaction.scrub("""{"password": "$token"}"""))
        assertEquals("Cannot delete [path]", TelemetryRedaction.scrub("Cannot delete ${Synthetic.FILE_PATH}"))
        assertEquals("Opened [file] twice", TelemetryRedaction.scrub("Opened ${Synthetic.FILE_NAME} twice"))
        assertEquals(
            "java.net.SocketTimeoutException: timeout",
            TelemetryRedaction.scrub("java.net.SocketTimeoutException: timeout"),
        )
    }

    @Test
    fun sensitiveKeysLoseTheirValueAtAnyDepthAndUnknownObjectsAreDropped() {
        val scrubbed = TelemetryRedaction.scrubMap(
            mapOf(
                "player" to "media3",
                "status_code" to 403,
                "nested" to mapOf(
                    "title" to Synthetic.TITLE,
                    "items" to listOf(mapOf("file_name" to Synthetic.FILE_NAME, "url" to Synthetic.SIGNED_URL)),
                ),
                "object" to Any(),
            ),
        )

        assertEquals("media3", scrubbed["player"])
        assertEquals(403, scrubbed["status_code"])
        assertEquals(TelemetryRedaction.REDACTED, scrubbed["object"])
        val nested = scrubbed["nested"] as Map<*, *>
        assertEquals(TelemetryRedaction.REDACTED, nested["title"])
        val item = (nested["items"] as List<*>).single() as Map<*, *>
        assertEquals(TelemetryRedaction.REDACTED, item["file_name"])
        assertEquals("[url:media.example.invalid]", item["url"])
    }

    @Test
    fun reportedFailuresCarryOnlyIdentifiers() {
        val failure = TelemetryFailure(
            event = "playback_failure",
            reasons = listOf("network", "io_network_connection_failed"),
            tags = mapOf(
                TelemetryTag.Player to "media3",
                TelemetryTag.FileId to "12345",
                TelemetryTag.SourceKind to Synthetic.SIGNED_URL,
                TelemetryTag.MediaType to Synthetic.TITLE,
            ),
        )

        assertEquals("playback_failure (network, io_network_connection_failed)", failure.message)
        assertEquals(listOf("playback_failure", "network", "io_network_connection_failed"), failure.fingerprint)
        assertEquals(
            mapOf("player" to "media3", "file_id" to "12345", "source_kind" to "unknown", "media_type" to "unknown"),
            failure.tags,
        )
    }
}
