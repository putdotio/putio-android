package io.putdotio.android.telemetry

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.JsonSerializer
import io.sentry.Scope
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.App
import io.sentry.protocol.Contexts
import io.sentry.protocol.DebugImage
import io.sentry.protocol.DebugMeta
import io.sentry.protocol.Device
import io.sentry.protocol.Geo
import io.sentry.protocol.Mechanism
import io.sentry.protocol.Message
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.Request
import io.sentry.protocol.Response
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryThread
import io.sentry.protocol.User
import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryBoundaryTest {
    @Test
    fun anEventLeavesWithItsAllowlistedFieldsOnly() {
        val options = configured(Filter(isCapturing = true))

        val sent = checkNotNull(options.beforeSend?.execute(leakyEvent(), Hint()))

        val json = StringWriter().also { JsonSerializer(SentryOptions()).serialize(sent, it) }.toString()
        assertEquals(json, emptyList<String>(), LEAKS.filter(json::contains))
        assertTrue(Scope(options).apply { addBreadcrumb(leakyBreadcrumb()) }.breadcrumbs.isEmpty())
        assertEquals(SentryLevel.FATAL, sent.level)
        assertEquals("io.put.putio@0.1.0+1", sent.release)
        assertEquals("installation", sent.user?.id)
        assertEquals(mapOf("surface" to "tv"), sent.tags)
        assertEquals("IOException", sent.exceptions?.single()?.type)
        assertEquals("play", sent.exceptions?.single()?.stacktrace?.frames?.single()?.function)
        assertEquals("Pixel", sent.contexts.device?.model)
        assertEquals("16", sent.contexts.operatingSystem?.version)
    }

    @Test
    fun aReportedFailureLeavesAsItsCodeAndTags() {
        val failure = TelemetryFailure(
            "playback_failure",
            listOf("network", RELEASE_NAME),
            mapOf(TelemetryTag.Player to "media3", TelemetryTag.FileId to TITLE),
        )
        val hint = Hint().apply { set(SentryBoundary.FAILURE_HINT, failure) }

        val sent = checkNotNull(configured(Filter(isCapturing = true)).beforeSend?.execute(SentryEvent(), hint))

        assertEquals("playback_failure", sent.exceptions?.single()?.type)
        assertEquals("network, unknown", sent.exceptions?.single()?.value)
        assertEquals(mapOf("surface" to "tv", "player" to "media3", "file_id" to "unknown"), sent.tags)
    }

    @Test
    fun nothingLeavesWhileOffOrFromBeforeTheCurrentOnPeriod() {
        val off = configured(Filter(isCapturing = false))
        assertNull(off.beforeSend?.execute(SentryEvent(), Hint()))
        assertFalse("cached envelopes and sessions would upload", off.transportGate.isConnected)

        val on = configured(Filter(isCapturing = true, since = 2_000))
        assertNull(on.beforeSend?.execute(SentryEvent(Date(1_999)), Hint()))
        assertNotNull(on.beforeSend?.execute(SentryEvent(Date(2_000)), Hint()))
        assertTrue(on.transportGate.isConnected)
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

    /** Every field an app, integration, or cached scope can fill carries [LEAK]; the rest hold real-looking values. */
    @Suppress("DEPRECATION") // User.name is deprecated but still sent.
    private fun leakyEvent(): SentryEvent =
        SentryEvent().apply {
            level = SentryLevel.FATAL
            release = "io.put.putio@0.1.0+1"
            message = Message().apply {
                formatted = LEAK
                message = LEAK
                params = listOf(LEAK)
            }
            logger = LEAK
            serverName = LEAK
            transaction = LEAK
            fingerprints = listOf(LEAK)
            setModule(LEAK, LEAK)
            tags = mapOf("surface" to LEAK, TelemetryTag.FileId.key to LEAK, LEAK to LEAK)
            setExtras(mapOf(LEAK to LEAK))
            user = User().apply {
                id = "installation"
                email = LEAK
                username = LEAK
                name = LEAK
                ipAddress = LEAK
                data = mapOf(LEAK to LEAK)
                geo = Geo().apply { city = LEAK }
            }
            request = Request().apply {
                url = LEAK
                queryString = LEAK
                fragment = LEAK
                cookies = LEAK
                headers = mapOf(LEAK to LEAK)
                data = LEAK
            }
            fillLeakyContexts(contexts)
            exceptions = listOf(leakyException())
            threads = listOf(
                SentryThread().apply {
                    name = LEAK
                    stacktrace = leakyStacktrace()
                },
            )
            debugMeta = DebugMeta().apply { images = listOf(DebugImage().apply { codeFile = LEAK }) }
            breadcrumbs = listOf(leakyBreadcrumb())
            unknown = mapOf(LEAK to LEAK)
        }

    private fun fillLeakyContexts(contexts: Contexts) {
        contexts.setApp(
            App().apply {
                inForeground = true
                appName = LEAK
                viewNames = listOf(LEAK)
                unknown = mapOf(LEAK to LEAK)
            },
        )
        contexts.setDevice(
            Device().apply {
                model = "Pixel"
                name = LEAK
                unknown = mapOf(LEAK to LEAK)
            },
        )
        contexts.setOperatingSystem(
            OperatingSystem().apply {
                name = "Android"
                version = "16"
                rawDescription = LEAK
                unknown = mapOf(LEAK to LEAK)
            },
        )
        contexts.setResponse(Response().apply { headers = mapOf(LEAK to LEAK) })
        contexts.put(LEAK, LEAK)
    }

    private fun leakyException(): SentryException =
        SentryException().apply {
            type = "IOException"
            module = "java.io"
            value = LEAK
            unknown = mapOf(LEAK to LEAK)
            mechanism = Mechanism().apply {
                type = "UncaughtExceptionHandler"
                isHandled = false
                description = LEAK
                helpLink = LEAK
                data = mapOf(LEAK to LEAK)
                meta = mapOf(LEAK to LEAK)
            }
            stacktrace = leakyStacktrace()
        }

    private fun leakyStacktrace(): SentryStackTrace =
        SentryStackTrace(
            listOf(
                SentryStackFrame().apply {
                    module = "io.putdotio.android.playback.PlaybackController"
                    function = "play"
                    lineno = 42
                    filename = LEAK
                    absPath = LEAK
                    contextLine = LEAK
                    preContext = listOf(LEAK)
                    vars = mapOf(LEAK to LEAK)
                },
            ),
        ).apply { unknown = mapOf(LEAK to LEAK) }

    private fun leakyBreadcrumb(): Breadcrumb =
        Breadcrumb().apply {
            category = LEAK
            message = LEAK
            setData(LEAK, LEAK)
        }

    private companion object {
        const val TITLE = "Synthetic Feature Title"
        const val FILE_NAME = "Synthetic.Feature.Title.2001.mkv"
        const val RELEASE_NAME = "synthetic.feature.title.2001.1080p.web-dl"
        const val TOKEN = "synthetic-token-5f1c9e"
        const val HOST = "media.example.invalid"
        const val LEAK = "$TITLE https://$HOST/hls/$FILE_NAME?oauth_token=$TOKEN"
        val LEAKS = listOf(TITLE, FILE_NAME, TOKEN, HOST)
    }
}
