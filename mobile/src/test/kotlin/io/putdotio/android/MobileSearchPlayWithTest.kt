package io.putdotio.android

import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.MobileSearchPlayWith
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileSearchPlayWithTest {
    private val episode = FilesItem(FilesItemId(8), FilesFolder.Root.id, "episode.mkv", PutioFileType.VIDEO, 0, "")
    private val other = FilesItem(FilesItemId(9), FilesFolder.Root.id, "other.mkv", PutioFileType.VIDEO, 0, "")

    @Test
    fun aResultOpenedWithAMethodGoesThroughTheResultOpenAndPlaysOnceWithIt() {
        val playWith = MobileSearchPlayWith()
        val opened = mutableListOf<FilesItem>()

        playWith.open(episode, StreamingMethod.File, opened::add)

        assertEquals(listOf(episode), opened)
        assertEquals(StreamingMethod.File, playWith.take(episode))
        assertNull(playWith.take(episode))
    }

    @Test
    fun anotherResultFollowsTheSetting() {
        val playWith = MobileSearchPlayWith()

        playWith.open(episode, StreamingMethod.Hls) {}

        assertNull(playWith.take(other))
        assertNull(playWith.take(episode))
    }
}
