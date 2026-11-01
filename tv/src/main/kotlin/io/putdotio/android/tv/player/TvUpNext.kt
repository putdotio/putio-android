package io.putdotio.android.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.putioFailure
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvButtonSize
import io.putdotio.android.tv.TvButtonStyle
import io.putdotio.android.tv.withFullLineBox
import kotlin.math.ceil

internal const val TV_UP_NEXT_TAG = "tv-player-up-next"
internal const val TV_UP_NEXT_MILLIS = 10_000L

internal data class TvUpNext(val target: PlaybackTarget, val folderName: String?)

internal sealed interface TvUpNextLookup {
    data object Off : TvUpNextLookup

    data object Pending : TvUpNextLookup

    data class Found(val upNext: TvUpNext) : TvUpNextLookup

    data object FolderEnd : TvUpNextLookup

    data object Failed : TvUpNextLookup
}

internal typealias TvFindNeighbours = suspend (PlaybackTarget) -> PlaybackRepositoryResult<PlaybackNeighbours>

@Composable
internal fun rememberUpNext(
    state: PlaybackState,
    findNeighbours: TvFindNeighbours?,
    onAuthenticationRequired: suspend () -> Unit,
): TvUpNextLookup {
    val ready = state.content is PlaybackContent.Ready && state.target.mediaType == PlaybackMediaType.VIDEO
    val initial = if (findNeighbours == null) TvUpNextLookup.Off else TvUpNextLookup.Pending
    val lookup by produceState(initial, state.target.fileId, ready, findNeighbours != null) {
        value = initial
        val find = findNeighbours ?: return@produceState
        if (!ready) return@produceState
        value = when (val found = find(state.target)) {
            is PlaybackRepositoryResult.Success ->
                found.value.next?.takeUnless { it.fileId in state.visitedFileIds }
                    ?.let { TvUpNextLookup.Found(TvUpNext(it, found.value.folderName)) }
                    ?: TvUpNextLookup.FolderEnd
            is PlaybackRepositoryResult.Failure -> {
                if (found.failure.putioFailure is PutioFailure.AuthenticationRequired) onAuthenticationRequired()
                TvUpNextLookup.Failed
            }
        }
    }
    return lookup
}

internal fun TvPlayerOverlay.ended(autoplayNext: Boolean = false, lookup: TvUpNextLookup = TvUpNextLookup.Off) =
    when {
        exited -> TvPlayerTransition(this)
        !autoplayNext -> exit()
        else -> copy(awaitingUpNext = true, controlsVisible = true, scrub = null, sheet = null, activity = activity + 1)
            .upNextResolved(lookup)
    }

internal fun TvPlayerOverlay.upNextResolved(lookup: TvUpNextLookup): TvPlayerTransition =
    if (!awaitingUpNext || exited) {
        TvPlayerTransition(this)
    } else {
        when (lookup) {
            TvUpNextLookup.Pending -> TvPlayerTransition(this)
            is TvUpNextLookup.Found -> TvPlayerTransition(copy(awaitingUpNext = false, upNext = true))
            TvUpNextLookup.Off, TvUpNextLookup.FolderEnd, TvUpNextLookup.Failed ->
                copy(awaitingUpNext = false).playNext()
        }
    }

internal fun TvPlayerOverlay.playUpNext(target: PlaybackTarget): TvPlayerTransition =
    if (upNext && !exited) {
        TvPlayerTransition(
            copy(upNext = false, exited = true, scrub = null, sheet = null),
            listOf(TvPlayerCommand.PlayChosen(target)),
        )
    } else {
        TvPlayerTransition(this)
    }

internal fun TvPlayerOverlay.cancelUpNext(): TvPlayerTransition =
    if (upNextPending) {
        TvPlayerTransition(copy(upNext = false, awaitingUpNext = false, activity = activity + 1))
    } else {
        TvPlayerTransition(this)
    }

internal val TvPlayerOverlay.upNextPending: Boolean
    get() = upNext || awaitingUpNext

internal val TvPlayerOverlay.holdsPlayerFocus: Boolean
    get() = sheet == null && !upNext

internal fun TvPlayerOverlay.shownFocus(chips: List<TvPlayerControl>): TvPlayerControl =
    if (upNext) TvPlayerControl.PlayPause else focusIn(chips)

@Composable
internal fun TvUpNextOverlay(
    overlay: TvPlayerOverlay,
    lookup: TvUpNextLookup,
    update: ((TvPlayerOverlay) -> TvPlayerTransition) -> Unit,
) {
    LaunchedEffect(overlay.awaitingUpNext, lookup) { update { it.upNextResolved(lookup) } }
    val found = lookup as? TvUpNextLookup.Found
    if (!overlay.upNext || found == null) return
    TvUpNextCard(
        upNext = found.upNext,
        onPlayNow = { update { it.playUpNext(found.upNext.target) } },
        onCancel = { update { it.cancelUpNext() } },
    )
}

@Composable
private fun TvUpNextCard(
    upNext: TvUpNext,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val currentOnPlayNow by rememberUpdatedState(onPlayNow)
    var remainingMillis by remember { mutableLongStateOf(TV_UP_NEXT_MILLIS) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (remainingMillis > 0L) {
            remainingMillis = withFrameMillis { now -> (TV_UP_NEXT_MILLIS - (now - start)).coerceAtLeast(0L) }
        }
        currentOnPlayNow()
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().tvPlayerEdges()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = UP_NEXT_ABOVE_CONTROLS)
                .width(UP_NEXT_WIDTH)
                .clip(RoundedCornerShape(PutioRadius.large))
                .background(UP_NEXT_FILL)
                .padding(UP_NEXT_PADDING)
                .testTag(TV_UP_NEXT_TAG),
        ) {
            val secondary = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text = upNext.folderName?.let { stringResource(R.string.tv_player_up_next_in, it) }
                    ?: stringResource(R.string.tv_player_up_next),
                style = PutioTypeScale.tvMeta.copy(fontSize = UP_NEXT_HEADER_SIZE, lineHeight = UP_NEXT_HEADER_LINE)
                    .withFullLineBox(),
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = upNext.target.name,
                style = PutioTypeScale.tvDetailTitle.copy(fontSize = UP_NEXT_NAME_SIZE, lineHeight = UP_NEXT_NAME_LINE)
                    .withFullLineBox(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            val seconds = ceil(remainingMillis / MILLIS_PER_SECOND).toLong().toString()
            Text(
                text = countdown(stringResource(R.string.tv_player_up_next_countdown, seconds), seconds),
                style = PutioTypeScale.tvMeta.copy(lineHeight = UP_NEXT_COUNTDOWN_LINE).withFullLineBox(),
                color = secondary,
                modifier = Modifier.padding(top = 3.dp),
            )
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(UP_NEXT_LINE)
                    .clip(RoundedCornerShape(UP_NEXT_LINE / 2))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = UP_NEXT_TRACK_ALPHA)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(1f - remainingMillis.toFloat() / TV_UP_NEXT_MILLIS)
                        .height(UP_NEXT_LINE)
                        .background(PutioDesignTokens.yellowSolid),
                )
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvButton(
                    onClick = onPlayNow,
                    size = TvButtonSize.Card,
                    modifier = Modifier.focusRequester(focus),
                ) {
                    Icon(
                        painter = painterResource(DesignR.drawable.ic_ph_play_fill),
                        contentDescription = null,
                        modifier = Modifier.size(TvButtonSize.Card.icon),
                    )
                    Text(stringResource(R.string.tv_player_play_now), style = PutioTypeScale.tvLabel)
                }
                TvButton(onClick = onCancel, size = TvButtonSize.Card, style = TvButtonStyle.Text) {
                    Text(stringResource(R.string.tv_dialog_cancel), style = PutioTypeScale.tvLabel)
                }
            }
        }
    }
}

private fun countdown(text: String, seconds: String): AnnotatedString {
    val start = text.lastIndexOf(seconds)
    if (start < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(fontFamily = PutioTypeScale.tvMono.fontFamily), start, start + seconds.length)
    }
}

private const val MILLIS_PER_SECOND = 1_000.0
private val UP_NEXT_FILL = Color(red = 30, green = 30, blue = 30, alpha = 235)
private const val UP_NEXT_TRACK_ALPHA = 0.2f
private val UP_NEXT_WIDTH = 280.dp
private val UP_NEXT_PADDING = 14.dp
private val UP_NEXT_ABOVE_CONTROLS = 89.dp
private val UP_NEXT_LINE = 3.dp
private val UP_NEXT_HEADER_SIZE = 12.sp
private val UP_NEXT_HEADER_LINE = 16.sp
private val UP_NEXT_COUNTDOWN_LINE = 17.5.sp
private val UP_NEXT_NAME_SIZE = 15.sp
private val UP_NEXT_NAME_LINE = 19.sp
