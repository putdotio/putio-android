package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackFolderQueueTest {
    @Test
    fun previousPlaysTheFileBeforeOnlyInATracksFirstSeconds() {
        val previous = PlaybackTarget(FilesItemId(40L), "01 song.mp3", PlaybackMediaType.AUDIO)
        val queue = PlaybackNeighbours(previous = previous)

        assertEquals(previous, queue.previousAt(0L))
        assertEquals(previous, queue.previousAt(2_999L))
        assertNull(queue.previousAt(3_000L))
        assertNull(PlaybackNeighbours().previousAt(0L))
    }
}
