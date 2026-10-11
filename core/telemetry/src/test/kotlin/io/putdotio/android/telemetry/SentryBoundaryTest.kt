package io.putdotio.android.telemetry

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.JsonObjectReader
import io.sentry.JsonSerializer
import io.sentry.SentryBaseEvent
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.App
import io.sentry.protocol.Contexts
import io.sentry.protocol.Device
import io.sentry.protocol.Geo
import io.sentry.protocol.Gpu
import io.sentry.protocol.Mechanism
import io.sentry.protocol.Message
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.Request
import io.sentry.protocol.Response
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryRuntime
import io.sentry.protocol.SentryThread
import io.sentry.protocol.User
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryBoundaryTest {
    /**
     * Every field of the Sentry models the boundary rewrites or lets through, as reviewed in
     * `SentryBoundary.redact`. A Sentry upgrade that adds one fails here until `redact`
     * handles it and the field is added to this list.
     */
    private val reviewedFields: Map<Class<*>, Set<String>> = mapOf(
        SentryBaseEvent::class.java to setOf(
            "breadcrumbs", "contexts", "debugMeta", "dist", "environment", "eventId", "extra", "platform",
            "release", "request", "sdk", "serverName", "tags", "throwable", "user",
        ),
        SentryEvent::class.java to setOf(
            "exception", "fingerprint", "level", "logger", "message", "modules", "threads", "timestamp",
            "transaction", "unknown",
        ),
        Breadcrumb::class.java to setOf(
            "category", "data", "level", "message", "nanos", "origin", "timestamp", "timestampMs", "type", "unknown",
        ),
        SentryException::class.java to setOf(
            "mechanism", "module", "stacktrace", "threadId", "type", "unknown", "value",
        ),
        Mechanism::class.java to setOf(
            "data", "description", "exceptionGroup", "exceptionId", "handled", "helpLink", "meta", "parentId",
            "synthetic", "thread", "type", "unknown",
        ),
        Message::class.java to setOf("formatted", "message", "params", "unknown"),
        Request::class.java to setOf(
            "apiTarget", "bodySize", "cookies", "data", "env", "fragment", "headers", "method", "other",
            "queryString", "unknown", "url",
        ),
        User::class.java to setOf("data", "email", "geo", "id", "ipAddress", "name", "unknown", "username"),
        Contexts::class.java to setOf("internalStorage", "responseLock"),
        App::class.java to setOf(
            "appBuild", "appIdentifier", "appName", "appStartTime", "appVersion", "buildType", "deviceAppHash",
            "inForeground", "isSplitApks", "permissions", "splitNames", "startType", "unknown", "viewNames",
        ),
        Device::class.java to setOf(
            "archs", "batteryLevel", "batteryTemperature", "bootTime", "brand", "charging", "chipset",
            "connectionEffectiveType", "connectionType", "cpuDescription", "externalFreeStorage",
            "externalStorageSize", "family", "freeMemory", "freeStorage", "id", "locale", "lowMemory",
            "manufacturer", "memorySize", "model", "modelId", "name", "online", "orientation", "processorCount",
            "processorFrequency", "screenDensity", "screenDpi", "screenHeightPixels", "screenWidthPixels",
            "simulator", "storageSize", "timezone", "unknown", "usableMemory",
        ),
        OperatingSystem::class.java to setOf(
            "build", "kernelVersion", "name", "rawDescription", "rooted", "unknown", "version",
        ),
        SentryRuntime::class.java to setOf("name", "rawDescription", "unknown", "version"),
        Gpu::class.java to setOf(
            "apiType", "id", "memorySize", "multiThreadedRendering", "name", "npotSupport", "unknown", "vendorId",
            "vendorName", "version",
        ),
    )

    @Test
    fun everyReviewedSentryModelFieldIsHandledByTheBoundary() {
        reviewedFields.forEach { (type, reviewed) ->
            val fields = type.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .map { it.name }
                .toSet()
            assertEquals(
                "${type.simpleName} changed shape; handle the new field in SentryBoundary.redact, " +
                    "then update this list",
                reviewed,
                fields,
            )
        }
    }

    @Test
    fun aRedactedEventLeaksNoSyntheticSecretInAnySerializedField() {
        val event = SentryBoundary.redact(leakyEvent())
        val strings = serializedStrings(event)

        for (leak in Synthetic.leaks) {
            val hits = strings.filter { it.contains(leak) }
            assertTrue("$leak survived redaction in $hits", hits.isEmpty())
        }
        assertEquals(
            mapOf("surface" to "tv", "telemetry_event" to "playback_failure", "isSideLoaded" to "true"),
            event.tags,
        )
        assertEquals(listOf("playback_failure", "[url:media.example.invalid]"), event.fingerprints)
        assertEquals("Playback failed for [url:media.example.invalid]", event.message?.formatted)
        assertEquals(setOf("app", "device", "os"), event.contexts.keys().toList().toSet())
        assertEquals("Android", event.contexts.operatingSystem?.name)
        assertEquals("synthetic-installation", event.user?.id)
        assertEquals("IOException", event.exceptions?.single()?.type)
        assertEquals("[url:media.example.invalid]", event.breadcrumbs?.single()?.getData("url"))
        assertNull(event.request)
        assertNull(event.extras)
    }

    @Test
    fun configuredCallbacksSendOnlyRedactedEventsAndBreadcrumbsWhileCapturing() {
        val options = configured(Filter(isCapturing = true))

        val breadcrumb = checkNotNull(options.beforeBreadcrumb?.execute(leakyBreadcrumb(), Hint()))
        val event = checkNotNull(options.beforeSend?.execute(leakyEvent(), Hint()))

        assertFalse(options.dataCollection.isExplicitlyConfigured)
        assertEquals("https://synthetickey@relay.put.io/1", options.dsn)
        assertEquals("tv", options.tags["surface"])
        assertTrue(options.transportGate.isConnected)
        for (leak in Synthetic.leaks) {
            assertFalse(leak, serializedStrings(breadcrumb).any { it.contains(leak) })
            assertFalse(leak, serializedStrings(event).any { it.contains(leak) })
        }
    }

    @Test
    fun whileDiagnosticsAreOffNothingIsRecordedOrUploaded() {
        val options = configured(Filter(isCapturing = false))

        assertNull(options.beforeBreadcrumb?.execute(leakyBreadcrumb(), Hint()))
        assertNull(options.beforeSend?.execute(leakyEvent(), Hint()))
        assertFalse("cached envelopes and sessions would upload", options.transportGate.isConnected)
    }

    @Test
    fun aReportFromBeforeTheCurrentOnPeriodIsDropped() {
        val options = configured(Filter(isCapturing = true, since = 2_000))

        assertNull(options.beforeSend?.execute(SentryEvent(Date(1_999)), Hint()))
        assertNotNull(options.beforeSend?.execute(SentryEvent(Date(2_000)), Hint()))
    }

    @Test
    fun aReportedFailureBecomesAMessageEventWithItsIdentifiersOnly() {
        val failure = TelemetryFailure(
            "playback_failure",
            listOf("network", "io_network_connection_failed"),
            mapOf(TelemetryTag.Player to "media3", TelemetryTag.FileId to "42"),
        )

        val event = SentryBoundary.redact(SentryBoundary.event(failure))

        assertEquals(SentryLevel.ERROR, event.level)
        assertEquals("playback_failure (network, io_network_connection_failed)", event.message?.formatted)
        assertEquals(listOf("playback_failure", "network", "io_network_connection_failed"), event.fingerprints)
        assertEquals(mapOf("player" to "media3", "file_id" to "42"), event.tags)
        assertNull(event.exceptions)
    }

    @Test
    fun purgingDeletesEverythingWaitingForUpload() {
        val cacheDir = Files.createTempDirectory("diagnostics").toFile()
        try {
            val pending = File(diagnosticsCacheDir(cacheDir), "dsnhash/outbox/pending").apply { parentFile?.mkdirs() }
            pending.writeText("synthetic")
            val sdkDefault = File(cacheDir, "sentry/envelope").apply { parentFile?.mkdirs() }
            sdkDefault.writeText("synthetic")
            val unrelated = File(cacheDir, "media").apply { writeText("kept") }

            purgeDiagnosticsCache(cacheDir)

            assertFalse(diagnosticsCacheDir(cacheDir).exists())
            assertFalse(sdkDefault.parentFile?.exists() == true)
            assertTrue(unrelated.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun configured(filter: DiagnosticsFilter): SentryOptions =
        SentryOptions().also { options ->
            val configuration = checkNotNull(
                SentryConfiguration.of("https://synthetickey@o0.ingest.example.invalid/1", "nightly", "r", "tv"),
            )
            SentryBoundary.configure(options, configuration, filter, "/nonexistent")
        }

    private class Filter(override val isCapturing: Boolean, private val since: Long = 0) : DiagnosticsFilter {
        override fun admits(timestampMillis: Long): Boolean = isCapturing && timestampMillis >= since
    }

    private fun leakyEvent(): SentryEvent =
        SentryEvent().apply {
            level = SentryLevel.ERROR
            message = Message().apply {
                formatted = "Playback failed for ${Synthetic.SIGNED_URL}"
                message = "Opening ${Synthetic.FILE_PATH}"
                params = listOf(Synthetic.SIGNED_URL, "Bearer ${Synthetic.TOKEN}")
                unknown = mapOf("title" to Synthetic.TITLE)
            }
            logger = "player ${Synthetic.SIGNED_URL}"
            serverName = "host-${Synthetic.TOKEN}"
            release = "io.put.synthetic@1.0"
            transaction = "PlayerScreen ${Synthetic.FILE_NAME}"
            tags = mapOf(
                "surface" to "tv",
                "telemetry_event" to "playback_failure",
                "isSideLoaded" to "true",
                "media_title" to Synthetic.TITLE,
                "url" to Synthetic.SIGNED_URL,
            )
            setExtras(mapOf("nested" to mapOf("title" to Synthetic.TITLE, "urls" to listOf(Synthetic.SIGNED_URL))))
            fillLeakyContexts(contexts)
            fingerprints = listOf("playback_failure", Synthetic.SIGNED_URL)
            user = leakyUser()
            request = Request().apply {
                url = Synthetic.SIGNED_URL
                queryString = "oauth_token=${Synthetic.TOKEN}"
                fragment = Synthetic.TOKEN
                headers = mapOf("Authorization" to "Bearer ${Synthetic.TOKEN}")
                cookies = "session=${Synthetic.TOKEN}"
                data = mapOf("title" to Synthetic.TITLE)
            }
            exceptions = listOf(leakyException())
            threads = listOf(
                SentryThread().apply {
                    name = "download ${Synthetic.FILE_NAME}"
                    unknown = mapOf("t" to Synthetic.TITLE)
                },
            )
            breadcrumbs = listOf(leakyBreadcrumb())
            unknown = mapOf("title" to Synthetic.TITLE)
        }

    private fun fillLeakyContexts(contexts: Contexts) {
        contexts.setOperatingSystem(
            OperatingSystem().apply {
                name = "Android"
                unknown = mapOf("x" to Synthetic.TOKEN)
            },
        )
        contexts.setApp(
            App().apply {
                viewNames = listOf("MainActivity ${Synthetic.FILE_NAME}")
                unknown = mapOf("t" to Synthetic.TITLE)
            },
        )
        contexts.setDevice(
            Device().apply {
                name = Synthetic.TITLE
                model = "Synthetic Model"
                unknown = mapOf("e" to Synthetic.EMAIL)
            },
        )
        contexts.setResponse(Response().apply { headers = mapOf("Authorization" to "Bearer ${Synthetic.TOKEN}") })
        contexts.put("file", mapOf("title" to Synthetic.TITLE, "path" to Synthetic.FILE_PATH))
    }

    // `User.name` is deprecated but still serialized, so the fixture fills it.
    @Suppress("DEPRECATION")
    private fun leakyUser(): User =
        User().apply {
            id = "synthetic-installation"
            email = Synthetic.EMAIL
            username = Synthetic.TITLE
            name = Synthetic.TITLE
            ipAddress = Synthetic.IP_ADDRESS
            data = mapOf("token" to Synthetic.TOKEN)
            geo = Geo().apply { city = Synthetic.TITLE }
        }

    private fun leakyException(): SentryException =
        SentryException().apply {
            type = "IOException"
            value = "Cannot open ${Synthetic.FILE_PATH} at ${Synthetic.SIGNED_URL}"
            module = "io.putdotio ${Synthetic.FILE_NAME}"
            unknown = mapOf("title" to Synthetic.TITLE)
            mechanism = Mechanism().apply {
                type = "generic"
                description = "token=${Synthetic.TOKEN}"
                helpLink = Synthetic.SIGNED_URL
                data = mapOf("url" to Synthetic.SIGNED_URL, "title" to Synthetic.TITLE)
                meta = mapOf("detail" to Synthetic.FILE_PATH)
                unknown = mapOf("title" to Synthetic.TITLE)
            }
        }

    private fun leakyBreadcrumb(): Breadcrumb =
        Breadcrumb().apply {
            category = "http ${Synthetic.FILE_NAME}"
            message = "GET ${Synthetic.SIGNED_URL}"
            level = SentryLevel.INFO
            setData("url", Synthetic.SIGNED_URL)
            setData("http.query", "oauth_token=${Synthetic.TOKEN}")
            setData("title", Synthetic.TITLE)
            setData("nested", mapOf("file_name" to Synthetic.FILE_NAME, "paths" to listOf(Synthetic.FILE_PATH)))
            unknown = mapOf("title" to Synthetic.TITLE)
        }

    /** Every key and value of [entity] as Sentry would send it. */
    private fun serializedStrings(entity: Any): List<String> {
        val json = StringWriter().also { JsonSerializer(SentryOptions()).serialize(entity, it) }.toString()
        return strings(JsonObjectReader(StringReader(json)).nextObjectOrNull())
    }

    private fun strings(value: Any?): List<String> =
        when (value) {
            is String -> listOf(value)
            is Map<*, *> -> value.flatMap { (key, item) -> listOf(key.toString()) + strings(item) }
            is List<*> -> value.flatMap(::strings)
            else -> emptyList()
        }
}
