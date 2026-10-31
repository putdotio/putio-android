package io.putdotio.android.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput

private const val MICROS_PER_MILLI = 1_000L

/** Renderers whose text renderers can read ahead; see [setSubtitleLead]. */
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
        for (index in first until out.size) out[index] = SubtitleLeadRenderer(out[index])
    }
}

/**
 * A text renderer that reads its cues [leadUs] ahead of the playback position, from the samples
 * already buffered, so subtitles show sooner. Media3 has no subtitle timing of its own; later
 * subtitles wait in [DelayedSubtitleCues] instead, because reading behind would need samples a seek
 * has already passed.
 */
@UnstableApi
internal class SubtitleLeadRenderer(renderer: Renderer) : ForwardingRenderer(renderer) {
    @Volatile
    private var leadUs = 0L

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType == MSG_SET_SUBTITLE_LEAD) {
            leadUs = message as Long
        } else {
            super.handleMessage(messageType, message)
        }
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs + leadUs, elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        super.resetPosition(positionUs + leadUs, sampleStreamIsResetToKeyFrame)
    }

    // Media3 gives the final stream's end time only to a renderer that is a TextRenderer, so the
    // wrapped one would wait out a cue that runs past the media. Read to the end, it holds up
    // nothing; the player keeps rendering it until audio and video end.
    override fun isEnded(): Boolean = super.isEnded() || hasReadStreamToEnd()

    companion object {
        const val MSG_SET_SUBTITLE_LEAD: Int = Renderer.MSG_CUSTOM_BASE + 1
    }
}

/**
 * Shows this player's subtitles [leadMillis] sooner. The text renderer drops cues behind where it
 * has read, so with [reread] the player seeks a millisecond on and reads its subtitles again; Media3
 * skips a seek to the position it is already at. Players built without the app's renderers, such
 * as test fakes and media controllers, keep their timing.
 */
@UnstableApi
public fun Player.setSubtitleLead(leadMillis: Long, reread: Boolean = false) {
    val player = this as? ExoPlayer ?: return
    val renderers = (0 until player.rendererCount).mapNotNull { player.getRenderer(it) as? SubtitleLeadRenderer }
    renderers.forEach { renderer ->
        player.createMessage(renderer)
            .setType(SubtitleLeadRenderer.MSG_SET_SUBTITLE_LEAD)
            .setPayload(leadMillis.coerceAtLeast(0L) * MICROS_PER_MILLI)
            .send()
    }
    if (reread && renderers.isNotEmpty() && player.playbackState != Player.STATE_IDLE) {
        player.seekTo(player.currentPosition + 1)
    }
}

/**
 * The cues the player showed, by the position it showed them at, so they can show again later.
 * Only what played since the last seek is here: after a seek, a delay leaves its first stretch
 * without subtitles.
 */
public class DelayedSubtitleCues(private val retainMillis: Long = DEFAULT_RETAIN_MILLIS) {
    private val shown = ArrayDeque<Pair<Long, List<Cue>>>()

    public fun record(positionMillis: Long, cues: List<Cue>) {
        synchronized(shown) {
            while (shown.isNotEmpty() && shown.last().first > positionMillis) shown.removeLast()
            shown.addLast(positionMillis to cues)
            while (shown.size > 1 && shown[1].first + retainMillis < positionMillis) shown.removeFirst()
        }
    }

    public fun clear() {
        synchronized(shown) { shown.clear() }
    }

    /** What showed [delayMillis] before [positionMillis]; nothing when that was before the record. */
    public fun at(positionMillis: Long, delayMillis: Long): List<Cue> =
        synchronized(shown) { shown.lastOrNull { it.first + delayMillis <= positionMillis }?.second.orEmpty() }

    private companion object {
        // Past the longest delay the viewer can pick.
        const val DEFAULT_RETAIN_MILLIS = 12_000L
    }
}

public fun TrackSelectionParameters.sameSubtitleChoice(other: TrackSelectionParameters): Boolean =
    subtitleChoice() == other.subtitleChoice()

private fun TrackSelectionParameters.subtitleChoice(): Pair<Boolean, List<TrackSelectionOverride>> =
    (C.TRACK_TYPE_TEXT in disabledTrackTypes) to overrides.values.filter { it.type == C.TRACK_TYPE_TEXT }
