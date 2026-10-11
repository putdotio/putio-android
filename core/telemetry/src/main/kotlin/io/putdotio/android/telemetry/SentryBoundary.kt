package io.putdotio.android.telemetry

import android.content.Context
import io.sentry.Hint
import io.sentry.IConnectionStatusProvider.ConnectionStatus
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.App
import io.sentry.protocol.Contexts
import io.sentry.protocol.DebugImage
import io.sentry.protocol.DebugMeta
import io.sentry.protocol.Device
import io.sentry.protocol.Mechanism
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SdkVersion
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal object SentryBoundary {
    const val FAILURE_HINT: String = "io.put.failure"

    // Steps after launch run in order off the caller's thread, since closing flushes. submit, not
    // execute: a step that throws is dropped instead of crashing the app.
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    var current: Period? = null
        private set

    /** One uninterrupted on-period. Each SDK checks its own, so one still closing never reopens. */
    class Period(val since: Long) {
        val isCurrent: Boolean get() = current === this
    }

    // A start after launch drops events stamped before it: Android hands the SDK ANRs from its exit
    // history, which can date from an opted-out period, and Sentry marks a dropped one as reported.
    fun open(launch: Boolean): Period = Period(if (launch) 0 else System.currentTimeMillis()).also { current = it }

    fun start(context: Context, configuration: SentryConfiguration, launch: Boolean) {
        val period = open(launch)
        val init = { SentryAndroid.init(context) { configure(it, configuration, period, cacheDir(context)) } }
        // At launch on the caller's thread, so the crash handler is in place before the first screen.
        if (launch) runCatching(init) else executor.submit(init)
    }

    fun stop(cacheDir: File): Future<*> {
        current = null
        executor.submit { Sentry.close() }
        return executor.submit { cacheDir.deleteRecursively() }
    }

    fun capture(failure: TelemetryFailure) {
        if (current == null) return
        val hint = Hint().apply { set(FAILURE_HINT, failure) }
        executor.submit { Sentry.captureEvent(SentryEvent().apply { level = SentryLevel.ERROR }, hint) }
    }

    fun cacheDir(context: Context): File = File(context.cacheDir, "io.put.diagnostics")

    fun configure(options: SentryOptions, configuration: SentryConfiguration, period: Period, cacheDir: File) {
        options.dsn = configuration.dsn
        options.environment = configuration.environment
        options.cacheDirPath = cacheDir.path
        options.maxBreadcrumbs = 0
        // Cached envelopes and sessions upload without passing beforeSend.
        options.setTransportGate {
            period.isCurrent && options.connectionStatusProvider.connectionStatus != ConnectionStatus.DISCONNECTED
        }
        options.setBeforeSend { event, hint ->
            // Sentry sends the hint's attachments with whatever event this returns.
            hint.clearAttachments()
            hint.screenshot = null
            hint.viewHierarchy = null
            hint.threadDump = null
            hint.tombstone = null
            val failure = hint.getAs(FAILURE_HINT, TelemetryFailure::class.java)
            val admitted = period.isCurrent && event.timestamp.time >= period.since
            if (admitted) allowlisted(event, configuration.surface, failure) else null
        }
    }
}

/**
 * Copies listed fields into a new event, so unlisted ones, including any a Sentry upgrade adds,
 * stay on the device. A throwable's message can quote a media title, so exceptions keep their type.
 */
private fun allowlisted(event: SentryEvent, surface: String, failure: TelemetryFailure?) =
    SentryEvent(event.timestamp).apply {
        eventId = event.eventId
        level = event.level
        platform = event.platform
        release = event.release
        environment = event.environment
        dist = event.dist
        sdk = event.sdk?.let { SdkVersion(it.name, it.version) }
        user = event.user?.id?.let { id -> User().apply { this.id = id } }
        tags = mapOf("surface" to surface) + failure?.tags.orEmpty()
        exceptions = failure?.let { listOf(it.exception()) } ?: event.exceptions?.map(::allowlisted)
        // Sentry picks the R8 mapping that deobfuscates the frames by this id.
        debugMeta = event.debugMeta?.images?.filter { it.type == DebugImage.PROGUARD }?.let { images ->
            DebugMeta().apply { this.images = images.map(::allowlisted) }
        }
        allowlisted(event.contexts, contexts)
    }

private fun allowlisted(from: Contexts, to: Contexts) {
    from.app?.let { app -> to.setApp(App().apply { inForeground = app.inForeground }) }
    from.operatingSystem?.let { os ->
        to.setOperatingSystem(
            OperatingSystem().apply {
                name = os.name
                version = os.version
            },
        )
    }
    from.device?.let { device ->
        to.setDevice(
            Device().apply {
                manufacturer = device.manufacturer
                model = device.model
            },
        )
    }
}

private fun allowlisted(exception: SentryException) =
    SentryException().apply {
        type = exception.type
        module = exception.module
        mechanism = exception.mechanism?.let { from ->
            Mechanism().apply {
                type = from.type
                isHandled = from.isHandled
            }
        }
        stacktrace = exception.stacktrace?.frames?.let { frames ->
            SentryStackTrace(
                frames.map { from ->
                    SentryStackFrame().apply {
                        module = from.module
                        function = from.function
                        lineno = from.lineno
                        isInApp = from.isInApp
                    }
                },
            )
        }
    }

private fun allowlisted(image: DebugImage) =
    DebugImage().apply {
        type = image.type
        uuid = image.uuid
    }

private fun TelemetryFailure.exception() =
    SentryException().also {
        it.type = type
        it.value = code
    }
