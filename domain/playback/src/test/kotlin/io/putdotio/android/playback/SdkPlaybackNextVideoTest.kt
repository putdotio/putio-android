package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackPreference
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SdkPlaybackNextVideoTest {
    @Test
    fun nullParentIdInFindNextVideoEndsWithoutListing() =
        runBlocking {
            val repository = SdkPlaybackRepository(
                playbackPreference = { PlaybackPreference.HLS },
                loadAccount = { error("Account must not load") },
                resolvePlayback = { error("Playback must not resolve") },
                loadFile = {
                    PutioFile(
                        id = 42L,
                        name = "video-42.mp4",
                        parentId = null,
                        createdAt = "2026-09-05",
                        fileType = PutioFileType.VIDEO,
                    )
                },
                listFolder = { _, _ -> error("A file with no parent folder must not list") },
                continueListing = { _, _ -> error("Unexpected pagination") },
            )
            assertEquals(
                PlaybackNextResult.Ended,
                repository.findNextVideo(PlaybackTarget(FilesItemId(42L), "episode.mkv")),
            )
        }
}
