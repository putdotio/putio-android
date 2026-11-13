package io.putdotio.android.playback

import androidx.annotation.StringRes
import io.putdotio.android.settings.StreamingMethod

public enum class PlaybackStream(
    @param:StringRes public val label: Int,
    @param:StringRes public val detail: Int,
) {
    Original(R.string.playback_stream_original, R.string.playback_stream_original_detail),
    Converted(R.string.playback_stream_converted, R.string.playback_stream_converted_detail),
}

/** What the Stream chip shows: the stream playing, and the move to the other one. */
public class PlaybackStreamSwitch(
    public val current: PlaybackStream,
    public val switch: () -> Unit,
)

internal fun PlaybackState.originalChosen(event: PlaybackEvent.OriginalChosen): PlaybackTransition {
    if (!offersOriginal) return PlaybackTransition(this, consumed = false)
    val requestId = PlaybackRequestId(nextRequestValue)
    val original = target.copy(method = StreamingMethod.File)
    return PlaybackTransition(
        state = copy(
            target = original,
            content = PlaybackContent.Loading(requestId),
            nextRequestValue = nextRequestValue + 1,
            resumePositionMillis = event.positionMillis.coerceAtLeast(0L),
            convertedOnly = false,
            linkRefetchedAtMillis = null,
            switchedToConverted = false,
        ),
        effect = PlaybackEffect.Resolve(original, requestId),
    )
}
