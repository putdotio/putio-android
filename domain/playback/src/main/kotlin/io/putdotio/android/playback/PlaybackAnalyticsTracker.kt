package io.putdotio.android.playback

import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.sdk.files.PlaybackSourceKind

/** What the players tell a playback's product analytics beyond the controller's own states. */
public interface PlaybackAnalyticsReporter {
    /** The player played the current file to its end, whether or not autoplay moves on. */
    public fun reachedEnd() {}

    /** A receiver took the current file over. */
    public fun castStarted() {}

    /** A receiver couldn't take the current file over. */
    public fun castFailed() {}

    /** The local player shows the current file, such as after a Cast session ends. */
    public fun localShown() {}

    public companion object {
        public val None: PlaybackAnalyticsReporter = object : PlaybackAnalyticsReporter {}
    }
}

/**
 * One controller's `playback_started` once per file and player when its source is ready or a
 * receiver takes it over, `playback_failed` once per failed load, and `playback_ended` once per
 * file played to its end. Resumes, seeks and retries don't repeat the start; closing the player
 * first sends no end.
 */
internal class PlaybackAnalyticsTracker(
    private val capture: (AnalyticsEvent) -> Unit,
    private val now: () -> Long,
) : PlaybackAnalyticsReporter {
    private var previous: PlaybackState? = null
    private var started: AnalyticsEvent.Playback? = null
    private var startedAt = 0L
    private var ended = false

    @Synchronized
    fun onState(state: PlaybackState) {
        val before = previous
        previous = state
        if (before?.target?.fileId != state.target.fileId) {
            started = null
            ended = false
        }
        val content = state.content
        when {
            content is PlaybackContent.Ready && started == null ->
                start(playback(state.target.mediaType, content, AnalyticsEvent.Player.ExoPlayer))
            content is PlaybackContent.Failed && before?.content !is PlaybackContent.Failed -> {
                val ready = before?.content as? PlaybackContent.Ready
                val playback = started ?: playback(state.target.mediaType, ready, AnalyticsEvent.Player.ExoPlayer)
                capture(AnalyticsEvent.PlaybackFailed(playback, content.failure.analyticsError()))
            }
        }
    }

    // A replay after the end doesn't repeat it; the file's next opening does.
    @Synchronized
    override fun reachedEnd() {
        val playback = started ?: return
        if (ended) return
        ended = true
        capture(AnalyticsEvent.PlaybackEnded(playback, AnalyticsEvent.PlaybackDuration.of(now() - startedAt)))
    }

    @Synchronized
    override fun castStarted() {
        val playback = castPlayback() ?: return
        if (started?.player != AnalyticsEvent.Player.Chromecast) start(playback)
    }

    @Synchronized
    override fun localShown() {
        val state = previous ?: return
        val ready = state.content as? PlaybackContent.Ready ?: return
        if (started?.player == AnalyticsEvent.Player.Chromecast) {
            start(playback(state.target.mediaType, ready, AnalyticsEvent.Player.ExoPlayer))
        }
    }

    @Synchronized
    override fun castFailed() {
        val playback = castPlayback() ?: return
        capture(AnalyticsEvent.PlaybackFailed(playback, AnalyticsEvent.PlaybackError.Unknown))
    }

    private fun castPlayback(): AnalyticsEvent.Playback? {
        val state = previous ?: return null
        val ready = state.content as? PlaybackContent.Ready
        return playback(state.target.mediaType, ready, AnalyticsEvent.Player.Chromecast)
    }

    private fun start(playback: AnalyticsEvent.Playback) {
        started = playback
        startedAt = now()
        ended = false
        capture(AnalyticsEvent.PlaybackStarted(playback))
    }
}

internal fun playback(mediaType: PlaybackMediaType, ready: PlaybackContent.Ready?, player: AnalyticsEvent.Player) =
    playback(mediaType, ready?.source?.kind, ready?.offline == true, player)

internal fun playback(
    mediaType: PlaybackMediaType,
    kind: PlaybackSourceKind?,
    offline: Boolean,
    player: AnalyticsEvent.Player,
) = AnalyticsEvent.Playback(
    media = when (mediaType) {
        PlaybackMediaType.VIDEO -> AnalyticsEvent.Media.Video
        PlaybackMediaType.AUDIO -> AnalyticsEvent.Media.Audio
    },
    // A receiver streams a downloaded file from put.io; only the local player reads the download.
    streamMethod = if (offline && player == AnalyticsEvent.Player.ExoPlayer) {
        AnalyticsEvent.StreamMethod.LocalFile
    } else {
        when (kind) {
            PlaybackSourceKind.HLS -> AnalyticsEvent.StreamMethod.Hls
            PlaybackSourceKind.MP4 -> AnalyticsEvent.StreamMethod.Mp4
            PlaybackSourceKind.ORIGINAL -> AnalyticsEvent.StreamMethod.Progressive
            null -> null
        }
    },
    player = player,
)
