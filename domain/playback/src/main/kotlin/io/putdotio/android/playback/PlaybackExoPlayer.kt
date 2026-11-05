package io.putdotio.android.playback

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW

private const val EMULATOR_CODEC_WORKAROUND_API = 37

/** The renderers every ExoPlayer in the app starts from, on either surface; text takes a timing offset. */
@UnstableApi
public fun playbackRenderersFactory(context: Context): DefaultRenderersFactory {
    val renderersFactory: DefaultRenderersFactory = SubtitleTimingRenderersFactory(context)
    if (requiresEmulatorCodecWorkaround(Build.VERSION.SDK_INT, Build.HARDWARE)) {
        // API 37's goldfish AVC codec can fail its memfd queue before decoding a frame.
        renderersFactory
            .setMediaCodecSelector(EmulatorMediaCodecSelector)
            .setEnableDecoderFallback(true)
    }
    return renderersFactory
}

/**
 * The load control every ExoPlayer in the app starts from. Media3 sizes its buffer for the
 * selected tracks, up to 125 MiB for video, and holds it on the Java heap.
 */
@UnstableApi
public fun playbackLoadControl(context: Context): DefaultLoadControl.Builder {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    return DefaultLoadControl.Builder()
        .setTargetBufferBytes(bufferTargetBytes(activityManager.memoryClass, activityManager.isLowRamDevice))
}

internal fun bufferTargetBytes(
    memoryClassMb: Int,
    isLowRam: Boolean,
): Int = BYTES_PER_MIB * when {
    isLowRam || memoryClassMb < SMALL_HEAP_MB -> LOW_RAM_BUFFER_MIB
    memoryClassMb < MEDIUM_HEAP_MB -> SMALL_HEAP_BUFFER_MIB
    memoryClassMb < LARGE_HEAP_MB -> MEDIUM_HEAP_BUFFER_MIB
    else -> LARGE_HEAP_BUFFER_MIB
}

private const val BYTES_PER_MIB = 1024 * 1024
private const val SMALL_HEAP_MB = 128
private const val MEDIUM_HEAP_MB = 256
private const val LARGE_HEAP_MB = 512
private const val LOW_RAM_BUFFER_MIB = 16
private const val SMALL_HEAP_BUFFER_MIB = 40
private const val MEDIUM_HEAP_BUFFER_MIB = 64
private const val LARGE_HEAP_BUFFER_MIB = 96

public fun PlaybackMediaType.audioAttributes(): AudioAttributes =
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
public fun playbackSurfaceType(
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
