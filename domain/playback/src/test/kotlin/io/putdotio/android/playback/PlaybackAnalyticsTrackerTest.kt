package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.AnalyticsEvent.Playback
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackAnalyticsTrackerTest {
    private val events = mutableListOf<AnalyticsEvent>()
    private var clock = 0L
    private val tracker = PlaybackAnalyticsTracker(events::add) { clock }

    @Test
    fun oneStartAndEndPerFileThroughRetriesAndReplays() {
        var state = PlaybackReducer.start(Target).state.also(tracker::onState)
        state = state.after(resolved(1L, 42L))
        val unsupported = PlaybackFailure.MediaUnsupported(IllegalStateException("decoder"))
        state = state.after(PlaybackEvent.PlayerFailed(unsupported, 0L))
        state = state.after(PlaybackEvent.Retry)
        state = state.after(resolved(2L, 42L))
        clock = 40 * 60_000L
        tracker.onReachedEnd()
        tracker.onReachedEnd()
        state = state.after(PlaybackEvent.PlayerEnded)
        val next = PlaybackTarget(FilesItemId(43L), "next.mkv")
        state = state.after(PlaybackEvent.NextFound(PlaybackRequestId(3L), next))
        state.after(resolved(4L, 43L))

        val video = Playback(AnalyticsEvent.Media.Video, AnalyticsEvent.StreamMethod.Hls)
        assertEquals(
            listOf(
                AnalyticsEvent.PlaybackStarted(video),
                AnalyticsEvent.PlaybackFailed(video, AnalyticsEvent.PlaybackError.Unsupported),
                AnalyticsEvent.PlaybackEnded(video, AnalyticsEvent.PlaybackDuration.From30mTo90m),
                AnalyticsEvent.PlaybackStarted(video),
            ),
            events,
        )
    }

    private fun PlaybackState.after(event: PlaybackEvent): PlaybackState =
        PlaybackReducer.reduce(this, event).state.also(tracker::onState)

    private fun resolved(requestId: Long, fileId: Long) =
        PlaybackEvent.ResolveSucceeded(PlaybackRequestId(requestId), PlaybackResolution.Ready(source(fileId)))

    private fun source(fileId: Long) =
        PlaybackSource(
            fileId = fileId,
            kind = PlaybackSourceKind.HLS,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/$fileId/hls/media.m3u8?token=secret"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )

    private companion object {
        val Target = PlaybackTarget(FilesItemId(42L), "episode.mkv")
    }
}
