package io.putdotio.android.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.ProductAnalytics

/**
 * Playback analytics read straight from a player that outlives any screen, such as the background
 * audio service's: `playback_started` once per item when it first plays, `playback_ended` when it
 * plays to its end (the queue moving on counts), and `playback_failed` once per failure.
 */
public class PlayerPlaybackAnalytics internal constructor(
    private val media: AnalyticsEvent.Media,
    private val capture: (AnalyticsEvent) -> Unit,
    private val now: () -> Long,
) : Player.Listener {
    public constructor(media: AnalyticsEvent.Media) : this(media, ProductAnalytics::capture, System::currentTimeMillis)

    private var mediaId: String? = null
    private var started: AnalyticsEvent.Playback? = null
    private var startedAt = 0L
    private var ended = false

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) end()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val item = player.currentMediaItem
        if (item?.mediaId != mediaId) {
            mediaId = item?.mediaId
            started = null
            ended = false
        }
        if (item != null && started == null && player.isPlaying) {
            val playback = playback(item)
            started = playback
            startedAt = now()
            capture(AnalyticsEvent.PlaybackStarted(playback))
        }
        if (player.playbackState == Player.STATE_ENDED) end()
    }

    override fun onPlayerError(error: PlaybackException) {
        val playback = started ?: AnalyticsEvent.Playback(media, null, AnalyticsEvent.Player.ExoPlayer)
        capture(AnalyticsEvent.PlaybackFailed(playback, error.toPlaybackFailure().analyticsError()))
    }

    private fun end() {
        val playback = started ?: return
        if (ended) return
        ended = true
        capture(AnalyticsEvent.PlaybackEnded(playback, AnalyticsEvent.PlaybackDuration.of(now() - startedAt)))
    }

    private fun playback(item: MediaItem) =
        AnalyticsEvent.Playback(
            media = media,
            streamMethod = item.localConfiguration?.let { local ->
                if (local.mimeType == MimeTypes.APPLICATION_M3U8) {
                    AnalyticsEvent.StreamMethod.Hls
                } else {
                    AnalyticsEvent.StreamMethod.Mp4
                }
            },
            player = AnalyticsEvent.Player.ExoPlayer,
        )
}
