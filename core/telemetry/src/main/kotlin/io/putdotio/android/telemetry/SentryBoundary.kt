package io.putdotio.android.telemetry

import io.sentry.Breadcrumb
import io.sentry.IConnectionStatusProvider.ConnectionStatus
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.App
import io.sentry.protocol.Contexts
import io.sentry.protocol.Device
import io.sentry.protocol.Gpu
import io.sentry.protocol.Message
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryRuntime
import io.sentry.protocol.User

/**
 * Every event and breadcrumb, including the SDK's automatic crash, ANR, lifecycle, and
 * connectivity ones, passes [redact] before it can leave; while diagnostics are off they are
 * dropped instead. Fields not rewritten here pass through on purpose: SDK-maintained metadata
 * (`eventId`, `timestamp`, `level`, `platform`, `release`, `environment`, `dist`, `sdk`,
 * `modules`) and code identifiers (`threads` stack traces, `debugMeta`).
 * `SentryBoundaryTest` fails when a Sentry upgrade adds a field this has not reviewed.
 */
internal object SentryBoundary {
    const val SURFACE_TAG: String = "surface"

    /** Device and runtime facts the SDK fills in; any other context, such as `response` or `trace`, is dropped. */
    val allowedContexts: Set<String> = setOf("app", "device", "os", "runtime", "gpu")

    /** The SDK's own install-source tags, the surface, and [TelemetryTag]s; any other tag is dropped. */
    val allowedTags: Set<String> =
        setOf("isSideLoaded", "installerStore", SURFACE_TAG) + TelemetryTag.entries.map(TelemetryTag::key)

    fun configure(
        options: SentryOptions,
        configuration: SentryConfiguration,
        filter: DiagnosticsFilter,
        cacheDirPath: String,
    ) {
        options.dsn = configuration.dsn
        options.environment = configuration.environment
        options.release = configuration.release
        options.setTag(SURFACE_TAG, configuration.surface)
        // Data collection stays unconfigured, so the SDK collects no personal data by default;
        // setting any one of its options opts the others into broader defaults.
        options.cacheDirPath = cacheDirPath
        // Also covers what the SDK uploads without these callbacks: cached envelopes and sessions.
        options.setTransportGate {
            filter.isCapturing &&
                options.connectionStatusProvider.connectionStatus != ConnectionStatus.DISCONNECTED
        }
        options.setBeforeSend { event, _ -> if (filter.admits(event.timestamp.time)) redact(event) else null }
        options.setBeforeBreadcrumb { breadcrumb, _ -> if (filter.isCapturing) redact(breadcrumb) else null }
        // Errors and release health only: no traces, logs, or metrics.
        options.tracesSampleRate = null
        options.setBeforeSendTransaction { _, _ -> null }
        options.logs.isEnabled = false
        options.logs.setBeforeSend { null }
        options.metrics.isEnabled = false
        options.metrics.setBeforeSend { _, _ -> null }
    }

    fun event(failure: TelemetryFailure): SentryEvent =
        SentryEvent().apply {
            level = SentryLevel.ERROR
            message = Message().apply { formatted = failure.message }
            fingerprints = failure.fingerprint
            failure.tags.forEach { (key, value) -> setTag(key, value) }
        }

    fun redact(event: SentryEvent): SentryEvent =
        event.apply {
            message = message?.let { redact(it) }
            logger = logger?.let(TelemetryRedaction::scrub)
            serverName = null
            transaction = transaction?.let(TelemetryRedaction::scrub)
            tags = tags?.filterKeys(allowedTags::contains)?.mapValues { TelemetryRedaction.scrub(it.value) }
            setExtras(null)
            redact(contexts)
            fingerprints = fingerprints?.map(TelemetryRedaction::scrub)
            user = user?.let { User().apply { id = it.id } }
            request = null
            exceptions?.forEach { redact(it) }
            threads?.forEach { thread ->
                thread.name = thread.name?.let(TelemetryRedaction::scrub)
                thread.unknown = null
            }
            breadcrumbs = breadcrumbs?.map { redact(it) }
            unknown = null
        }

    fun redact(breadcrumb: Breadcrumb): Breadcrumb =
        breadcrumb.apply {
            message = message?.let(TelemetryRedaction::scrub)
            category = category?.let(TelemetryRedaction::scrub)
            data.keys.toList().forEach { key -> setData(key, TelemetryRedaction.scrubValue(getData(key), key)) }
            unknown = null
        }

    private fun redact(message: Message): Message =
        Message().apply {
            formatted = message.formatted?.let(TelemetryRedaction::scrub)
            this.message = message.message?.let(TelemetryRedaction::scrub)
            params = message.params?.map(TelemetryRedaction::scrub)
        }

    // A throwable's message can quote anything, a media title included, and titles have no
    // shape to scrub; the exception's type and stack trace identify the crash without it.
    private fun redact(exception: SentryException) {
        exception.value = null
        exception.type = exception.type?.let(TelemetryRedaction::scrub)
        exception.module = exception.module?.let(TelemetryRedaction::scrub)
        exception.unknown = null
        exception.mechanism?.apply {
            description = null
            helpLink = null
            meta = meta?.let(TelemetryRedaction::scrubMap)
            data = data?.let(TelemetryRedaction::scrubMap)
            unknown = null
        }
    }

    private fun redact(contexts: Contexts) {
        for (key in contexts.keys().toList()) {
            if (key !in allowedContexts) {
                contexts.remove(key)
                continue
            }
            when (val context = contexts.get(key)) {
                is App -> context.apply {
                    viewNames = viewNames?.map(TelemetryRedaction::scrub)
                    unknown = null
                }
                is Device -> context.apply {
                    name = null
                    unknown = null
                }
                is OperatingSystem -> context.unknown = null
                is SentryRuntime -> context.unknown = null
                is Gpu -> context.unknown = null
                // Contexts read back from disk arrive as plain maps.
                else -> contexts.put(key, TelemetryRedaction.scrubValue(context))
            }
        }
    }
}
