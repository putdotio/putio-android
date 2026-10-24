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

    // Reading behind, the renderer would still wait for cues after the media has ended.
    override fun isEnded(): Boolean = super.isEnded() || (offsetUs > 0 && hasReadStreamToEnd())

    companion object {
        const val MSG_SET_SUBTITLE_OFFSET: Int = Renderer.MSG_CUSTOM_BASE + 1
    }
}

/**
 * Shows this player's subtitles [offsetMillis] later (negative: sooner). Players built without the
 * app's renderers, such as test fakes and media controllers, keep their timing.
 */
@UnstableApi
public fun Player.setSubtitleOffset(offsetMillis: Long) {
    val player = this as? ExoPlayer ?: return
    for (index in 0 until player.rendererCount) {
        val renderer = player.getRenderer(index) as? SubtitleOffsetRenderer ?: continue
        player.createMessage(renderer)
            .setType(SubtitleOffsetRenderer.MSG_SET_SUBTITLE_OFFSET)
            .setPayload(offsetMillis * MICROS_PER_MILLI)
            .send()
    }
}
