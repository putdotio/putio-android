package io.putdotio.android.telemetry

import io.sentry.Attachment
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
import io.sentry.protocol.Mechanism
import io.sentry.protocol.Message
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.Request
import io.sentry.protocol.SdkVersion
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryBoundaryTest {
    @Test
    fun onlyAllowlistedFieldsLeaveTheDevice() {
        val files = files()
        val options = configured(SentryBoundary.open(launch = true), files)
        val attachment = Attachment(LEAK.toByteArray(), LEAK)
        val hint = Hint.withAttachment(attachment).apply {
            screenshot = attachment
            viewHierarchy = attachment
            threadDump = attachment
            tombstone = attachment
        }

        val sent = checkNotNull(options.beforeSend?.execute(leakyEvent(), hint))

        val json = StringWriter().also { JsonSerializer(SentryOptions()).serialize(sent, it) }.toString()
        assertEquals(json, emptyList<String>(), LEAKS.filter(json::contains))
        val attachments = listOfNotNull(hint.screenshot, hint.viewHierarchy, hint.threadDump, hint.tombstone)
        assertEquals(emptyList<Attachment>(), hint.attachments + attachments)
        assertTrue(Scope(options).apply { addBreadcrumb(Breadcrumb(LEAK)) }.breadcrumbs.isEmpty())
        assertEquals(SentryLevel.FATAL, sent.level)
        assertEquals("io.put.putio@0.1.0+1", sent.release)
        assertEquals(files.installation.readText(), sent.user?.id)
        assertEquals(mapOf("surface" to "tv"), sent.tags)
        assertEquals("IOException", sent.exceptions?.single()?.type)
        assertEquals("play", sent.exceptions?.single()?.stacktrace?.frames?.single()?.function)
        assertEquals("Pixel", sent.contexts.device?.model)
        assertEquals("16", sent.contexts.operatingSystem?.version)
        assertEquals("r8-mapping", sent.debugMeta?.images?.single()?.uuid)
    }

    @Test
    fun aNativeCrashKeepsOnlyWhatSymbolicatesIt() {
        val options = configured(SentryBoundary.open(launch = true), files())

        val sent = checkNotNull(options.beforeSend?.execute(nativeCrash(), Hint()))

        val json = StringWriter().also { JsonSerializer(SentryOptions()).serialize(sent, it) }.toString()
        assertEquals(json, emptyList<String>(), (LEAKS + INSTALL_PATH).filter(json::contains))
        val exception = checkNotNull(sent.exceptions?.single())
        assertEquals("SIGSEGV", exception.type)
        assertNull(exception.value)
        assertEquals("signalhandler", exception.mechanism?.type)
        val frame = checkNotNull(exception.stacktrace?.frames?.single())
        assertEquals("0x7a1c2b3f40", frame.instructionAddr)
        assertEquals("libavcodec.so", frame.`package`)
        val image = checkNotNull(sent.debugMeta?.images?.single())
        assertEquals("elf", image.type)
        assertEquals("1f2e3d4c-5b6a-7980-1a2b-3c4d5e6f7a8b", image.debugId)
        assertEquals("4c3d2e1f6a5b8079", image.codeId)
        assertEquals("arm64", image.arch)
        assertEquals("0x7a1c000000", image.imageAddr)
        assertEquals(5_679_760L, image.imageSize)
        assertEquals("libavcodec.so", image.codeFile)
    }

    @Test
    fun aReportedFailureLeavesAsCodes() {
        val failure = TelemetryFailure("playback_failure", listOf("network", RELEASE_NAME), mapOf("file_id" to TITLE))
        val hint = Hint().apply { set(SentryBoundary.FAILURE_HINT, failure) }
        val options = configured(SentryBoundary.open(launch = true), files())

        val sent = checkNotNull(options.beforeSend?.execute(SentryEvent(), hint))

        assertEquals("playback_failure", sent.exceptions?.single()?.type)
        assertEquals("network, unknown", sent.exceptions?.single()?.value)
        assertEquals(mapOf("surface" to "tv", "file_id" to "unknown"), sent.tags)
    }

    @Test
    fun switchingOffSendsNothingRecordedBefore() {
        val files = files()
        val pending = File(files.cache, "outbox/pending").apply { parentFile?.mkdirs() }
        pending.writeText("synthetic")
        val closing = configured(SentryBoundary.open(launch = false), files)
        assertNotNull(closing.beforeSend?.execute(SentryEvent(), Hint()))
        assertTrue(closing.transportGate.isConnected)

        SentryBoundary.stop(files).get()
        assertFalse("an opt-out left the transport open", closing.transportGate.isConnected)
        assertFalse("pending envelopes would upload at the next start", pending.exists())
        val reopened = configured(SentryBoundary.open(launch = false), files)

        assertNull(closing.beforeSend?.execute(SentryEvent(), Hint()))
        assertFalse("the closing SDK's transport reopened", closing.transportGate.isConnected)
        assertNull("an ANR from the opted-out period left", reopened.beforeSend?.execute(SentryEvent(Date(0)), Hint()))
        assertNotNull(reopened.beforeSend?.execute(SentryEvent(), Hint()))
    }

    @Test
    fun aLaunchKeepsEnvelopesOnTheDeviceUntilTheAccountAnswers() {
        val launch = configured(SentryBoundary.open(launch = true), files())

        assertNotNull("a crash before the answer was lost", launch.beforeSend?.execute(SentryEvent(), Hint()))
        assertFalse("a launch sent before the account answered", launch.transportGate.isConnected)

        SentryBoundary.confirm()
        assertTrue("an answer of on kept the transport closed", launch.transportGate.isConnected)
    }

    @Test
    fun anOptOutForgetsTheInstallation() {
        val files = files()
        files.sentryInstallation.writeText("sentry-installation")
        val before = checkNotNull(configured(SentryBoundary.open(launch = false), files).beforeSend)
            .execute(SentryEvent(), Hint())?.user?.id

        SentryBoundary.stop(files).get()
        val after = checkNotNull(configured(SentryBoundary.open(launch = false), files).beforeSend)
            .execute(SentryEvent(), Hint())?.user?.id

        assertFalse("Sentry's installation id outlived the opt-out", files.sentryInstallation.exists())
        assertNotNull(before)
        assertNotNull(after)
        assertNotEquals("an opt-in reused the opted-out installation id", before, after)
    }

    @Test
    fun anUnwritableInstallationIdStillSendsTheReportWithoutIt() {
        val files = files()
        files.installation.mkdirs()
        val options = configured(SentryBoundary.open(launch = false), files)

        val sent = options.beforeSend?.execute(SentryEvent(), Hint())

        assertNotNull("an id the device could not keep dropped the report", sent)
        assertNull(sent?.user)
    }

    private fun files(): DiagnosticsFiles {
        val root = Files.createTempDirectory("diagnostics").toFile()
        return DiagnosticsFiles(File(root, "cache"), File(root, "INSTALLATION"), File(root, "id"))
    }

    private fun configured(period: SentryBoundary.Period, files: DiagnosticsFiles): SentryOptions =
        SentryOptions().also { options ->
            val configuration = SentryConfiguration.of("https://synthetickey@o0.example.invalid/1", "n", "tv")
            SentryBoundary.configure(options, checkNotNull(configuration), period, files)
        }

    /** Every unlisted field an app, integration, or cached scope can fill carries [LEAK]. */
    @Suppress("DEPRECATION") // User.name is deprecated but still sent.
    private fun leakyEvent(): SentryEvent =
        SentryEvent().apply {
            level = SentryLevel.FATAL
            release = "io.put.putio@0.1.0+1"
            sdk = SdkVersion("sentry.java.android", "8.60.0").apply { unknown = mapOf(LEAK to LEAK) }
            message = Message().apply { formatted = LEAK }
            logger = LEAK
            serverName = LEAK
            transaction = LEAK
            fingerprints = listOf(LEAK)
            setModule(LEAK, LEAK)
            tags = mapOf("surface" to LEAK, "file_id" to LEAK)
            setExtras(mapOf(LEAK to LEAK))
            user = User().apply {
                id = "installation"
                email = LEAK
                name = LEAK
                ipAddress = LEAK
            }
            request = Request().apply { url = LEAK }
            fillLeakyContexts(contexts)
            exceptions = listOf(
                SentryException().apply {
                    type = "IOException"
                    value = LEAK
                    mechanism = Mechanism().apply { description = LEAK }
                    stacktrace = leakyStacktrace()
                },
            )
            threads = listOf(SentryThread().apply { name = LEAK })
            debugMeta = DebugMeta().apply {
                images = listOf(
                    DebugImage().apply {
                        type = DebugImage.PROGUARD
                        uuid = "r8-mapping"
                        codeFile = LEAK
                    },
                    DebugImage().apply { codeFile = LEAK },
                )
            }
            breadcrumbs = listOf(Breadcrumb(LEAK))
            unknown = mapOf(LEAK to LEAK)
        }

    private fun nativeCrash(): SentryEvent =
        SentryEvent().apply {
            level = SentryLevel.FATAL
            platform = "native"
            message = Message().apply { formatted = LEAK }
            exceptions = listOf(
                SentryException().apply {
                    type = "SIGSEGV"
                    value = LEAK
                    mechanism = Mechanism().apply {
                        type = "signalhandler"
                        isHandled = false
                        meta = mapOf(LEAK to LEAK)
                    }
                    stacktrace = SentryStackTrace(
                        listOf(
                            SentryStackFrame().apply {
                                instructionAddr = "0x7a1c2b3f40"
                                `package` = "$INSTALL_PATH/lib/arm64/libavcodec.so"
                                absPath = LEAK
                            },
                        ),
                    )
                },
            )
            threads = listOf(SentryThread().apply { name = LEAK })
            debugMeta = DebugMeta().apply {
                images = listOf(
                    DebugImage().apply {
                        type = "elf"
                        debugId = "1f2e3d4c-5b6a-7980-1a2b-3c4d5e6f7a8b"
                        codeId = "4c3d2e1f6a5b8079"
                        arch = "arm64"
                        imageAddr = "0x7a1c000000"
                        imageSize = 5_679_760L
                        codeFile = "$INSTALL_PATH/lib/arm64/libavcodec.so"
                        debugFile = LEAK
                    },
                    DebugImage().apply {
                        type = "macho"
                        codeFile = LEAK
                    },
                )
            }
            fillLeakyContexts(contexts)
        }

    private fun fillLeakyContexts(contexts: Contexts) {
        contexts.setApp(App().apply { appName = LEAK })
        contexts.setDevice(
            Device().apply {
                model = "Pixel"
                name = LEAK
            },
        )
        contexts.setOperatingSystem(
            OperatingSystem().apply {
                version = "16"
                rawDescription = LEAK
            },
        )
        contexts.put(LEAK, LEAK)
    }

    private fun leakyStacktrace(): SentryStackTrace =
        SentryStackTrace(
            listOf(
                SentryStackFrame().apply {
                    function = "play"
                    absPath = LEAK
                    vars = mapOf(LEAK to LEAK)
                },
            ),
        ).apply { unknown = mapOf(LEAK to LEAK) }

    private companion object {
        const val TITLE = "Synthetic Feature Title"
        const val FILE_NAME = "Synthetic.Feature.Title.2001.mkv"
        const val RELEASE_NAME = "synthetic.feature.title.2001.1080p.web-dl"
        const val TOKEN = "synthetic-token-5f1c9e"
        const val HOST = "media.example.invalid"
        const val LEAK = "$TITLE https://$HOST/hls/$FILE_NAME?oauth_token=$TOKEN"
        val LEAKS = listOf(TITLE, FILE_NAME, TOKEN, HOST)
        const val INSTALL_PATH = "/data/app/~~c2ludGhldGlj==/io.put.putio-c3ludGhldGlj=="
    }
}
