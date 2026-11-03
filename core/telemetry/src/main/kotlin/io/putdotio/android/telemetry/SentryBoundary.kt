package io.putdotio.android.telemetry

import android.content.Context
import io.sentry.Hint
import io.sentry.IConnectionStatusProvider.ConnectionStatus
import io.sentry.IConnectionStatusProvider.IConnectionStatusObserver
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
import java.util.UUID
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

    /**
     * One uninterrupted on-period. Each SDK checks its own, so one still closing never reopens. A
     * launch period keeps envelopes on the device until the account confirms diagnostics.
     */
    class Period(val since: Long, confirmed: Boolean) {
        @Volatile
        var confirmed: Boolean = confirmed
            private set

        val isCurrent: Boolean get() = current === this
        val sends: Boolean get() = isCurrent && confirmed

        fun confirm() {
            confirmed = true
        }
    }

    // A start after launch drops events stamped before it: Android hands the SDK ANRs from its exit
    // history, which can date from an opted-out period, and Sentry marks a dropped one as reported.
    fun open(launch: Boolean): Period =
        Period(if (launch) 0 else System.currentTimeMillis(), confirmed = !launch).also { current = it }

    fun start(context: Context, configuration: SentryConfiguration, launch: Boolean) {
        val period = open(launch)
        val files = DiagnosticsFiles(context)
        val init = { SentryAndroid.init(context) { configure(it, configuration, period, files) } }
        // At launch on the caller's thread, so the crash handler is in place before the first screen.
        if (launch) runCatching(init) else executor.submit(init)
    }

    /** Opens the launch period's transport and sends what it kept, as Sentry does when a network returns. */
    fun confirm() {
        val period = current?.takeUnless { it.confirmed } ?: return
        period.confirm()
        executor.submit {
            val options = Sentry.getCurrentScopes().options
            val status = options.connectionStatusProvider.connectionStatus
            options.integrations.filterIsInstance<IConnectionStatusObserver>().forEach {
                it.onConnectionStatusChanged(status)
            }
        }
    }

    fun stop(files: DiagnosticsFiles): Future<*> {
        current = null
        executor.submit { Sentry.close() }
        return executor.submit { files.delete() }
    }

    fun capture(failure: TelemetryFailure) {
        if (current == null) return
        val hint = Hint().apply { set(FAILURE_HINT, failure) }
        executor.submit { Sentry.captureEvent(SentryEvent().apply { level = SentryLevel.ERROR }, hint) }
    }

    fun configure(options: SentryOptions, configuration: SentryConfiguration, period: Period, files: DiagnosticsFiles) {
        options.dsn = configuration.dsn
        options.environment = configuration.environment
        options.cacheDirPath = files.cache.path
        options.maxBreadcrumbs = 0
        // Release-health sessions skip beforeSend and carry Sentry's own installation id.
        options.isEnableAutoSessionTracking = false
        // Cached envelopes upload without passing beforeSend.
        options.setTransportGate {
            period.sends && options.connectionStatusProvider.connectionStatus != ConnectionStatus.DISCONNECTED
        }
        val installation = lazy(files::installationId)
        options.setBeforeSend { event, hint ->
            // Sentry sends the hint's attachments with whatever event this returns.
            hint.clearAttachments()
            hint.screenshot = null
            hint.viewHierarchy = null
            hint.threadDump = null
            hint.tombstone = null
            val failure = hint.getAs(FAILURE_HINT, TelemetryFailure::class.java)
            val admitted = period.isCurrent && event.timestamp.time >= period.since
            if (admitted) allowlisted(event, installation.value, configuration.surface, failure) else null
        }
    }
}

/**
 * What diagnostics keep on the device: Sentry's cache, Sentry's installation id, and the id events
 * carry. That one is ours, since Sentry holds its own in memory for the life of the process.
 */
internal class DiagnosticsFiles(val cache: File, val sentryInstallation: File, val installation: File) {
    constructor(context: Context) : this(
        File(context.cacheDir, "io.put.diagnostics"),
        File(context.filesDir, "INSTALLATION"),
        File(context.noBackupFilesDir, "io.put.diagnostics.installation"),
    )

    @Synchronized
    fun installationId(): String =
        installation.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { installation.writeText(it) }

    @Synchronized
    fun delete() {
        cache.deleteRecursively()
        sentryInstallation.delete()
        installation.delete()
    }
}

/**
 * Copies listed fields into a new event, so unlisted ones, including any a Sentry upgrade adds,
 * stay on the device. A throwable's message can quote a media title, so exceptions keep their type.
 */
private fun allowlisted(event: SentryEvent, installation: String, surface: String, failure: TelemetryFailure?) =
    SentryEvent(event.timestamp).apply {
        eventId = event.eventId
        level = event.level
        platform = event.platform
        release = event.release
        environment = event.environment
        dist = event.dist
        sdk = event.sdk?.let { SdkVersion(it.name, it.version) }
        user = User().apply { id = installation }
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
