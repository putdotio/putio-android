package io.putdotio.android

import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.session.MediaController
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.files.FilesItemId
import java.io.Closeable
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val EMULATOR_CODEC_WORKAROUND_API = 37

internal fun interface MobilePlayerFactory {
    fun create(
        context: android.content.Context,
        mediaType: PlaybackMediaType,
    ): Media3Player

    /**
     * Attaches to the audio session. [onResult] may run synchronously or later on the
     * main thread; closing the returned handle detaches without stopping playback.
     * The default is a private player that lives and dies with the handle.
     */
    fun connectAudio(
        context: android.content.Context,
        onResult: (Result<Media3Player>) -> Unit,
    ): Closeable {
        val player = create(context, PlaybackMediaType.AUDIO)
        onResult(Result.success(player))
        return Closeable { player.release() }
    }

    /** Ends session audio so a private video player owns the media controls. */
    fun stopAudio(context: android.content.Context) {}

    /** The file the audio session currently holds, or null when idle or unreachable. */
    suspend fun activeAudio(context: android.content.Context): ActiveAudio? =
        suspendCancellableCoroutine { continuation ->
            // The callback may run before connectAudio returns, so whichever side finishes
            // second closes the handle.
            var handle: Closeable? = null
            var delivered = false
            handle = connectAudio(context) { result ->
                val active = result.getOrNull()?.let { live ->
                    live.activeSessionFileId()?.let { id ->
                        ActiveAudio(FilesItemId(id), live.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty())
                    }
                }
                delivered = true
                handle?.closeQuietly()
                if (continuation.isActive) continuation.resume(active)
            }
            if (delivered) handle?.closeQuietly()
            continuation.invokeOnCancellation { handle?.closeQuietly() }
        }
}

// A failed detach must not take the caller's coroutine down with it.
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}

internal data class ActiveAudio(
    val fileId: FilesItemId,
    val title: String,
)

internal object DefaultMobilePlayerFactory : MobilePlayerFactory {
    override fun stopAudio(context: android.content.Context) {
        MobilePlaybackService.stop(context)
    }

    override fun connectAudio(
        context: android.content.Context,
        onResult: (Result<Media3Player>) -> Unit,
    ): Closeable {
        // The controller can outlive the screen that asked for it.
        val appContext = context.applicationContext
        val future =
            MediaController.Builder(appContext, MobilePlaybackService.sessionToken(appContext)).buildAsync()
        future.addListener(
            // A cancelled connection still answers, so no caller waits forever.
            { onResult(runCatching { future.get() }) },
            ContextCompat.getMainExecutor(appContext),
        )
        return Closeable { MediaController.releaseFuture(future) }
    }

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
