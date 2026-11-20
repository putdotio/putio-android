package io.putdotio.android.telemetry

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.posthog.PersonProfiles
import com.posthog.PostHogBootstrapConfig
import com.posthog.PostHogEncryption
import com.posthog.PostHogEvent
import com.posthog.PostHogInterface
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogMemoryPreferences
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.OkHttpClient

internal class PostHogAnalyticsSdk(
    private val context: Context,
    private val configuration: ProductAnalyticsConfiguration,
    private val httpClient: OkHttpClient? = null,
) : AnalyticsSdk {
    private var client: PostHogInterface? = null
    private var streams: QueueStreams? = null
    private var common: Map<String, Any> = emptyMap()

    override fun start(accountId: Long) {
        common = commonProperties()
        val streams = QueueStreams().also { streams = it }
        client = PostHogAndroid.with(context, config(accountId, streams))
    }

    override fun capture(name: String, properties: Map<String, String>, timestampMillis: Long) {
        client?.capture(name, properties = common + properties, timestamp = Date(timestampMillis))
    }

    override fun stop() {
        val closing = runCatching { streams?.closeAll(STOP_TIMEOUT_MILLIS) }
        streams = null
        storageDir(context).deleteRecursively()
        client?.close()
        client = null
        val storage = storageDir(context)
        if (!storage.deleteRecursively()) {
            throw IOException("PostHog storage survived stop: $storage").apply {
                closing.exceptionOrNull()?.let(::addSuppressed)
            }
        }
        closing.getOrThrow()
    }

    private fun config(accountId: Long, streams: QueueStreams): PostHogAndroidConfig {
        val storage = storageDir(context)
        return PostHogAndroidConfig(
            projectToken = configuration.token,
            host = HOST,
            captureApplicationLifecycleEvents = false,
            captureDeepLinks = false,
            captureScreenViews = false,
            capturePushNotificationSubscriptions = false,
            capturePushNotificationOpened = false,
        ).apply {
            // Identified before the first event, with no $identify and no flags request.
            bootstrap = PostHogBootstrapConfig(distinctId = accountId.toString(), isIdentifiedId = true)
            personProfiles = PersonProfiles.IDENTIFIED_ONLY
            setDefaultPersonProperties = false
            remoteConfig = false
            preloadFeatureFlags = false
            sendFeatureFlagEvent = false
            sessionReplay = false
            surveys = false
            errorTrackingConfig.autoCapture = false
            // Ids live in memory only, so each start is a fresh install and an opt-out leaves none.
            cachePreferences = PostHogMemoryPreferences()
            legacyStoragePrefix = File(storage, "legacy").path
            storagePrefix = File(storage, "queue").path
            replayStoragePrefix = File(storage, "replay").path
            logsStoragePrefix = File(storage, "logs").path
            // A session has few events; each goes when captured instead of waiting for 20.
            flushAt = 1
            httpClient = this@PostHogAnalyticsSdk.httpClient
            encryption = streams
            addBeforeSend { event -> allowlisted(event)?.also { streams.queued() } }
        }
    }

    /** Read once per start. */
    private fun commonProperties(): Map<String, Any> {
        val tablet = context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP
        val deviceClass = when {
            configuration.app == "androidtv" -> "tv"
            context.resources.configuration.smallestScreenWidthDp == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED ->
                "unknown"
            tablet -> "tablet"
            else -> "phone"
        }
        return buildMap {
            put("app", configuration.app)
            put("runtime", configuration.app)
            put("runtime_version", Build.VERSION.RELEASE)
            put("device_class", deviceClass)
            put("auth_state", "signed_in")
            // Only release builds send, and they talk to production put.io.
            put("environment", "production")
            put("release_channel", configuration.releaseChannel)
            put("app_version", configuration.appVersion)
            put("\$geoip_disable", true)
        }
    }

    private companion object {
        /** put.io's forward-only proxy to PostHog Cloud EU. */
        const val HOST = "https://burrow.put.io"
        const val TABLET_MIN_WIDTH_DP = 600
        const val STOP_TIMEOUT_MILLIS = 5_000L

        fun storageDir(context: Context) = File(context.noBackupFilesDir, "io.put.analytics")
    }
}

@Suppress("DEPRECATION") // `set` is the SDK's $set; listing it as null keeps person properties off the wire.
private fun allowlisted(event: PostHogEvent): PostHogEvent? {
    val kept = AnalyticsAllowlist.allowedProperties(event.event, event.properties.orEmpty()) ?: return null
    return event.copy(properties = kept.toMutableMap(), set = null)
}

private class QueueStreams : PostHogEncryption {
    private val lock = ReentrantLock()
    private val writeStarted = lock.newCondition()
    private val open = mutableSetOf<Closeable>()
    private var queued = 0
    private var started = 0
    private var closed = false

    fun queued() {
        lock.withLock { queued++ }
    }

    override fun encrypt(outputStream: OutputStream): OutputStream {
        lock.withLock {
            started++
            writeStarted.signalAll()
            open(outputStream)
        }
        return object : FilterOutputStream(outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
            }

            override fun close() {
                lock.withLock { open -= outputStream }
                super.close()
            }
        }
    }

    override fun decrypt(inputStream: InputStream): InputStream {
        lock.withLock { open(inputStream) }
        return object : FilterInputStream(inputStream) {
            override fun close() {
                lock.withLock { open -= inputStream }
                super.close()
            }
        }
    }

    fun closeAll(timeoutMillis: Long) {
        var unstarted = 0
        val left = lock.withLock {
            var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (started < queued && nanos > 0) nanos = writeStarted.awaitNanos(nanos)
            unstarted = queued - started
            closed = true
            open.toList().also { open.clear() }
        }
        val failures = left.mapNotNull { runCatching(it::close).exceptionOrNull() }
        if (unstarted > 0 || failures.isNotEmpty()) {
            val message = "PostHog queue: $unstarted writes never started, ${failures.size} streams failed to close"
            throw IOException(message).apply { failures.forEach(::addSuppressed) }
        }
    }

    private fun open(stream: Closeable) {
        if (closed) {
            stream.close()
            throw IOException("PostHog storage is stopping")
        }
        open += stream
    }
}
