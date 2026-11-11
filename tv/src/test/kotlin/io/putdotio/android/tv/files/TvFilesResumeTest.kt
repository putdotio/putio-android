package io.putdotio.android.tv.files

import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvFilesResumeTest {
    @Test
    fun aTenMinuteVideoOffersResumeUntil9m30sThenShowsTheWatchedEye() {
        val partWay = video(savedSeconds = 569.0)
        assertEquals(569.0, checkNotNull(partWay.resumePosition(resumePlayback = true)), 0.0)
        assertFalse(partWay.showsWatched)

        val finished = video(savedSeconds = 570.0)
        assertNull(finished.resumePosition(resumePlayback = true))
        assertTrue(finished.showsWatched)
    }

    private fun video(savedSeconds: Double) = FilesItem(
        id = FilesItemId(7L),
        parentId = null,
        name = "Harbor film.mp4",
        type = PutioFileType.VIDEO,
        sizeBytes = 0L,
        createdAt = "",
        playback = FilesPlaybackProgress(savedSeconds, 600.0),
    )
}
