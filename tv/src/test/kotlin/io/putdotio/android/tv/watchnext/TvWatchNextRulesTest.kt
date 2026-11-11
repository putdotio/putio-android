package io.putdotio.android.tv.watchnext

import org.junit.Assert.assertEquals
import org.junit.Test

class TvWatchNextRulesTest {
    private val video = TvWatchNextMedia(
        fileId = 7L,
        title = "Harbor film.mp4",
        durationSeconds = 600.0,
        posterUrl = "https://api.put.io/screenshots/abc.jpg",
        isVideo = true,
    )

    @Test
    fun `a video saved part way is a Continue card at that position`() {
        assertEquals(
            TvWatchNextChange.Publish(
                TvWatchNextProgram(
                    fileId = 7L,
                    title = "Harbor film.mp4",
                    posterUrl = "https://api.put.io/screenshots/abc.jpg",
                    positionMillis = 125_500L,
                    durationMillis = 600_000L,
                ),
            ),
            watchNextChange(video, 125.5),
        )
        assertEquals(TvWatchNextChange.Publish::class, watchNextChange(video, 0.5)::class)
        assertEquals(TvWatchNextChange.Publish::class, watchNextChange(video, 569.9)::class)
    }

    @Test
    fun `a video at 95 percent or later is finished and leaves the row`() {
        assertEquals(TvWatchNextChange.Remove(7L), watchNextChange(video, 570.0))
        assertEquals(TvWatchNextChange.Remove(7L), watchNextChange(video, 600.0))
    }

    @Test
    fun `a two hour film is finished from 1h54m, well before its last ten seconds`() {
        val film = video.copy(durationSeconds = 7_200.0)

        assertEquals(TvWatchNextChange.Publish::class, watchNextChange(film, 6_839.0)::class)
        assertEquals(TvWatchNextChange.Remove(7L), watchNextChange(film, 6_840.0))
    }

    @Test
    fun `an unwatched video leaves the row`() {
        assertEquals(TvWatchNextChange.Remove(7L), watchNextChange(video, 0.0))
        assertEquals(TvWatchNextChange.Remove(7L), watchNextChange(video.copy(durationSeconds = null), 0.0))
    }

    @Test
    fun `audio, and a video without a duration to show progress against, change nothing`() {
        assertEquals(TvWatchNextChange.None, watchNextChange(video.copy(isVideo = false), 125.0))
        assertEquals(TvWatchNextChange.None, watchNextChange(video.copy(durationSeconds = null), 125.0))
    }
}
