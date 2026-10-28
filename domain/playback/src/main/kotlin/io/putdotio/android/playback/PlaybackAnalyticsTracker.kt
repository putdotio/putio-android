package io.putdotio.android.playback

import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind

/**
 * One controller's `playback_started` once per file when its source is ready, `playback_failed`
 * once per failed load, and `playback_ended` once per file the player plays to its end. Resumes,
 * seeks and retries don't repeat the start; closing the player first sends no end.
 */
internal class PlaybackAnalyticsTracker(
    private val capture: (AnalyticsEvent) -> Unit,
    private val now: () -> Long,
) {
    private var previous: PlaybackState? = null
    private var started: Pair<AnalyticsEvent.Playback, Long>? = null

    @Synchronized
    fun onState(state: PlaybackState) {
        val before = previous
        previous = state
        if (before?.target?.fileId != state.target.fileId) started = null
        val content = state.content
        when {
            content is PlaybackContent.Ready && started == null -> {
                val playback = playback(state.target.mediaType, content.source)
                started = playback to now()
                capture(AnalyticsEvent.PlaybackStarted(playback))
            }
            content is PlaybackContent.Failed && before?.content !is PlaybackContent.Failed -> {
                val source = (before?.content as? PlaybackContent.Ready)?.source
                val playback = started?.first ?: playback(state.target.mediaType, source)
                capture(AnalyticsEvent.PlaybackFailed(playback, content.failure.analyticsError()))
            }
        }
    }

    /** The player reached the end of the current file, whether or not autoplay moves on. */
    @Synchronized
    fun onReachedEnd() {
        val (playback, since) = started ?: return
        // A replay after the end doesn't repeat it; the file's next opening does.
        started = playback to Long.MAX_VALUE
        if (since != Long.MAX_VALUE) {
            capture(AnalyticsEvent.PlaybackEnded(playback, AnalyticsEvent.PlaybackDuration.of(now() - since)))
        }
    }
}

private fun playback(mediaType: PlaybackMediaType, source: PlaybackSource?) =
    AnalyticsEvent.Playback(
        media = when (mediaType) {
            PlaybackMediaType.VIDEO -> AnalyticsEvent.Media.Video
            PlaybackMediaType.AUDIO -> AnalyticsEvent.Media.Audio
        },
        streamMethod = when (source?.kind) {
            PlaybackSourceKind.HLS -> AnalyticsEvent.StreamMethod.Hls
            PlaybackSourceKind.MP4 -> AnalyticsEvent.StreamMethod.Mp4
            PlaybackSourceKind.ORIGINAL -> AnalyticsEvent.StreamMethod.Progressive
            null -> null
        },
    )
