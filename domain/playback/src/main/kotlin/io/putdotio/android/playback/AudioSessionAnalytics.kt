package io.putdotio.android.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.sdk.files.PlaybackSourceKind
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val SOURCE_KIND_KEY = "io.putdotio.android.playback.sourceKind"
private const val OFFLINE_KEY = "io.putdotio.android.playback.offline"

internal fun analyticsExtras(kind: PlaybackSourceKind, offline: Boolean): Bundle =
    Bundle().apply {
        putString(SOURCE_KIND_KEY, kind.name)
        putBoolean(OFFLINE_KEY, offline)
    }

public class AudioSessionAnalytics internal constructor(
    private val capture: (AnalyticsEvent) -> Unit,
    private val now: () -> Long,
) {
    public constructor() : this(ProductAnalytics::capture, System::currentTimeMillis)

    private var fileId: Long? = null
    private var started: AnalyticsEvent.Playback? = null
    private var startedAt = 0L
    private var ended = false

    public fun observe(player: Media3Player): Closeable {
        val listener = object : Media3Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Media3Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) localShown(mediaItem)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Media3Player.STATE_ENDED) localEnded()
            }

            override fun onPlayerError(error: PlaybackException) = localFailed(player.currentMediaItem, error)
        }
        player.addListener(listener)
        return Closeable {
            player.removeListener(listener)
            localShown(null)
        }
    }

    public fun follow(controller: PlaybackController, scope: CoroutineScope): PlaybackAnalyticsReporter {
        scope.launch {
            var before: PlaybackContent? = null
            controller.state.collect { state ->
                val content = state.content
                if (content is PlaybackContent.Failed && !before.onSessionPlayer()) {
                    capture(AnalyticsEvent.PlaybackFailed(state.playback(LOCAL), content.failure.analyticsError()))
                }
                before = content
            }
        }
        return object : PlaybackAnalyticsReporter {
            override fun reachedEnd() = reportedEnd(controller.state.value.target.fileId.value)

            override fun castStarted() = castStarted(controller.state.value)

            override fun castFailed() {
                val playback = controller.state.value.playback(CAST)
                capture(AnalyticsEvent.PlaybackFailed(playback, AnalyticsEvent.PlaybackError.Unknown))
            }
        }
    }

    @Synchronized
    private fun localShown(item: MediaItem?) {
        val id = item?.fileId()
        if (id == null) {
            if (started?.player != CAST) {
                fileId = null
                started = null
            }
            return
        }
        if (id == fileId && started?.player == LOCAL && !ended) return
        fileId = id
        start(item.playback())
    }

    @Synchronized
    private fun localEnded() {
        if (started?.player == LOCAL) end()
    }

    @Synchronized
    private fun localFailed(item: MediaItem?, error: PlaybackException) {
        val playback = started?.takeIf { it.player == LOCAL && item?.fileId() == fileId } ?: item.playback()
        capture(AnalyticsEvent.PlaybackFailed(playback, error.toPlaybackFailure().analyticsError()))
    }

    @Synchronized
    private fun castStarted(state: PlaybackState) {
        val id = state.target.fileId.value
        if (id == fileId && started?.player == CAST && !ended) return
        fileId = id
        start(state.playback(CAST))
    }

    @Synchronized
    private fun reportedEnd(id: Long) {
        if (id == fileId) end()
    }

    private fun start(playback: AnalyticsEvent.Playback) {
        started = playback
        startedAt = now()
        ended = false
        capture(AnalyticsEvent.PlaybackStarted(playback))
    }

    private fun end() {
        val playback = started ?: return
        if (ended) return
        ended = true
        capture(AnalyticsEvent.PlaybackEnded(playback, AnalyticsEvent.PlaybackDuration.of(now() - startedAt)))
    }
}

private val LOCAL = AnalyticsEvent.Player.ExoPlayer
private val CAST = AnalyticsEvent.Player.Chromecast

private fun MediaItem.fileId(): Long? = mediaId.toLongOrNull()

private fun MediaItem?.playback(): AnalyticsEvent.Playback {
    val extras = this?.mediaMetadata?.extras
    val kind = extras?.getString(SOURCE_KIND_KEY)?.let { name -> PlaybackSourceKind.entries.find { it.name == name } }
    return playback(PlaybackMediaType.AUDIO, kind, extras?.getBoolean(OFFLINE_KEY) == true, LOCAL)
}

private fun PlaybackState.playback(player: AnalyticsEvent.Player): AnalyticsEvent.Playback =
    playback(target.mediaType, content as? PlaybackContent.Ready, player)

private fun PlaybackContent?.onSessionPlayer(): Boolean =
    this is PlaybackContent.Ready || this == PlaybackContent.Session || this is PlaybackContent.Failed
