package io.putdotio.android.playback

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.sdk.files.PutioMediaFormat
import io.putdotio.sdk.files.PutioMediaInfo
import io.putdotio.sdk.files.PutioMediaStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MediaCodecDecoderInventoryTest {
    private val decision = OriginalPlaybackDecision(
        MediaCodecDecoderInventory(ApplicationProvider.getApplicationContext()) { false },
    )

    @Before
    @After
    fun clearDecoders() {
        ShadowMediaCodecList.reset()
        MediaCodecUtil.clearDecoderInfoCache()
    }

    @Test
    fun aBackwardCompatibleDolbyVisionMatroskaPlaysOnItsHevcBaseLayer() {
        addVideoDecoder(
            MimeTypes.VIDEO_H265,
            CodecProfileLevel.HEVCProfileMain10 to CodecProfileLevel.HEVCMainTierLevel62,
            CodecProfileLevel.HEVCProfileMain10HDR10 to CodecProfileLevel.HEVCMainTierLevel62,
        )

        assertNull(decision.fallbackReason(dolbyVision(profile = 8)))
        assertEquals(PlaybackFallbackReason.VideoUnsupported, decision.fallbackReason(dolbyVision(profile = 5)))
        assertEquals(PlaybackFallbackReason.VideoUnsupported, decision.fallbackReason(dolbyVision(profile = 7)))
    }

    @Test
    fun aDolbyVisionDecoderPlaysEveryProfileItReports() {
        addVideoDecoder(
            MimeTypes.VIDEO_DOLBY_VISION,
            CodecProfileLevel.DolbyVisionProfileDvheStn to CodecProfileLevel.DolbyVisionLevelUhd60,
        )

        assertNull(decision.fallbackReason(dolbyVision(profile = 5)))
        assertEquals(PlaybackFallbackReason.VideoUnsupported, decision.fallbackReason(dolbyVision(profile = 8)))
    }

    @Test
    fun dolbyVisionProfile10RequiresDolbyVisionWithoutBaseLayerCompatibility() {
        addVideoDecoder(
            MimeTypes.VIDEO_AV1,
            CodecProfileLevel.AV1ProfileMain10 to CodecProfileLevel.AV1Level62,
        )

        assertEquals(
            PlaybackFallbackReason.VideoUnsupported,
            decision.fallbackReason(dolbyVision(profile = 10, codec = "av1")),
        )
    }

    private fun addVideoDecoder(mimeType: String, vararg profileLevels: Pair<Int, Int>) {
        val reported = profileLevels.map { (profile, level) ->
            CodecProfileLevel().apply {
                this.profile = profile
                this.level = level
            }
        }
        ShadowMediaCodecList.addCodec(
            MediaCodecInfoBuilder.newBuilder()
                .setName("decoder.$mimeType")
                .setIsEncoder(false)
                .setCapabilities(
                    MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                        .setMediaFormat(MediaFormat.createVideoFormat(mimeType, UHD_WIDTH, UHD_HEIGHT))
                        .setIsEncoder(false)
                        .setProfileLevels(reported.toTypedArray())
                        .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
                        .build(),
                )
                .build(),
        )
    }

    private fun dolbyVision(profile: Int, codec: String = "hevc"): PutioMediaInfo =
        PutioMediaInfo(
            format = PutioMediaFormat(name = "matroska,webm"),
            streams = listOf(
                PutioMediaStream(
                    codecType = "video",
                    codecName = codec,
                    rfc6381Codec = "",
                    width = UHD_WIDTH,
                    height = UHD_HEIGHT,
                    profile = "Main 10",
                    level = 153,
                    bitDepth = 10,
                    isHdr = true,
                    dolbyVisionProfile = profile,
                ),
                PutioMediaStream(codecType = "audio", codecName = "pcm_s16le", channels = 2, isDefault = true),
            ),
            playbackIssues = emptyList(),
        )

    private companion object {
        const val UHD_WIDTH = 3840
        const val UHD_HEIGHT = 2160
    }
}
