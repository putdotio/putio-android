package io.putdotio.android.telemetry

/**
 * The events from the Analytics event contract (Notion, Frontend → Analytics) that this app has a
 * hook for. Every value is a fixed vocabulary, so a call site can't send free text, except
 * `app_opened`'s route, which [AnalyticsAllowlist] checks by shape.
 */
public sealed interface AnalyticsEvent {
    public enum class Screen(internal val value: String) {
        Files("files"),
        Transfers("transfers"),
        Trash("trash"),
        Search("search"),
        History("history"),
        Account("account"),
        Player("player"),
    }

    /** Account and app settings this app saves, minus the privacy controls: consent stays out of events. */
    public enum class Setting(internal val value: String) {
        HistoryEnabled("history_enabled"),
        TrashEnabled("trash_enabled"),
        HideSubtitles("hide_subtitles"),
        DontAutoSelectSubtitles("dont_autoselect_subtitles"),
        UseStartFrom("use_start_from"),
        SortBy("sort_by"),
        TunnelRoute("tunnel_route_name"),
        VideoPlaybackType("video_playback_type"),
        AutoplayNextVideo("autoplay_next_video"),
    }

    /** A switch's state or a playback type, else `changed`, so a sort or route never leaves the device. */
    public enum class SettingValue(internal val value: String) {
        On("on"),
        Off("off"),
        Hls("hls"),
        Mp4("mp4"),
        Changed("changed"),
        ;

        public companion object {
            public fun of(enabled: Boolean): SettingValue = if (enabled) On else Off
        }
    }

    public enum class Media(internal val value: String) { Video("video"), Audio("audio") }

    public enum class StreamMethod(internal val value: String) {
        Hls("hls"),
        Progressive("progressive"),
        Mp4("mp4"),
        LocalFile("local_file"),
    }

    public enum class PlaybackDuration(internal val value: String) {
        Under5m("lt_5m"),
        From5mTo30m("5m_30m"),
        From30mTo90m("30m_90m"),
        Over90m("gt_90m"),
        ;

        public companion object {
            public fun of(millis: Long): PlaybackDuration =
                when {
                    millis < FIVE_MINUTES -> Under5m
                    millis < THIRTY_MINUTES -> From5mTo30m
                    millis < NINETY_MINUTES -> From30mTo90m
                    else -> Over90m
                }
        }
    }

    /** `source` is a deleted, unpaid or throttled file, `unsupported` one the player can't play as it is. */
    public enum class PlaybackError(internal val value: String) {
        Network("network"),
        Media("media"),
        Source("source"),
        Unsupported("unsupported"),
        Unknown("unknown"),
    }

    /** Media3 ExoPlayer plays locally; a Cast receiver is `chromecast`. */
    public enum class Player(internal val value: String) { ExoPlayer("exoplayer"), Chromecast("chromecast") }

    /** A failed load may have no stream yet. */
    public data class Playback(val media: Media, val streamMethod: StreamMethod?, val player: Player)

    public data class ScreenViewed(val screen: Screen) : AnalyticsEvent

    public data class SettingsChanged(val setting: Setting, val value: SettingValue) : AnalyticsEvent

    public data class PlaybackStarted(val playback: Playback) : AnalyticsEvent

    public data class PlaybackEnded(val playback: Playback, val duration: PlaybackDuration) : AnalyticsEvent

    public data class PlaybackFailed(val playback: Playback, val error: PlaybackError) : AnalyticsEvent
}

internal enum class Entry(val value: String) { Direct("direct"), Deeplink("deeplink"), Resume("resume") }

internal enum class SessionDuration(val value: String) {
    Under1m("lt_1m"),
    From1mTo5m("1m_5m"),
    From5mTo15m("5m_15m"),
    From15mTo60m("15m_60m"),
    Over60m("gt_60m"),
    ;

    companion object {
        fun of(millis: Long): SessionDuration =
            when {
                millis < MINUTE -> Under1m
                millis < FIVE_MINUTES -> From1mTo5m
                millis < FIFTEEN_MINUTES -> From5mTo15m
                millis < SIXTY_MINUTES -> From15mTo60m
                else -> Over60m
            }
    }
}

/** [route] is the account's tunnel route name, null while settings aren't loaded. */
internal data class AppOpened(val entry: Entry, val route: String? = null) : AnalyticsEvent

internal data class AppBackgrounded(val duration: SessionDuration) : AnalyticsEvent

private const val MINUTE = 60_000L
private const val FIVE_MINUTES = 5 * MINUTE
private const val FIFTEEN_MINUTES = 15 * MINUTE
private const val THIRTY_MINUTES = 30 * MINUTE
private const val SIXTY_MINUTES = 60 * MINUTE
private const val NINETY_MINUTES = 90 * MINUTE

internal val AnalyticsEvent.name: String
    get() =
        when (this) {
            is AppOpened -> "app_opened"
            is AppBackgrounded -> "app_backgrounded"
            is AnalyticsEvent.ScreenViewed -> "screen_viewed"
            is AnalyticsEvent.SettingsChanged -> "settings_changed"
            is AnalyticsEvent.PlaybackStarted -> "playback_started"
            is AnalyticsEvent.PlaybackEnded -> "playback_ended"
            is AnalyticsEvent.PlaybackFailed -> "playback_failed"
        }

internal val AnalyticsEvent.properties: Map<String, String>
    get() =
        when (this) {
            is AppOpened ->
                mapOf("entry" to entry.value) + route?.let { mapOf("route" to it.ifEmpty { "default" }) }.orEmpty()
            is AppBackgrounded -> mapOf("session_duration" to duration.value)
            is AnalyticsEvent.ScreenViewed -> mapOf("screen" to screen.value)
            is AnalyticsEvent.SettingsChanged -> mapOf("setting" to setting.value, "value" to value.value)
            is AnalyticsEvent.PlaybackStarted -> playback.properties
            is AnalyticsEvent.PlaybackEnded -> playback.properties + ("duration" to duration.value)
            is AnalyticsEvent.PlaybackFailed -> playback.properties + ("error" to error.value)
        }

private val AnalyticsEvent.Playback.properties: Map<String, String>
    get() = mapOf("media" to media.value, "player" to player.value) +
        streamMethod?.let { mapOf("stream_method" to it.value) }.orEmpty()

/**
 * The contract's allowlist, written out apart from [AnalyticsEvent] so a new event, key or value is
 * dropped until it is listed here too. Everything the SDK adds, its device census included, is
 * dropped unless [sdkProperties] keeps it.
 */
internal object AnalyticsAllowlist {
    private val playback = mapOf(
        "media" to setOf("video", "audio"),
        "stream_method" to setOf("hls", "progressive", "mp4", "local_file"),
        "player" to setOf("exoplayer", "chromecast"),
    )

    private val eventProperties: Map<String, Map<String, Set<String>>> = mapOf(
        "app_opened" to mapOf("entry" to setOf("direct", "deeplink", "resume")),
        "app_backgrounded" to mapOf("session_duration" to setOf("lt_1m", "1m_5m", "5m_15m", "15m_60m", "gt_60m")),
        "screen_viewed" to mapOf(
            "screen" to setOf("files", "transfers", "trash", "search", "history", "account", "player"),
        ),
        "settings_changed" to mapOf(
            "setting" to setOf(
                "history_enabled", "trash_enabled", "hide_subtitles", "dont_autoselect_subtitles", "use_start_from",
                "sort_by", "tunnel_route_name", "video_playback_type", "autoplay_next_video",
            ),
            "value" to setOf("on", "off", "hls", "mp4", "changed"),
        ),
        "playback_started" to playback,
        "playback_ended" to playback + ("duration" to setOf("lt_5m", "5m_30m", "30m_90m", "gt_90m")),
        "playback_failed" to playback + ("error" to setOf("network", "media", "source", "unsupported", "unknown")),
    )

    private val commonProperties: Map<String, Set<String>> = mapOf(
        "app" to setOf("android", "androidtv"),
        "runtime" to setOf("android", "androidtv"),
        "device_class" to setOf("phone", "tablet", "tv", "unknown"),
        "auth_state" to setOf("signed_in"),
        "environment" to setOf("production"),
        "release_channel" to setOf("stable", "beta"),
    )

    /** Free-form short strings that only one event carries. */
    private val eventPatternProperties: Map<String, Map<String, Regex>> = mapOf(
        "app_opened" to mapOf("route" to Regex("""[A-Za-z0-9_ .()-]{1,64}""")),
    )

    private val patternProperties: Map<String, Regex> = mapOf(
        "app_version" to Regex("""[A-Za-z0-9_.+-]{1,64}"""),
        "runtime_version" to Regex("""[0-9]+(\.[0-9]+){0,2}"""),
    )

    private val VERSION = Regex("""[0-9]+(\.[0-9]+){1,3}""")
    private val UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

    /** The SDK's own keys that ingestion needs, each checked for its shape. */
    private val sdkProperties: Map<String, (Any) -> Boolean> = mapOf(
        "\$lib" to { it == "posthog-android" },
        "\$lib_version" to { it is String && VERSION.matches(it) },
        "\$session_id" to { it is String && UUID.matches(it) },
        "\$is_identified" to { it is Boolean },
        "\$process_person_profile" to { it is Boolean },
        "\$geoip_disable" to { it == true },
    )

    /** Null drops the whole event. */
    fun allowedProperties(event: String, properties: Map<String, Any>): Map<String, Any>? {
        val eventValues = eventProperties[event] ?: return null
        return properties.filter { (key, value) ->
            sdkProperties[key]?.let { return@filter it(value) }
            if (value !is String) return@filter false
            (eventValues[key] ?: commonProperties[key])?.let { return@filter value in it }
            val pattern = eventPatternProperties[event]?.get(key) ?: patternProperties[key]
            pattern?.matches(value) == true
        }
    }
}
