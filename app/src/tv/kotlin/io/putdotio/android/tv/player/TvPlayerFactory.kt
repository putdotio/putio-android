package io.putdotio.android.tv.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.audioAttributes
import io.putdotio.android.playback.playbackRenderersFactory

/** Creates the player one TV playback screen owns and releases. */
internal fun interface TvPlayerFactory {
    fun create(context: Context, mediaType: PlaybackMediaType): Player
}

/**
 * A streaming ExoPlayer. The resolved source already carries its media credential, and
 * the default media source factory picks HLS from the item's MIME type.
 */
internal object DefaultTvPlayerFactory : TvPlayerFactory {
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        ExoPlayer.Builder(context, playbackRenderersFactory(context))
            .setAudioAttributes(mediaType.audioAttributes(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
}
