package io.putdotio.android

import android.os.Build
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.material3.Player
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackState
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal const val MOBILE_VIDEO_PLAYER_TAG = "mobile-video-player"
private const val MILLIS_PER_SECOND = 1_000.0

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobileVideoPlayerScreen(
    state: PlaybackState,
    onRetry: () -> Unit,
    onMediaRequestFailure: (PlaybackFailure) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val content = state.content) {
            is PlaybackContent.Loading ->
                MobileLoadingState(stringResource(R.string.mobile_playback_loading))

            is PlaybackContent.Ready ->
                MobileReadyVideoPlayer(
                    source = content.source,
                    title = state.target.name,
                    onMediaRequestFailure = onMediaRequestFailure,
                )

            is PlaybackContent.Conversion ->
                MobileErrorState(
                    title = stringResource(R.string.mobile_playback_conversion_title),
                    message = content.state.message(),
                    retryLabel = stringResource(R.string.mobile_playback_check_again),
                    onRetry = onRetry,
                )

            is PlaybackContent.Unsupported ->
                MobileEmptyState(
                    title = stringResource(R.string.mobile_playback_unsupported_title),
                    message = stringResource(R.string.mobile_playback_unsupported_message),
                )

            is PlaybackContent.Failed ->
                MobileErrorState(
                    title = stringResource(R.string.mobile_playback_error_title),
                    message = stringResource(content.failure.messageResource()),
                    retryLabel = stringResource(R.string.mobile_action_retry),
                    onRetry = onRetry,
                )
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_arrow_left),
                contentDescription = stringResource(R.string.mobile_action_back),
            )
        }
    }
}

@UnstableApi
@Composable
private fun MobileReadyVideoPlayer(
    source: PlaybackSource,
    title: String,
    onMediaRequestFailure: (PlaybackFailure) -> Unit,
) {
    val context = LocalContext.current
    val preparedPlayback = remember(source, title) { source.preparePlayback(title) }
    val currentOnMediaRequestFailure = rememberUpdatedState(onMediaRequestFailure)
    val player = remember(context, preparedPlayback) {
        val renderersFactory = DefaultRenderersFactory(context)
        if (requiresEmulatorCodecWorkaround(Build.VERSION.SDK_INT, Build.HARDWARE)) {
            // API 37's goldfish AVC codec can fail its memfd queue before decoding a frame.
            renderersFactory
                .setMediaCodecSelector(EmulatorMediaCodecSelector)
                .setEnableDecoderFallback(true)
        }
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            setMediaItem(preparedPlayback.mediaItem, preparedPlayback.startPositionMillis)
            prepare()
            playWhenReady = true
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        player.pause()
    }
    DisposableEffect(player) {
        val listener =
            object : Media3Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    error.toMediaRequestFailureOrNull()?.let(currentOnMediaRequestFailure.value)
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Player(
        player = player,
        modifier = Modifier
            .fillMaxSize()
            .testTag(MOBILE_VIDEO_PLAYER_TAG),
        surfaceType = playbackSurfaceType(Build.VERSION.SDK_INT, Build.HARDWARE),
    )
}

internal fun requiresEmulatorCodecWorkaround(
    sdkInt: Int,
    hardware: String,
): Boolean = sdkInt == 37 && (hardware == "ranchu" || hardware == "goldfish")

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

internal data class PreparedPlayback(
    val mediaItem: MediaItem,
    val startPositionMillis: Long,
)

internal fun PlaybackSource.preparePlayback(title: String): PreparedPlayback =
    PreparedPlayback(
        mediaItem = toMediaItem(title),
        startPositionMillis = startFromSeconds.toPlaybackMillis(),
    )

internal fun PlaybackSource.toMediaItem(title: String): MediaItem {
    val subtitleConfigurations =
        (subtitles as? PlaybackSubtitles.Sidecar)
            ?.tracks
            .orEmpty()
            .mapNotNull { subtitle ->
                val mimeType = subtitle.format.toSubtitleMimeType() ?: return@mapNotNull null
                MediaItem.SubtitleConfiguration.Builder(subtitle.url.value.toUri())
                    .setId(subtitle.key)
                    .setLabel(subtitle.name)
                    .setLanguage(subtitle.languageCode)
                    .setMimeType(mimeType)
                    .setSelectionFlags(0)
                    .build()
            }

    return MediaItem.Builder()
        .setUri(url.value)
        .setMimeType(if (kind == PlaybackSourceKind.HLS) MimeTypes.APPLICATION_M3U8 else null)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .setSubtitleConfigurations(subtitleConfigurations)
        .build()
}

internal fun Throwable.toMediaRequestFailureOrNull(): PlaybackFailure? {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    var dataSourceFailure: HttpDataSource.HttpDataSourceException? = null
    while (current != null && visited.add(current)) {
        when (current) {
            is HttpDataSource.InvalidResponseCodeException ->
                return PlaybackFailure.MediaCredentialUnavailable(this)

            is HttpDataSource.HttpDataSourceException -> dataSourceFailure = current
        }
        current = current.cause
    }
    return dataSourceFailure?.let { PlaybackFailure.NetworkUnavailable(this) }
}

private fun Double.toPlaybackMillis(): Long =
    (this * MILLIS_PER_SECOND)
        .coerceIn(0.0, Long.MAX_VALUE.toDouble())
        .roundToLong()

private fun String?.toSubtitleMimeType(): String? =
    when (this?.lowercase()) {
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ssa", "ass" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

@Composable
private fun PlaybackConversionState.message(): String =
    when (this) {
        PlaybackConversionState.Queued -> stringResource(R.string.mobile_playback_conversion_queued)
        is PlaybackConversionState.Converting ->
            percent?.roundToInt()?.let {
                stringResource(R.string.mobile_playback_conversion_progress, "$it%")
            } ?: stringResource(R.string.mobile_playback_conversion_working)

        PlaybackConversionState.Completed -> stringResource(R.string.mobile_playback_conversion_completed)
        PlaybackConversionState.Failed -> stringResource(R.string.mobile_playback_conversion_failed)
        PlaybackConversionState.NotAvailable -> stringResource(R.string.mobile_playback_conversion_unavailable)
        is PlaybackConversionState.Unknown -> stringResource(R.string.mobile_playback_conversion_unknown)
    }

@StringRes
private fun PlaybackFailure.messageResource(): Int =
    when (this) {
        is PlaybackFailure.AuthenticationRequired -> R.string.mobile_state_error_session
        is PlaybackFailure.AccessDenied -> R.string.mobile_playback_error_forbidden
        is PlaybackFailure.RateLimited -> R.string.mobile_state_error_rate_limited
        is PlaybackFailure.NetworkUnavailable -> R.string.mobile_state_error_message
        is PlaybackFailure.MediaCredentialUnavailable -> R.string.mobile_playback_error_credential
        is PlaybackFailure.ApiRejected,
        is PlaybackFailure.InvalidResponse,
        is PlaybackFailure.Misconfigured,
        is PlaybackFailure.ServerUnavailable,
        is PlaybackFailure.Unexpected,
        -> R.string.mobile_state_error_unavailable
    }
