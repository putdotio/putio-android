package io.putdotio.android.playback

import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.DirectPlayDownloadSource.Converted
import io.putdotio.android.playback.DirectPlayDownloadSource.Original
import io.putdotio.android.playback.DirectPlayDownloadSource.Unavailable
import io.putdotio.sdk.files.FileSubtitle
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioMediaInfo
import io.putdotio.sdk.files.PutioPlaybackIssue
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DirectPlayDownloadsTest {
    private val files = mutableMapOf<Long, PutioFile>()
    private val subtitleLookups = mutableListOf<Long>()

    @Test
    fun theOriginalWhereThisDeviceCanPlayItElseTheConvertedFile() = runBlocking {
        val downloads = downloads(OriginalVideoPolicy.Check)

        assertEquals(Original, downloads.source(file(H264_MP4, captured("h264-aac.mp4"))))
        assertEquals(Original, downloads.source(file(H264_MKV, captured("h264-high-aac.mkv"))))
        assertEquals(Converted, downloads.source(file(AV1_MKV, captured("av1-4k-eac3.mkv"))))
        assertEquals(Converted, downloads.source(file(UNPROBED, mediaInfo = null)))
    }

    @Test
    fun anyPlaybackIssueDownloadsTheConvertedFileEvenUnderTheDebugForce() = runBlocking {
        val flagged = captured("h264-aac.mp4").copy(playbackIssues = listOf(PutioPlaybackIssue("future_issue")))

        assertEquals(Converted, downloads(OriginalVideoPolicy.Check).source(file(H264_MP4, flagged)))
        assertEquals(Converted, downloads(OriginalVideoPolicy.Always).source(file(H264_MP4, flagged)))
    }

    @Test
    fun aSwitchedFileOrABuildWithoutOriginalsDownloadsConverted() = runBlocking {
        val playable = file(H264_MP4, captured("h264-aac.mp4"))

        assertEquals(Converted, downloads(OriginalVideoPolicy.Never).source(playable))
        assertEquals(Converted, downloads(OriginalVideoPolicy.Check, switched = setOf(H264_MP4)).source(playable))
    }

    @Test
    fun noConvertedCopyAndNoPlayableOriginalIsUnavailableWithoutSubtitles() = runBlocking {
        val downloads = downloads(OriginalVideoPolicy.Check)

        val result = downloads.resolve(file(AV1_MKV, captured("av1-4k-eac3.mkv"), mp4 = false), true)
        assertEquals(DirectPlayDownload(Unavailable), result)
        assertEquals(Unavailable, downloads.source(file(TRACK, mediaInfo = null, type = PutioFileType.AUDIO)))
        assertEquals(emptyList<Long>(), subtitleLookups)
    }

    @Test
    fun subtitleFilesComeWithTheDownloadUnlessTheAccountHidesThem() = runBlocking {
        val downloads = downloads(OriginalVideoPolicy.Check, subtitles = listOf(ENGLISH))
        val video = file(H264_MKV, captured("h264-high-aac.mkv"))

        assertEquals(DirectPlayDownload(Original, listOf(ENGLISH)), downloads.resolve(video, includeSubtitles = true))
        assertEquals(DirectPlayDownload(Original), downloads.resolve(video, includeSubtitles = false))
        assertEquals(listOf(H264_MKV), subtitleLookups)
    }

    @Test
    fun aFailedSubtitleListingKeepsTheFileAndAFailedFileLookupAnswersNothing() = runBlocking {
        val noSubtitles = DirectPlayDownloads(
            originalVideoPolicy = { OriginalVideoPolicy.Check },
            decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE)),
            switchedToConverted = SwitchedToConvertedFiles.None,
            loadFile = { files.getValue(it) },
            listSubtitles = { error("subtitles offline") },
        )
        assertEquals(
            DirectPlayDownload(Converted),
            noSubtitles.resolve(file(AV1_MKV, captured("av1-4k-eac3.mkv")), includeSubtitles = true),
        )
        val offline = DirectPlayDownloads(
            originalVideoPolicy = { OriginalVideoPolicy.Always },
            decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE)),
            switchedToConverted = SwitchedToConvertedFiles.None,
            loadFile = { error("offline") },
            listSubtitles = { emptyList() },
        )
        assertNull(offline.resolve(FilesItemId(H264_MP4), includeSubtitles = true))
    }

    @Test
    fun aSlowSubtitleListingKeepsTheAnswerWithoutSubtitlesAndALookupThatNeverAnswersGivesUp() = runTest {
        val slowSubtitles = DirectPlayDownloads(
            originalVideoPolicy = { OriginalVideoPolicy.Check },
            decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE)),
            switchedToConverted = SwitchedToConvertedFiles.None,
            loadFile = { id ->
                delay(9.seconds)
                files.getValue(id)
            },
            listSubtitles = { awaitCancellation() },
            io = EmptyCoroutineContext,
        )
        val video = file(H264_MKV, captured("h264-high-aac.mkv"))
        assertEquals(DirectPlayDownload(Original), slowSubtitles.resolve(video, includeSubtitles = true))

        val silent = DirectPlayDownloads(
            originalVideoPolicy = { OriginalVideoPolicy.Check },
            decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE)),
            switchedToConverted = SwitchedToConvertedFiles.None,
            loadFile = { awaitCancellation() },
            listSubtitles = { emptyList() },
            io = EmptyCoroutineContext,
        )
        val answer = async { silent.resolve(video, includeSubtitles = true) }
        advanceTimeBy(9.seconds)
        runCurrent()
        assertFalse(answer.isCompleted)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(answer.isCompleted)
        assertNull(answer.await())
    }

    private suspend fun DirectPlayDownloads.source(fileId: FilesItemId): DirectPlayDownloadSource? =
        resolve(fileId, includeSubtitles = false)?.source

    private fun file(
        id: Long,
        mediaInfo: PutioMediaInfo?,
        type: PutioFileType = PutioFileType.VIDEO,
        mp4: Boolean = true,
    ): FilesItemId {
        files[id] = PutioFile(
            id = id,
            name = "file-$id",
            createdAt = "2026-11-06T00:00:00",
            fileType = type,
            needConvert = true,
            isMp4Available = mp4,
            mediaInfo = mediaInfo,
        )
        return FilesItemId(id)
    }

    private fun downloads(
        policy: OriginalVideoPolicy,
        switched: Set<Long> = emptySet(),
        subtitles: List<FileSubtitle> = emptyList(),
    ) = DirectPlayDownloads(
        originalVideoPolicy = { policy },
        decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE)),
        switchedToConverted = object : SwitchedToConvertedFiles {
            override suspend fun contains(fileId: Long): Boolean = fileId in switched

            override fun add(fileId: Long) = Unit
        },
        loadFile = { files.getValue(it) },
        listSubtitles = { id ->
            subtitleLookups += id
            subtitles
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
        val ENGLISH = FileSubtitle(
            format = "srt",
            key = "en-key",
            language = "English",
            languageCode = "eng",
            name = "Sintel.en.srt",
            source = "opensubtitles",
            url = "https://api.put.io/v2/files/15/subtitles/en-key?oauth_token=download",
        )
    }
}
