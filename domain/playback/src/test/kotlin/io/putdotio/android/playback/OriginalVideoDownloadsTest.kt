package io.putdotio.android.playback

import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioMediaInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OriginalVideoDownloadsTest {
    private val loaded = mutableListOf<Long>()

    @Test
    fun theCheckDownloadsTheOriginalOnlyWhereThePlayerWouldPlayItOverHls() = runBlocking {
        val downloads = downloads(OriginalVideoPolicy.Check)

        assertEquals(true, downloads.accepts(file(H264_MP4, captured("h264-aac.mp4"))))
        assertEquals(true, downloads.accepts(file(H264_MKV, captured("h264-high-aac.mkv"))))
        assertEquals(false, downloads.accepts(file(AV1_MKV, captured("av1-4k-eac3.mkv"))))
        assertEquals(false, downloads.accepts(file(UNPROBED, mediaInfo = null)))
        assertEquals(listOf(H264_MP4, H264_MKV, AV1_MKV, UNPROBED), loaded)
    }

    @Test
    fun standardPlaybackAndSwitchedFilesDownloadConvertedWithoutAsking() = runBlocking {
        assertEquals(false, downloads(OriginalVideoPolicy.Never).accepts(file(H264_MP4, captured("h264-aac.mp4"))))
        assertEquals(
            false,
            downloads(OriginalVideoPolicy.Check, switched = setOf(H264_MP4))
                .accepts(file(H264_MP4, captured("h264-aac.mp4"))),
        )
        assertEquals(emptyList<Long>(), loaded)
    }

    @Test
    fun theDebugForceTakesEveryVideoButNoOtherType() = runBlocking {
        val downloads = downloads(OriginalVideoPolicy.Always)

        assertEquals(true, downloads.accepts(file(UNPROBED, mediaInfo = null)))
        assertEquals(false, downloads.accepts(file(TRACK, mediaInfo = null, type = PutioFileType.AUDIO)))
    }

    @Test
    fun aFailedLookupAnswersNothing() = runBlocking {
        val downloads = OriginalVideoDownloads(
            originalVideoPolicy = { OriginalVideoPolicy.Always },
            decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE)),
            switchedToConverted = SwitchedToConvertedFiles.None,
            loadFile = { error("offline") },
        )

        assertNull(downloads.accepts(FilesItemId(H264_MP4)))
        val unreadable = OriginalVideoDownloads(
            originalVideoPolicy = { OriginalVideoPolicy.Check },
            decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE)),
            switchedToConverted = object : SwitchedToConvertedFiles {
                override suspend fun contains(fileId: Long): Boolean = error("corrupt store")

                override fun add(fileId: Long) = Unit
            },
            loadFile = { error("not reached") },
        )
        assertNull(unreadable.accepts(FilesItemId(H264_MP4)))
    }

    private val files = mutableMapOf<Long, PutioFile>()

    private fun file(id: Long, mediaInfo: PutioMediaInfo?, type: PutioFileType = PutioFileType.VIDEO): FilesItemId {
        files[id] = PutioFile(
            id = id,
            name = "file-$id",
            createdAt = "2026-11-06T00:00:00",
            fileType = type,
            needConvert = true,
            mediaInfo = mediaInfo,
        )
        return FilesItemId(id)
    }

    private fun downloads(policy: OriginalVideoPolicy, switched: Set<Long> = emptySet()) = OriginalVideoDownloads(
        originalVideoPolicy = { policy },
        decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE)),
        switchedToConverted = object : SwitchedToConvertedFiles {
            override suspend fun contains(fileId: Long): Boolean = fileId in switched

            override fun add(fileId: Long) = Unit
        },
        loadFile = { id ->
            loaded += id
            files.getValue(id)
        },
    )

    private fun captured(name: String): PutioMediaInfo {
        val json = requireNotNull(javaClass.getResource("/media-info/$name.json")).readText()
        return Json.decodeFromString(PutioMediaInfo.serializer(), json)
    }

    private companion object {
        const val H264_MP4 = 11L
        const val AV1_MKV = 12L
        const val UNPROBED = 13L
        const val TRACK = 14L
        const val H264_MKV = 15L
        val PHONE = setOf(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_E_AC3)
    }
}
