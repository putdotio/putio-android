package io.putdotio.android

import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import io.putdotio.android.playback.PlaybackMediaType

private const val EMULATOR_CODEC_WORKAROUND_API = 37

internal fun interface MobilePlayerFactory {
    fun create(
        context: android.content.Context,
        mediaType: PlaybackMediaType,
    ): Media3Player
}

internal object DefaultMobilePlayerFactory : MobilePlayerFactory {
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun create(
        context: android.content.Context,
        mediaType: PlaybackMediaType,
    ): Media3Player {
        val renderersFactory = DefaultRenderersFactory(context)
        if (requiresEmulatorCodecWorkaround(Build.VERSION.SDK_INT, Build.HARDWARE)) {
            // API 37's goldfish AVC codec can fail its memfd queue before decoding a frame.
            renderersFactory
                .setMediaCodecSelector(EmulatorMediaCodecSelector)
                .setEnableDecoderFallback(true)
        }
        return ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(mediaType.audioAttributes(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}

internal fun PlaybackMediaType.audioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setContentType(
            when (this) {
                PlaybackMediaType.VIDEO -> C.AUDIO_CONTENT_TYPE_MOVIE
                PlaybackMediaType.AUDIO -> C.AUDIO_CONTENT_TYPE_MUSIC
            },
        )
        .setUsage(C.USAGE_MEDIA)
        .build()

internal fun requiresEmulatorCodecWorkaround(
    sdkInt: Int,
    hardware: String,
): Boolean = sdkInt == EMULATOR_CODEC_WORKAROUND_API && (hardware == "ranchu" || hardware == "goldfish")

internal fun emulatorCodecPriority(codecName: String): Int =
    if (codecName.startsWith("c2.goldfish.")) 1 else 0

@UnstableApi
internal fun playbackSurfaceType(
    sdkInt: Int,
    hardware: String,
): Int =
    if (requiresEmulatorCodecWorkaround(sdkInt, hardware)) {
        SURFACE_TYPE_TEXTURE_VIEW
    } else {
        SURFACE_TYPE_SURFACE_VIEW
    }

@UnstableApi
private val EmulatorMediaCodecSelector =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        MediaCodecSelector.DEFAULT
            .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            .sortedBy { emulatorCodecPriority(it.name) }
    }
