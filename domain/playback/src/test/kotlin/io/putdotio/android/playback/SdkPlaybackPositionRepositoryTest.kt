package io.putdotio.android.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkPlaybackPositionRepositoryTest {
    @Test
    fun aZeroPositionClearsTheSavedOneInsteadOfWritingIt() = runBlocking {
        val calls = mutableListOf<String>()
        val positions = SdkPlaybackPositionRepository(
            setPosition = { fileId, seconds -> calls += "set $fileId $seconds" },
            clearPosition = { fileId -> calls += "reset $fileId" },
        )
        assertTrue(positions.write(7L, 0.0) is PlaybackRepositoryResult.Success)
        assertTrue(positions.write(7L, 12.5) is PlaybackRepositoryResult.Success)
        assertTrue(positions.write(7L, -1.0) is PlaybackRepositoryResult.Failure)
        assertEquals(listOf("reset 7", "set 7 12.5"), calls)
    }
}
