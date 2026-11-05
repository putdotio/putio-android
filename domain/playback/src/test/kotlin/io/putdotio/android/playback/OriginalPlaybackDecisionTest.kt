package io.putdotio.android.playback

import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.playback.PlaybackFallbackReason.AudioUnsupported
import io.putdotio.android.playback.PlaybackFallbackReason.CodecUnmapped
import io.putdotio.android.playback.PlaybackFallbackReason.ContainerUnsupported
import io.putdotio.android.playback.PlaybackFallbackReason.NoMediaInfo
import io.putdotio.android.playback.PlaybackFallbackReason.PlaybackIssue
import io.putdotio.android.playback.PlaybackFallbackReason.UnknownIssue
import io.putdotio.android.playback.PlaybackFallbackReason.VideoUnsupported
import io.putdotio.sdk.files.PlaybackPreference
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

        assertNull(decision.reason(captured("h264-high-aac.mkv")))
        assertNull(decision.reason(captured("h264-aac.mp4")))
        assertNull(decision.reason(captured("mpeg4-asp-mp3.avi")))
        assertNull(decision.reason(captured("hevc-main10-aac.mkv")))
        assertNull(decision.reason(captured("hevc-main10-flac-ass-first.mkv")))
        assertEquals(ContainerUnsupported, decision.reason(captured("wmv3-wmav2.asf")))
        assertEquals(AudioUnsupported, decision.reason(captured("h264-dts-ass.mkv")))
        assertEquals(AudioUnsupported, decision.reason(captured("hevc-main10-eac3.mkv")))
        assertEquals(VideoUnsupported, decision.reason(captured("av1-4k-eac3.mkv")))
        assertEquals(
            UnknownIssue,
            decision.reason(captured("vp8-vorbis-future-issue.webm")),
        )
        assertFalse(decision.forPreference(MP4).canPlayOriginal(captured("wmv3-wmav2.asf"), false, true))
        assertTrue(decision.forPreference(MP4).canPlayOriginal(captured("h264-high-aac.mkv"), true, false))
    }

    @Test
    fun hlsOriginalsAreMp4OnlyAsPutIoServesThem() {
        val decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE))

        assertNull(decision.reason(captured("h264-aac.mp4"), HLS))
        assertEquals(ContainerUnsupported, decision.reason(captured("h264-high-aac.mkv"), HLS))
        assertEquals(ContainerUnsupported, decision.reason(captured("mpeg4-asp-mp3.avi"), HLS))
        assertFalse(decision.forPreference(HLS).canPlayOriginal(captured("h264-high-aac.mkv"), true, false))
        assertTrue(decision.forPreference(MP4).canPlayOriginal(captured("h264-high-aac.mkv"), true, false))
    }

    @Test
    fun anInventoryThatRejectsHevcMain10PicksConverted() {
        val rejectsMain10 = FakeDecoderInventory(PHONE) { format ->
            format.sampleMimeType == MimeTypes.VIDEO_H265 &&
                MediaCodecUtil.getCodecProfileAndLevel(format)?.first == CodecProfileLevel.HEVCProfileMain10
        }
        val decision = OriginalPlaybackDecision(rejectsMain10)

        assertEquals(VideoUnsupported, decision.reason(captured("hevc-main10-aac.mkv")))
        assertNull(decision.reason(captured("h264-high-aac.mkv")))
        assertNull(decision.reason(mediaInfo(video("hevc", profile = "Main", level = 120), audio("aac"))))
    }

    @Test
    fun oneDecodableOrPassedThroughAudioStreamIsEnough() {
        val tv = FakeDecoderInventory(PHONE + MimeTypes.AUDIO_E_AC3)
        val decision = OriginalPlaybackDecision(tv)

        assertNull(decision.reason(captured("hevc-main10-4k-dts-eac3-truehd.mkv")))
        assertEquals(
            listOf(MimeTypes.VIDEO_H265, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_E_AC3),
            tv.asked.map { it.sampleMimeType },
        )
        assertNull(decision.reason(mediaInfo(video("h264"))))
    }

    @Test
    fun playbackIssuesFallBackUnlessTheyAreKnownToPlay() {
        val decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE))
        fun withIssues(vararg issues: String) =
            decision.reason(
                mediaInfo(video("h264"), audio("aac")).copy(playbackIssues = issues.map(PutioPlaybackIssue::fromRaw)),
            )

        listOf("malformed_sei", "malformed_parameter_sets", "interlaced_video", "excessive_frame_rate").forEach {
            assertEquals(it, PlaybackIssue, withIssues(it, "open_gop"))
        }
        assertNull(withIssues("open_gop", "movie_fragments", "sparse_idr"))
        assertEquals(UnknownIssue, withIssues("sparse_idr", "future_issue"))
    }

    @Test
    fun missingOrUnmappedMediaInfoResolvesConverted() {
        val decision = OriginalPlaybackDecision(FakeDecoderInventory(PHONE + MimeTypes.VIDEO_MPEG))

        assertEquals(NoMediaInfo, decision.reason(null))
        assertEquals(NoMediaInfo, decision.reason(PutioMediaInfo(format = MATROSKA)))
        assertEquals(
            CodecUnmapped,
            decision.reason(mediaInfo(video("mpeg1video"), audio("mp2"))),
        )
        listOf(video("vc1") to audio("aac"), video("mjpeg") to audio("aac"), video("h264") to audio("wmav2"))
            .forEach { (video, audio) ->
                assertEquals(CodecUnmapped, decision.reason(mediaInfo(video, audio)))
            }
        assertEquals(
            ContainerUnsupported,
            decision.reason(mediaInfo(video("h264"), audio("aac")).copy(format = PutioMediaFormat())),
        )
        assertNull(decision.reason(mediaInfo(video("mjpeg"), video("h264"), audio("aac"))))
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
        assertEquals(
            MimeTypes.AUDIO_DTS_HD,
            MediaInfoFormats.toFormat(audio("dts", profile = "DTS-HD MA"))?.sampleMimeType,
        )
        assertEquals(MimeTypes.AUDIO_RAW, MediaInfoFormats.toFormat(audio("pcm_s16le"))?.sampleMimeType)
    }

    private fun OriginalPlaybackDecision.reason(info: PutioMediaInfo?, preference: PlaybackPreference = MP4) =
        fallbackReason(info, preference)

    private fun format(info: PutioMediaInfo, type: String): Format =
        requireNotNull(MediaInfoFormats.toFormat(info.streams.orEmpty().first { it.codecType == type }))

    private fun captured(name: String): PutioMediaInfo {
        val json = requireNotNull(javaClass.getResource("/media-info/$name.json")).readText()
        return Json.decodeFromString(PutioMediaInfo.serializer(), json)
    }

    private fun mediaInfo(vararg streams: PutioMediaStream): PutioMediaInfo =
        PutioMediaInfo(format = MATROSKA, streams = streams.toList(), playbackIssues = emptyList())

    private fun video(codec: String, profile: String? = null, level: Int? = null): PutioMediaStream =
        PutioMediaStream(
            codecType = "video",
            codecName = codec,
            width = 1920,
            height = 1080,
            profile = profile,
            level = level,
        )

    private fun audio(codec: String, profile: String? = null): PutioMediaStream =
        PutioMediaStream(codecType = "audio", codecName = codec, channels = 2, profile = profile)

    private companion object {
        val MATROSKA = PutioMediaFormat(name = "matroska")
        val HLS = PlaybackPreference.HLS
        val MP4 = PlaybackPreference.MP4
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
