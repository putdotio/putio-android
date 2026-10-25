package io.putdotio.android.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput

private const val MICROS_PER_MILLI = 1_000L

/** Renderers whose text renderers take a timing offset; see [setSubtitleOffset]. */
@UnstableApi
internal class SubtitleTimingRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val first = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (index in first until out.size) out[index] = SubtitleOffsetRenderer(out[index])
    }
}

/**
 * A text renderer that reads its cues [offsetUs] behind the playback position: a positive offset
 * shows subtitles later, a negative one sooner, from the samples already buffered ahead. Media3 has
 * no subtitle delay of its own.
 */
@UnstableApi
internal class SubtitleOffsetRenderer(renderer: Renderer) : ForwardingRenderer(renderer) {
    @Volatile
    private var offsetUs = 0L

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == MSG_SET_SUBTITLE_OFFSET) {
            offsetUs = message as Long
        } else {
            super.handleMessage(messageType, message)
        }
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs - offsetUs, elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        super.resetPosition(positionUs - offsetUs, sampleStreamIsResetToKeyFrame)
    }

    // Media3 gives the final stream's end time only to a renderer that is a TextRenderer, so the
    // wrapped one would wait out a cue that runs past the media. Read to the end, it holds up
    // nothing; the player keeps rendering it until audio and video end.
    override fun isEnded(): Boolean = super.isEnded() || hasReadStreamToEnd()

    companion object {
        const val MSG_SET_SUBTITLE_OFFSET: Int = Renderer.MSG_CUSTOM_BASE + 1
    }
}

/**
 * Shows this player's subtitles [offsetMillis] later (negative: sooner). The text renderer drops
 * cues behind where it has read, so with [reread] the player seeks a millisecond on and reads
 * its subtitles again. Players built without the app's renderers, such as test fakes and media
 * controllers, keep their timing.
 */
@UnstableApi
public fun Player.setSubtitleOffset(offsetMillis: Long, reread: Boolean = false) {
    val player = this as? ExoPlayer ?: return
    val renderers = (0 until player.rendererCount).mapNotNull { player.getRenderer(it) as? SubtitleOffsetRenderer }
    renderers.forEach { renderer ->
        player.createMessage(renderer)
            .setType(SubtitleOffsetRenderer.MSG_SET_SUBTITLE_OFFSET)
            .setPayload(offsetMillis * MICROS_PER_MILLI)
            .send()
    }
    if (reread && renderers.isNotEmpty() && player.playbackState != Player.STATE_IDLE) {
        // Media3 skips a seek to the position it is already at, which would read nothing again.
        player.seekTo(player.currentPosition + 1)
    }
}
