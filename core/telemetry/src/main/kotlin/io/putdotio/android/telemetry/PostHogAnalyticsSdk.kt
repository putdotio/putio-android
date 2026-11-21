package io.putdotio.android.telemetry

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.posthog.PersonProfiles
import com.posthog.PostHog
import com.posthog.PostHogBootstrapConfig
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import com.posthog.PostHogInterface
import com.posthog.internal.PostHogApi
import com.posthog.internal.PostHogApiEndpoint
import com.posthog.internal.PostHogApiError
import com.posthog.internal.PostHogMemoryPreferences
import com.posthog.internal.PostHogQueueInterface
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

internal class PostHogAnalyticsSdk(
    private val context: Context,
    private val configuration: ProductAnalyticsConfiguration,
    private val httpClient: OkHttpClient? = null,
) : AnalyticsSdk {
    private var client: PostHogInterface? = null
    private var closed: AtomicBoolean? = null
    private var common: Map<String, Any> = emptyMap()

    override fun start(accountId: Long) {
        common = commonProperties()
        val closed = AtomicBoolean().also { closed = it }
        client = PostHog.with(config(accountId, closed))
    }

    override fun capture(name: String, properties: Map<String, String>, timestampMillis: Long) {
        client?.capture(name, properties = common + properties, timestamp = Date(timestampMillis))
    }

    override fun stop() {
        closed?.set(true)
        closed = null
        val closing = client
        client = null
        runCatching { closing?.close() }
        storageDir(context).deleteRecursively()
    }

    private fun config(accountId: Long, closed: AtomicBoolean) = PostHogConfig(
        projectToken = configuration.token,
        host = HOST,
        queueProvider = { config, api, endpoint, _, executor -> MemoryQueue(config, api, endpoint, executor, closed) },
    ).apply {
        sdkName = "posthog-android"
        sdkVersion = com.posthog.android.BuildConfig.VERSION_NAME
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
        httpClient = this@PostHogAnalyticsSdk.httpClient
        addBeforeSend(::allowlisted)
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

        fun storageDir(context: Context) = File(context.noBackupFilesDir, "io.put.analytics")
    }
}

@Suppress("DEPRECATION") // `set` is the SDK's $set; listing it as null keeps person properties off the wire.
private fun allowlisted(event: PostHogEvent): PostHogEvent? {
    val kept = AnalyticsAllowlist.allowedProperties(event.event, event.properties.orEmpty()) ?: return null
    return event.copy(properties = kept.toMutableMap(), set = null)
}

private class MemoryQueue(
    private val config: PostHogConfig,
    private val api: PostHogApi,
    private val endpoint: PostHogApiEndpoint,
    private val executor: ExecutorService,
    private val closed: AtomicBoolean,
) : PostHogQueueInterface<PostHogEvent> {
    private val pending = ConcurrentLinkedDeque<PostHogEvent>()

    override val size: Int get() = pending.size

    override fun add(record: PostHogEvent) = submit {
        pending.addLast(record)
        while (pending.size > config.maxQueueSize) pending.pollFirst()
        send()
    }

    override fun flush() = submit(::send)

    override fun clear() = submit(pending::clear)

    override fun start() = Unit

    override fun stop() = executor.shutdown()

    private fun submit(task: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute { if (!closed.get()) task() }
        } catch (_: RejectedExecutionException) {
            return
        }
    }

    private fun send() {
        while (!closed.get()) {
            val batch = pending.take(config.maxBatchSize).ifEmpty { return }
            if (!settled(batch)) return
            repeat(batch.size) { pending.pollFirst() }
        }
    }

    private fun settled(batch: List<PostHogEvent>): Boolean = try {
        if (endpoint == PostHogApiEndpoint.BATCH) api.batch(batch) else api.snapshot(batch)
        true
    } catch (_: IOException) {
        false
    } catch (e: PostHogApiError) {
        e.statusCode != TOO_MANY_REQUESTS && e.statusCode < SERVER_ERROR
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val SERVER_ERROR = 500
    }
}
