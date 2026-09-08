package io.putdotio.android

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
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

/** The owner calls this on the player's application looper and supplies a scope on that looper. */
internal class MobilePlayerPositionObserver(
    private val player: Media3Player,
    parentScope: CoroutineScope,
    private val submit: (String, Long) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var ticker: Job? = null
    private var closed = false
    private val listener = object : Media3Player.Listener {
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
            if (itemChanged) submitPosition(oldLease, oldPosition.positionMs)
        }
    }

    init {
        checkApplicationLooper()
        player.addListener(listener)
        updateTicker()
    }

    fun flush() {
        if (closed) return
        checkApplicationLooper()
        submitPosition(player.currentMediaItem.reportingLease(), player.currentPosition)
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

    private fun submitPosition(lease: String?, positionMillis: Long) {
        if (!lease.isNullOrBlank() && positionMillis > 0) submit(lease, positionMillis)
    }

    private fun checkApplicationLooper() {
        check(Looper.myLooper() == player.applicationLooper) { "Position reporting must use the player looper" }
    }
}

private fun MediaItem?.reportingLease(): String? = this?.mediaMetadata?.extras?.getString(PLAYBACK_REPORTING_LEASE_KEY)
