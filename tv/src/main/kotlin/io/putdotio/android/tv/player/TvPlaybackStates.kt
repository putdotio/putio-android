package io.putdotio.android.tv.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackConversionAction
import io.putdotio.android.playback.PlaybackConversionPolling
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.action
import io.putdotio.android.playback.apiReason
import io.putdotio.android.playback.retryable
import io.putdotio.android.playback.starting
import io.putdotio.android.tv.TvEmptyState
import io.putdotio.android.tv.TvEmptyStateAction
import io.putdotio.android.tv.TvLoadingIndicator
import io.putdotio.android.tv.withFullLineBox
import io.putdotio.sdk.files.PlaybackConversionState
import kotlin.math.roundToInt

internal const val TV_CONVERSION_PERCENT_TAG = "tv-player-conversion-percent"
internal const val TV_CONVERSION_PROGRESS_TAG = "tv-player-conversion-progress"

@Composable
private fun TvPlayerStateFrame(
    target: PlaybackTarget,
    dimmed: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val poster = target.posterUrl.takeIf { dimmed && target.mediaType == PlaybackMediaType.VIDEO }
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = FRAME_DIM)))
        }
        content()
        Box(Modifier.fillMaxSize().tvPlayerEdges()) { TvPlayerTopBar(target.name) }
    }
}

@Composable
internal fun TvPlayerLoading(target: PlaybackTarget) {
    TvPlayerStateFrame(target, dimmed = false) {
        TvLoadingIndicator(stringResource(R.string.tv_player_loading))
    }
}

@Composable
internal fun TvConversionScreen(
    target: PlaybackTarget,
    conversion: PlaybackContent.Conversion,
    onRefresh: () -> Unit,
    onStartConversion: () -> Unit,
    onPlayInVlc: (() -> Unit)? = null,
) {
    PlaybackConversionPolling(conversion, onRefresh)
    val state = conversion.state
    val polling = state == PlaybackConversionState.Queued || state is PlaybackConversionState.Converting
    val running = conversion.starting || polling
    val action = when (conversion.action) {
        PlaybackConversionAction.ConvertAgain -> R.string.tv_player_convert_again to onStartConversion
        PlaybackConversionAction.CheckAgain -> R.string.tv_player_check_again to onRefresh
        null -> (R.string.tv_player_check_again to onRefresh).takeIf { polling }
    }
    TvPlayerStateFrame(target, dimmed = true) {
        TvEmptyState(
            glyph = null,
            headline = stringResource(R.string.tv_player_conversion_title),
            message = if (running) stringResource(R.string.tv_player_conversion_message) else state.message(),
            action = action?.let { (label, onClick) ->
                TvEmptyStateAction(
                    label = stringResource(label),
                    onClick = onClick,
                    icon = R.drawable.ic_ph_arrows_clockwise,
                    enabled = polling || conversion.refreshRequestId == null,
                )
            },
            secondaryAction = onPlayInVlc?.let { onClick ->
                TvEmptyStateAction(
                    label = stringResource(R.string.tv_player_play_in_vlc),
                    onClick = onClick,
                    icon = R.drawable.ic_ph_traffic_cone,
                )
            },
            supporting = if (running) {
                { TvConversionProgress((state as? PlaybackConversionState.Converting)?.percent) }
            } else {
                null
            },
            messageColor = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TvConversionProgress(percent: Double?) {
    Column(
        modifier = Modifier.padding(bottom = ACTION_EXTRA_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val track = Color.White.copy(alpha = PROGRESS_TRACK_ALPHA)
        val bar = Modifier
            .padding(top = PROGRESS_TOP)
            .width(PROGRESS_WIDTH)
            .height(PROGRESS_HEIGHT)
            .clip(CircleShape)
            .testTag(TV_CONVERSION_PROGRESS_TAG)
        if (percent == null) {
            LinearProgressIndicator(
                modifier = bar,
                color = PutioDesignTokens.yellowSolid,
                trackColor = track,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
            )
        } else {
            LinearProgressIndicator(
                progress = { (percent / PERCENT).toFloat().coerceIn(0f, 1f) },
                modifier = bar,
                color = PutioDesignTokens.yellowSolid,
                trackColor = track,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Text(
                text = stringResource(R.string.tv_player_conversion_percent, percent.roundToInt()),
                style = MaterialTheme.typography.bodySmall
                    .copy(
                        fontFamily = PutioTypeScale.tvMono.fontFamily,
                        fontSize = PERCENT_SIZE,
                        lineHeight = PERCENT_LINE,
                    )
                    .withFullLineBox(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = PERCENT_GAP)
                    .testTag(TV_CONVERSION_PERCENT_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun PlaybackConversionState.message(): String =
    when (this) {
        PlaybackConversionState.Queued,
        is PlaybackConversionState.Converting,
        -> stringResource(R.string.tv_player_conversion_message)
        PlaybackConversionState.Completed -> stringResource(R.string.tv_player_conversion_completed)
        PlaybackConversionState.Failed -> stringResource(R.string.tv_player_conversion_failed)
        PlaybackConversionState.NotAvailable -> stringResource(R.string.tv_player_conversion_unavailable)
        is PlaybackConversionState.Unknown -> stringResource(R.string.tv_player_conversion_unknown)
    }

@Composable
internal fun TvPlaybackFailureScreen(
    target: PlaybackTarget,
    failure: PlaybackFailure,
    onRetry: () -> Unit,
    onOpenInVlc: (() -> Unit)?,
) {
    val cantPlay = failure is PlaybackFailure.MediaUnsupported
    TvPlayerStateFrame(target, dimmed = true) {
        TvEmptyState(
            glyph = if (cantPlay) PutioEmptyGlyph.NoResults else PutioEmptyGlyph.CouldNotLoad,
            headline = stringResource(
                if (cantPlay) R.string.tv_player_cant_play_title else R.string.tv_player_error_title,
            ),
            message = if (cantPlay) {
                stringResource(R.string.tv_player_cant_play_message)
            } else {
                failure.apiReason ?: stringResource(failure.messageResource())
            },
            action = when {
                failure.retryable -> TvEmptyStateAction(
                    label = stringResource(R.string.tv_player_retry),
                    onClick = onRetry,
                    icon = R.drawable.ic_ph_arrows_clockwise,
                )
                cantPlay && onOpenInVlc != null -> TvEmptyStateAction(
                    label = stringResource(R.string.tv_player_open_in_vlc),
                    onClick = onOpenInVlc,
                    icon = R.drawable.ic_ph_traffic_cone,
                )
                else -> null
            },
            glyphRunPerCharacter = !cantPlay,
            glyphModifier = if (cantPlay) Modifier.offset(y = -SHRUG_LIFT) else Modifier,
            messageColor = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun TvUnsupportedScreen(target: PlaybackTarget) {
    TvPlayerStateFrame(target, dimmed = true) {
        TvEmptyState(
            glyph = PutioEmptyGlyph.NoResults,
            headline = stringResource(R.string.tv_player_unsupported_title),
            message = null,
        )
    }
}

@StringRes
private fun PlaybackFailure.messageResource(): Int =
    when (this) {
        is PlaybackFailure.MediaCredentialUnavailable -> R.string.tv_player_error_link_expired
        is PlaybackFailure.MediaUnsupported -> R.string.tv_player_cant_play_message
        is PlaybackFailure.Putio -> when (failure) {
            is PutioFailure.AuthenticationRequired -> R.string.tv_error_session
            is PutioFailure.NetworkUnavailable -> R.string.tv_error_network
            is PutioFailure.RateLimited -> R.string.tv_error_rate_limited
            is PutioFailure.AccessDenied -> R.string.tv_player_error_forbidden
            is PutioFailure.ApiRejected,
            is PutioFailure.Misconfigured,
            -> R.string.tv_player_error_rejected
            is PutioFailure.ServerUnavailable,
            is PutioFailure.InvalidResponse,
            is PutioFailure.Unexpected,
            -> R.string.tv_error_unavailable
        }
    }

private const val FRAME_DIM = 0.7f
private const val PERCENT = 100.0
private const val PROGRESS_TRACK_ALPHA = 0.22f
private val PROGRESS_WIDTH = 260.dp
private val PROGRESS_HEIGHT = 4.dp
private val PROGRESS_TOP = 8.dp
private val PERCENT_GAP = 11.dp
private val ACTION_EXTRA_GAP = 3.dp
private val PERCENT_SIZE = 13.sp
private val PERCENT_LINE = 17.sp
private val SHRUG_LIFT = 2.dp
