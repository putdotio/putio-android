package io.putdotio.android.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer

private const val MICROS_PER_MILLI = 1_000L

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

    override fun isEnded(): Boolean = super.isEnded() || hasReadStreamToEnd()

    companion object {
        const val MSG_SET_SUBTITLE_LEAD: Int = Renderer.MSG_CUSTOM_BASE + 1
    }
}

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

    public fun at(positionMillis: Long, delayMillis: Long): List<Cue> = atOrNull(positionMillis, delayMillis).orEmpty()

    public fun atOrNull(positionMillis: Long, delayMillis: Long): List<Cue>? =
        synchronized(shown) { shown.lastOrNull { it.first + delayMillis <= positionMillis }?.second }

    private companion object {
        const val DEFAULT_RETAIN_MILLIS = 12_000L
    }
}

public fun TrackSelectionParameters.sameSubtitleChoice(other: TrackSelectionParameters): Boolean =
    subtitleChoice() == other.subtitleChoice()

private fun TrackSelectionParameters.subtitleChoice(): Pair<Boolean, List<TrackSelectionOverride>> =
    (C.TRACK_TYPE_TEXT in disabledTrackTypes) to overrides.values.filter { it.type == C.TRACK_TYPE_TEXT }
