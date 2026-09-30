package io.putdotio.android.playback

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player as Media3Player
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val PLAYBACK_REPORTING_LEASE_KEY = "io.putdotio.android.playback.reportingLease"
private const val POSITION_REPORT_INTERVAL_MILLIS = 15_000L
private const val NEAR_END_RESET_MILLIS = 10_000L
private const val MAX_KNOWN_DURATIONS = 8

/** Tags [this] item with a [PlaybackPositionWriter] lease, so an observer reports its positions under it. */
internal fun MediaItem.withReportingLease(token: String): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle())
    extras.putString(PLAYBACK_REPORTING_LEASE_KEY, token)
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

/**
 * One per actual player, on mobile and TV. The owner calls this on the player's application
 * looper and supplies a scope on that looper. A position within 10 s of the item's end is
 * reported as 0, as iOS saves it, so a finished item starts over instead of offering to resume.
 */
internal class PlaybackPositionObserver(
    private val player: Media3Player,
    parentScope: CoroutineScope,
    private val submit: (String, Long) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var ticker: Job? = null
    private var closed = false

    /** Known durations by lease; a replaced item's discontinuity arrives after its timeline is gone. */
    private val durations = linkedMapOf<String, Long>()
    private val listener = object : Media3Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (!closed) rememberDurations(timeline)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (closed) return
            updateTicker()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) flush()
            updateTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Media3Player.STATE_ENDED || playbackState == Media3Player.STATE_IDLE) flush()
            updateTicker()
        }

        override fun onPlayerError(error: PlaybackException) = flush()

        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        override fun onPositionDiscontinuity(
            oldPosition: Media3Player.PositionInfo,
            newPosition: Media3Player.PositionInfo,
            reason: Int,
        ) {
            if (closed) return
            val oldLease = oldPosition.mediaItem.reportingLease()
            val itemChanged = oldLease != newPosition.mediaItem.reportingLease() ||
                oldPosition.mediaItemIndex != newPosition.mediaItemIndex ||
                reason == Media3Player.DISCONTINUITY_REASON_REMOVE ||
                reason == Media3Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            // A seek within the same item waits for the next periodic or lifecycle snapshot.
            if (itemChanged) submitPosition(oldLease, oldPosition.positionMs, durationOf(oldLease))
        }
    }

    init {
        checkApplicationLooper()
        player.addListener(listener)
        rememberDurations(player.currentTimeline)
        updateTicker()
    }

    fun flush() {
        if (closed) return
        checkApplicationLooper()
        rememberDurations(player.currentTimeline)
        val lease = player.currentMediaItem.reportingLease()
        submitPosition(lease, player.currentPosition, durationOf(lease))
    }

    override fun close() {
        if (closed) return
        checkApplicationLooper()
        try {
            flush()
        } finally {
            closed = true
            scope.cancel()
            player.removeListener(listener)
        }
    }

    private fun updateTicker() {
        // Buffering and transient suppression must not keep postponing the next sample.
        if (!player.playWhenReady || player.playbackState == Media3Player.STATE_IDLE ||
            player.playbackState == Media3Player.STATE_ENDED
        ) {
            ticker?.cancel()
            ticker = null
        } else if (ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive) {
                    delay(POSITION_REPORT_INTERVAL_MILLIS)
                    checkApplicationLooper()
                    if (player.isPlaying) flush()
                }
            }
        }
    }

    private fun submitPosition(lease: String?, positionMillis: Long, durationMillis: Long) {
        if (lease.isNullOrBlank() || positionMillis <= 0) return
        val nearEnd = durationMillis != C.TIME_UNSET && positionMillis >= durationMillis - NEAR_END_RESET_MILLIS
        submit(lease, if (nearEnd) 0L else positionMillis)
    }

    private fun rememberDurations(timeline: Timeline) {
        val window = Timeline.Window()
        for (index in 0 until timeline.windowCount) {
            timeline.getWindow(index, window)
            val lease = window.mediaItem.reportingLease()
            if (lease.isNullOrBlank() || window.durationMs == C.TIME_UNSET || window.durationMs <= 0) continue
            durations.remove(lease)
            durations[lease] = window.durationMs
        }
        while (durations.size > MAX_KNOWN_DURATIONS) durations.remove(durations.keys.first())
    }

    private fun durationOf(lease: String?): Long = lease?.let(durations::get) ?: C.TIME_UNSET

    private fun checkApplicationLooper() {
        check(Looper.myLooper() == player.applicationLooper) { "Position reporting must use the player looper" }
    }
}

private fun MediaItem?.reportingLease(): String? = this?.mediaMetadata?.extras?.getString(PLAYBACK_REPORTING_LEASE_KEY)
