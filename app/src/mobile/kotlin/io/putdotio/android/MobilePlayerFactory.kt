package io.putdotio.android

import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.putdotio.android.downloads.MobileDownloadCache
import androidx.media3.session.MediaController
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.audioAttributes
import io.putdotio.android.playback.playbackRenderersFactory
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.auth.MobileOAuthRuntime
import java.io.Closeable
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun interface MobilePlayerFactory {
    fun create(
        context: android.content.Context,
        mediaType: PlaybackMediaType,
    ): Media3Player

    fun reportableItem(item: MediaItem, useStartFrom: Boolean): MediaItem = item

    fun observePositions(context: android.content.Context, player: Media3Player): Closeable = Closeable {}

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

// A failed detach must not take the caller's coroutine or composition down with it.
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun Closeable.closeQuietly() {
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
    override fun observePositions(context: android.content.Context, player: Media3Player): Closeable =
        MobileOAuthRuntime.get(context).playbackReporting.observe(player)

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
        return ExoPlayer.Builder(context, playbackRenderersFactory(context))
            // Completed downloads play from the cache; everything else streams through the same source.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    MobileDownloadCache.get(context).let { it.playbackFactory(it.activeUserId ?: NO_DOWNLOAD_USER) },
                ),
            )
            .setAudioAttributes(mediaType.audioAttributes(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}

/** Players created before sign-in hold no user; the cache then never matches and everything streams. */
private const val NO_DOWNLOAD_USER = -1L
