package io.putdotio.android.telemetry

import io.sentry.IConnectionStatusProvider.ConnectionStatus
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.App
import io.sentry.protocol.Device
import io.sentry.protocol.Mechanism
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User

/**
 * Every event leaves as a new one built by [allowed]: exception types and stack frames, release
 * metadata, the installation id, a few device facts, and the tags put.io sets. A field not copied
 * there, including any a Sentry upgrade adds, never leaves the device.
 */
internal object SentryBoundary {
    const val SURFACE_TAG: String = "surface"

    /** Carries a [TelemetryFailure] from capture to `beforeSend`; Sentry never sends hints. */
    const val FAILURE_HINT: String = "io.put.failure"

    fun configure(
        options: SentryOptions,
        configuration: SentryConfiguration,
        filter: DiagnosticsFilter,
        cacheDirPath: String,
    ) {
        options.dsn = configuration.dsn
        options.environment = configuration.environment
        options.release = configuration.release
        options.cacheDirPath = cacheDirPath
        options.maxBreadcrumbs = 0
        // Cached envelopes and sessions upload without passing beforeSend.
        options.setTransportGate {
            filter.isCapturing &&
                options.connectionStatusProvider.connectionStatus != ConnectionStatus.DISCONNECTED
        }
        options.setBeforeSend { event, hint ->
            if (filter.admits(event.timestamp.time)) {
                allowed(event, configuration.surface, hint.getAs(FAILURE_HINT, TelemetryFailure::class.java))
            } else {
                null
            }
        }
    }

    fun allowed(event: SentryEvent, surface: String, failure: TelemetryFailure?): SentryEvent =
        SentryEvent(event.timestamp).apply {
            eventId = event.eventId
            level = event.level
            platform = event.platform
            release = event.release
            environment = event.environment
            dist = event.dist
            sdk = event.sdk
            user = event.user?.id?.let { id -> User().apply { this.id = id } }
            tags = mapOf(SURFACE_TAG to surface) + failure?.tags.orEmpty()
            exceptions = if (failure == null) {
                event.exceptions?.map(::allowed)
            } else {
                listOf(
                    SentryException().apply {
                        type = failure.type
                        value = failure.code
                    },
                )
            }
            event.contexts.app?.let { app -> contexts.setApp(App().apply { inForeground = app.inForeground }) }
            event.contexts.operatingSystem?.let { os ->
                contexts.setOperatingSystem(
                    OperatingSystem().apply {
                        name = os.name
                        version = os.version
                        build = os.build
                    },
                )
            }
            event.contexts.device?.let { device ->
                contexts.setDevice(
                    Device().apply {
                        manufacturer = device.manufacturer
                        brand = device.brand
                        family = device.family
                        model = device.model
                        archs = device.archs
                        isSimulator = device.isSimulator
                        memorySize = device.memorySize
                        freeMemory = device.freeMemory
                        isLowMemory = device.isLowMemory
                    },
                )
            }
        }

    // A throwable's message can quote anything, a media title included, so its value stays behind.
    private fun allowed(exception: SentryException): SentryException =
        SentryException().apply {
            type = exception.type
            module = exception.module
            mechanism = exception.mechanism?.let { mechanism ->
                Mechanism().apply {
                    type = mechanism.type
                    isHandled = mechanism.isHandled
                }
            }
            stacktrace = exception.stacktrace?.frames?.let { frames ->
                SentryStackTrace(
                    frames.map { frame ->
                        SentryStackFrame().apply {
                            module = frame.module
                            function = frame.function
                            lineno = frame.lineno
                            isInApp = frame.isInApp
                            isNative = frame.isNative
                        }
                    },
                )
            }
        }
}
