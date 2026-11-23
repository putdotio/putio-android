package io.putdotio.android.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun playbackTrackLoadListener(
    player: Media3Player,
    isReleased: () -> Boolean,
    recovery: PlaybackTrackRecovery,
    onDropSubtitles: (PlaybackTrackRecoveryStep.DropSubtitles) -> Unit,
): AnalyticsListener =
    object : AnalyticsListener {
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            if (isReleased() || !eventTime.belongsTo(player)) return
            recovery.loadFailed(
                trackType = mediaLoadData.trackType,
                uri = loadEventInfo.dataSpec.uri.toString(),
                gaveUp = wasCanceled,
                atMillis = SystemClock.elapsedRealtime(),
            )?.let(onDropSubtitles)
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            if (isReleased() || !eventTime.belongsTo(player)) return
            recovery.loadCompleted(loadEventInfo.dataSpec.uri.toString())
        }

        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            if (isReleased() || !eventTime.belongsTo(player)) return
            recovery.audioPlaying()
        }
    }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun AnalyticsListener.EventTime.belongsTo(player: Media3Player): Boolean {
    val current = player.currentMediaItem
    val currentTimeline = player.currentTimeline
    val currentWindowIndex = player.currentMediaItemIndex
    val currentWindow = current
        ?.takeIf { currentWindowIndex in 0 until currentTimeline.windowCount }
        ?.let { currentTimeline.getWindow(currentWindowIndex, Timeline.Window()) }
    val eventWindow = currentWindow
        ?.takeIf { windowIndex in 0 until timeline.windowCount }
        ?.let { timeline.getWindow(windowIndex, Timeline.Window()) }
    return eventWindow != null &&
        eventWindow.uid == currentWindow.uid &&
        eventWindow.mediaItem.sameItemAndTracks(current)
}

private fun MediaItem.sameItemAndTracks(other: MediaItem): Boolean =
    mediaId == other.mediaId &&
        localConfiguration?.uri == other.localConfiguration?.uri &&
        localConfiguration?.subtitleConfigurations == other.localConfiguration?.subtitleConfigurations
