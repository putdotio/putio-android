package io.putdotio.android.playback

import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.sdk.files.PutioMediaFormat
import io.putdotio.sdk.files.PutioMediaInfo
import io.putdotio.sdk.files.PutioMediaStream
import io.putdotio.sdk.files.PutioPlaybackIssue
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
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class OriginalPlaybackDecisionTest {
    @Test
    fun capturedFilesPlayOriginalOnlyWhereThePhoneDecodesThem() {
        val decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE))

        assertNull(decision.fallbackReason(captured("h264-high-aac.mkv")))
        assertNull(decision.fallbackReason(captured("h264-aac.mp4")))
        assertNull(decision.fallbackReason(captured("mpeg4-asp-mp3.avi")))
        assertNull(decision.fallbackReason(captured("hevc-main10-aac.mkv")))
        assertNull(decision.fallbackReason(captured("hevc-main10-flac-ass-first.mkv")))
        assertEquals(PlaybackFallbackReason.ContainerUnsupported, decision.fallbackReason(captured("wmv3-wmav2.asf")))
        assertEquals(PlaybackFallbackReason.AudioUnsupported, decision.fallbackReason(captured("h264-dts-ass.mkv")))
        assertEquals(PlaybackFallbackReason.AudioUnsupported, decision.fallbackReason(captured("hevc-main10-eac3.mkv")))
        assertEquals(PlaybackFallbackReason.VideoUnsupported, decision.fallbackReason(captured("av1-4k-eac3.mkv")))
        assertEquals(PlaybackFallbackReason.UnknownIssue, decision.fallbackReason(captured("vp8-vorbis-future-issue.webm")))
        assertFalse(decision.canPlayOriginal(captured("wmv3-wmav2.asf"), needConvert = false, isMp4Available = true))
        assertTrue(decision.canPlayOriginal(captured("h264-high-aac.mkv"), needConvert = true, isMp4Available = false))
    }

    @Test
    fun anInventoryThatRejectsHevcMain10PicksConverted() {
        val rejectsMain10 = FakeDecoderInventory(PHONE) { format ->
            format.sampleMimeType == MimeTypes.VIDEO_H265 &&
                MediaCodecUtil.getCodecProfileAndLevel(format)?.first == CodecProfileLevel.HEVCProfileMain10
        }
        val decision = OriginalPlaybackDecision(rejectsMain10)

        assertEquals(PlaybackFallbackReason.VideoUnsupported, decision.fallbackReason(captured("hevc-main10-aac.mkv")))
        assertNull(decision.fallbackReason(captured("h264-high-aac.mkv")))
        assertNull(decision.fallbackReason(mediaInfo(video("hevc", profile = "Main", level = 120), audio("aac"))))
    }

    @Test
    fun oneDecodableOrPassedThroughAudioStreamIsEnough() {
        val tv = FakeDecoderInventory(PHONE + MimeTypes.AUDIO_E_AC3)
        val decision = OriginalPlaybackDecision(tv)

        assertNull(decision.fallbackReason(captured("hevc-main10-4k-dts-eac3-truehd.mkv")))
        assertEquals(
            listOf(MimeTypes.VIDEO_H265, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_E_AC3),
            tv.asked.map { it.sampleMimeType },
        )
        assertNull(decision.fallbackReason(mediaInfo(video("h264"))))
    }

    @Test
    fun playbackIssuesFallBackUnlessTheyAreKnownToPlay() {
        val decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE))
        fun withIssues(vararg issues: String) =
            decision.fallbackReason(
                mediaInfo(video("h264"), audio("aac")).copy(playbackIssues = issues.map(PutioPlaybackIssue::fromRaw)),
            )

        listOf("malformed_sei", "malformed_parameter_sets", "interlaced_video", "excessive_frame_rate").forEach {
            assertEquals(it, PlaybackFallbackReason.PlaybackIssue, withIssues(it, "open_gop"))
        }
        assertNull(withIssues("open_gop", "movie_fragments", "sparse_idr"))
        assertEquals(PlaybackFallbackReason.UnknownIssue, withIssues("sparse_idr", "future_issue"))
    }

    @Test
    fun missingOrUnmappedMediaInfoResolvesConverted() {
        val decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE + MimeTypes.VIDEO_MPEG))

        assertEquals(PlaybackFallbackReason.NoMediaInfo, decision.fallbackReason(null))
        assertEquals(PlaybackFallbackReason.NoMediaInfo, decision.fallbackReason(PutioMediaInfo(format = MATROSKA)))
        assertEquals(
            PlaybackFallbackReason.CodecUnmapped,
            decision.fallbackReason(mediaInfo(video("mpeg1video"), audio("mp2"))),
        )
        assertEquals(PlaybackFallbackReason.CodecUnmapped, decision.fallbackReason(mediaInfo(video("vc1"), audio("aac"))))
        assertEquals(PlaybackFallbackReason.CodecUnmapped, decision.fallbackReason(mediaInfo(video("mjpeg"), audio("aac"))))
        assertEquals(PlaybackFallbackReason.CodecUnmapped, decision.fallbackReason(mediaInfo(video("h264"), audio("wmav2"))))
        assertEquals(
            PlaybackFallbackReason.ContainerUnsupported,
            decision.fallbackReason(mediaInfo(video("h264"), audio("aac")).copy(format = PutioMediaFormat())),
        )
        assertNull(decision.fallbackReason(mediaInfo(video("mjpeg"), video("h264"), audio("aac"))))
    }

    @Test
    fun streamsMapToFormatsMedia3Reads() {
        val h264 = format(captured("h264-high-aac.mkv"), "video")
        val hevc = format(captured("hevc-main10-aac.mkv"), "video")
        val mp4 = format(captured("h264-aac.mp4"), "video")
        val av1 = format(captured("av1-4k-eac3.mkv"), "video")

        assertEquals(MimeTypes.VIDEO_H264, h264.sampleMimeType)
        assertEquals(1276 to 720, h264.width to h264.height)
        assertEquals(
            CodecProfileLevel.AVCProfileHigh to CodecProfileLevel.AVCLevel31,
            MediaCodecUtil.getCodecProfileAndLevel(h264)?.let { it.first to it.second },
        )
        assertEquals(
            CodecProfileLevel.HEVCProfileMain10 to CodecProfileLevel.HEVCMainTierLevel4,
            MediaCodecUtil.getCodecProfileAndLevel(hevc)?.let { it.first to it.second },
        )
        assertEquals("avc1.42001e", mp4.codecs)
        assertEquals(
            CodecProfileLevel.AV1ProfileMain8 to CodecProfileLevel.AV1Level5,
            MediaCodecUtil.getCodecProfileAndLevel(av1)?.let { it.first to it.second },
        )
        assertEquals(MimeTypes.VIDEO_MP4V, format(captured("mpeg4-asp-mp3.avi"), "video").sampleMimeType)
        assertNull(format(captured("vp8-vorbis-future-issue.webm"), "video").codecs)

        val audio = captured("hevc-main10-4k-dts-eac3-truehd.mkv").streams.orEmpty()
            .filter { it.codecType == "audio" }
            .mapNotNull(MediaInfoFormats::toFormat)
        assertEquals(
            listOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_TRUEHD),
            audio.map { it.sampleMimeType },
        )
        assertEquals(listOf(6, 6, 6), audio.map { it.channelCount })
        assertEquals(MimeTypes.AUDIO_MPEG, format(captured("mpeg4-asp-mp3.avi"), "audio").sampleMimeType)
        assertEquals(MimeTypes.AUDIO_DTS_HD, MediaInfoFormats.toFormat(audio("dts", profile = "DTS-HD MA"))?.sampleMimeType)
        assertEquals(MimeTypes.AUDIO_RAW, MediaInfoFormats.toFormat(audio("pcm_s16le"))?.sampleMimeType)
    }

    private fun format(info: PutioMediaInfo, type: String): Format =
        requireNotNull(MediaInfoFormats.toFormat(info.streams.orEmpty().first { it.codecType == type }))

    private fun captured(name: String): PutioMediaInfo {
        val json = requireNotNull(javaClass.getResource("/media-info/$name.json")).readText()
        return Json.decodeFromString(PutioMediaInfo.serializer(), json)
    }

    private fun mediaInfo(vararg streams: PutioMediaStream): PutioMediaInfo =
        PutioMediaInfo(format = MATROSKA, streams = streams.toList(), playbackIssues = emptyList())

    private fun video(codec: String, profile: String? = null, level: Int? = null): PutioMediaStream =
        PutioMediaStream(codecType = "video", codecName = codec, width = 1920, height = 1080, profile = profile, level = level)

    private fun audio(codec: String, profile: String? = null): PutioMediaStream =
        PutioMediaStream(codecType = "audio", codecName = codec, channels = 2, profile = profile)

    private companion object {
        val MATROSKA = PutioMediaFormat(name = "matroska")
        val PHONE = setOf(
            MimeTypes.VIDEO_H264,
            MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_VP8,
            MimeTypes.VIDEO_VP9,
            MimeTypes.VIDEO_MP4V,
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_MPEG,
            MimeTypes.AUDIO_MPEG_L2,
            MimeTypes.AUDIO_OPUS,
            MimeTypes.AUDIO_VORBIS,
            MimeTypes.AUDIO_FLAC,
            MimeTypes.AUDIO_RAW,
        )
    }
}
