package io.putdotio.android.playback

import android.os.Bundle
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

public const val PLAYBACK_REPORTING_LEASE_KEY: String = "io.putdotio.android.playback.reportingLease"
private const val POSITION_REPORT_INTERVAL_MILLIS = 10_000L
private const val SEEK_SETTLE_MILLIS = 1_000L

/** Tags [this] item with a [PlaybackPositionWriter] lease, so an observer reports its positions under it. */
public fun MediaItem.withReportingLease(token: String): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle())
    extras.putString(PLAYBACK_REPORTING_LEASE_KEY, token)
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

/**
 * One per actual player, on mobile and TV. The owner calls this on the player's application
 * looper and supplies a scope on that looper.
 */
public class PlaybackPositionObserver(
    private val player: Media3Player,
    parentScope: CoroutineScope,
    private val submit: (String, Long) -> Unit,
) : Closeable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private var ticker: Job? = null
    private var seekSave: Job? = null
    private var seekedLease: String? = null
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
            if (itemChanged) {
                cancelSeekSave()
                submitPosition(oldLease, oldPosition.positionMs)
                if (newPosition.mediaItem.reportingLease() != oldLease) seekedLease = null
            } else if (reason == Media3Player.DISCONTINUITY_REASON_SEEK ||
                reason == Media3Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                if (reason == Media3Player.DISCONTINUITY_REASON_SEEK) seekedLease = oldLease
                saveWhenSeekSettles()
            }
        }
    }

    init {
        checkApplicationLooper()
        player.addListener(listener)
        updateTicker()
    }

    public fun flush() {
        if (closed) return
        checkApplicationLooper()
        cancelSeekSave()
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
                    if (player.isPlaying && seekSave == null) flush()
                }
            }
        }
    }

    private fun saveWhenSeekSettles() {
        cancelSeekSave()
        seekSave = scope.launch {
            delay(SEEK_SETTLE_MILLIS)
            checkApplicationLooper()
            seekSave = null
            flush()
        }
    }

    private fun cancelSeekSave() {
        seekSave?.cancel()
        seekSave = null
    }

    private fun submitPosition(lease: String?, positionMillis: Long) {
        if (lease.isNullOrBlank()) return
        if (positionMillis >= MIN_SAVED_POSITION_MILLIS || (positionMillis >= 0L && lease == seekedLease)) {
            submit(lease, positionMillis)
        }
    }

    private fun checkApplicationLooper() {
        check(Looper.myLooper() == player.applicationLooper) { "Position reporting must use the player looper" }
    }
}

private fun MediaItem?.reportingLease(): String? = this?.mediaMetadata?.extras?.getString(PLAYBACK_REPORTING_LEASE_KEY)
