package io.putdotio.android

import androidx.annotation.StringRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.putdotio.android.auth.MobileSignedOutReason
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import kotlinx.coroutines.delay
import kotlin.math.abs
import io.putdotio.android.design.R as DesignR

/** One kaomoji over its caption on the Welcome stage. */
internal data class MobileWelcomeFrame(
    @StringRes val kaomoji: Int,
    @StringRes val caption: Int,
)

/** The signed-out sequence, in DESIGN.md's order. */
internal val MobileWelcomeSequence: List<MobileWelcomeFrame> = listOf(
    MobileWelcomeFrame(R.string.mobile_welcome_kaomoji_magnet, R.string.mobile_welcome_caption_magnet),
    MobileWelcomeFrame(R.string.mobile_welcome_kaomoji_fetch, R.string.mobile_welcome_caption_fetch),
    MobileWelcomeFrame(R.string.mobile_welcome_kaomoji_stream, R.string.mobile_welcome_caption_stream),
    MobileWelcomeFrame(R.string.mobile_welcome_kaomoji_offline, R.string.mobile_welcome_caption_offline),
)

private val WaitingFrame = MobileWelcomeFrame(
    R.string.mobile_welcome_kaomoji_waiting,
    R.string.mobile_welcome_caption_waiting,
)

private val FailedFrame = MobileWelcomeFrame(
    R.string.mobile_welcome_kaomoji_failed,
    R.string.mobile_welcome_caption_failed,
)

internal data class MobileWelcomeNotice(
    @StringRes val title: Int,
    @StringRes val message: Int,
)

internal enum class MobileWelcomeActionStyle { Filled, Text }

internal data class MobileWelcomeAction(
    @StringRes val label: Int,
    val style: MobileWelcomeActionStyle,
)

/** The copy and controls of one signed-out moment; layout stays in [MobileWelcomeScreen]. */
internal data class MobileWelcomeContent(
    @StringRes val title: Int,
    @StringRes val lede: Int,
    val frames: List<MobileWelcomeFrame> = MobileWelcomeSequence,
    val notice: MobileWelcomeNotice? = null,
    val action: MobileWelcomeAction? = null,
)

private val SignIn = MobileWelcomeAction(R.string.mobile_auth_sign_in, MobileWelcomeActionStyle.Filled)

internal fun mobileWelcomeContent(
    reason: MobileSignedOutReason?,
    canSignIn: Boolean,
): MobileWelcomeContent =
    when (if (canSignIn) reason else MobileSignedOutReason.OAuthNotConfigured) {
        null -> MobileWelcomeContent(
            title = R.string.mobile_welcome_title,
            lede = R.string.mobile_welcome_lede,
            action = SignIn,
        )

        MobileSignedOutReason.SessionExpired -> MobileWelcomeContent(
            title = R.string.mobile_welcome_back_title,
            lede = R.string.mobile_welcome_expired,
            action = SignIn,
        )

        MobileSignedOutReason.SignInFailed -> MobileWelcomeContent(
            title = R.string.mobile_welcome_title,
            lede = R.string.mobile_welcome_lede,
            frames = listOf(FailedFrame),
            notice = MobileWelcomeNotice(R.string.mobile_welcome_failed_title, R.string.mobile_welcome_failed),
            action = MobileWelcomeAction(R.string.mobile_action_retry, MobileWelcomeActionStyle.Filled),
        )

        MobileSignedOutReason.SecureStorageUnavailable -> MobileWelcomeContent(
            title = R.string.mobile_welcome_title,
            lede = R.string.mobile_welcome_lede,
            frames = listOf(FailedFrame),
            notice = MobileWelcomeNotice(R.string.mobile_auth_storage_title, R.string.mobile_auth_storage_message),
            action = MobileWelcomeAction(R.string.mobile_auth_storage_reset, MobileWelcomeActionStyle.Filled),
        )

        // Without an OAuth client there is nothing to offer: no button.
        MobileSignedOutReason.OAuthNotConfigured -> MobileWelcomeContent(
            title = R.string.mobile_auth_not_configured_title,
            lede = R.string.mobile_auth_not_configured_message,
        )
    }

internal val MobileBrowserWelcomeContent = MobileWelcomeContent(
    title = R.string.mobile_welcome_browser_title,
    lede = R.string.mobile_welcome_browser_lede,
    frames = listOf(WaitingFrame),
    action = MobileWelcomeAction(R.string.mobile_auth_cancel, MobileWelcomeActionStyle.Text),
)

private const val WELCOME_HOLD_MS = 1_900L
private const val WELCOME_ROLL_MS = 600L
private const val WELCOME_FRAME_MS = WELCOME_HOLD_MS + WELCOME_ROLL_MS
private val WelcomeRollEasing = CubicBezierEasing(a = 0.7f, b = 0f, c = 0.2f, d = 1f)

/**
 * Where the Welcome sequence stands [elapsedMs] after it started, in frames: each frame holds, then
 * the next rolls up into its place. It runs from 0 to [frameCount], where the first frame has rolled
 * back in, and starts over at 0.
 */
internal fun welcomeSequencePosition(elapsedMs: Long, frameCount: Int): Float {
    val frame = (elapsedMs / WELCOME_FRAME_MS) % frameCount
    val roll = (elapsedMs % WELCOME_FRAME_MS - WELCOME_HOLD_MS).coerceAtLeast(0L)
    return frame + WelcomeRollEasing.transform(roll.toFloat() / WELCOME_ROLL_MS)
}

private val TOP_BAR_HEIGHT = 64.dp
private val WORDMARK_WIDTH = 88.dp
private val STAGE_TOP = 96.dp
private val STAGE_TOP_SHORT = 24.dp

/** Below the bar, the height the stage and the copy need with the full gap above the stage. */
private val SHORT_WINDOW = 600.dp
private val STAGE_HEIGHT = 220.dp
private val KAOMOJI_SIZE = 48.dp
private val KAOMOJI_LINE = 56.dp
private val DOT = 6.dp
private val ACTIVE_DOT = 18.dp
private val BLOCK_GAP = 16.dp

@Composable
internal fun MobileWelcomeScreen(
    content: MobileWelcomeContent,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(DesignR.drawable.putio_wordmark),
                contentDescription = stringResource(R.string.mobile_welcome_wordmark),
                modifier = Modifier.width(WORDMARK_WIDTH).aspectRatio(WORDMARK_ASPECT_RATIO),
            )
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Short windows (landscape, split screen) drop the gap so the copy rises; the rest scrolls.
            val stageTop = if (maxHeight < SHORT_WINDOW) STAGE_TOP_SHORT else STAGE_TOP
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                WelcomeStage(frames = content.frames, modifier = Modifier.padding(top = stageTop))
                WelcomeCopy(content = content, onAction = onAction, modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

@Composable
private fun WelcomeCopy(
    content: MobileWelcomeContent,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(content.title),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 40.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.01).em,
            ),
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(content.lede),
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = BLOCK_GAP),
        )
        // The notice and the action each sit 24dp below what precedes them; a lone action sits 32dp below the lede.
        content.notice?.let { notice ->
            WelcomeNotice(notice = notice, modifier = Modifier.padding(top = 24.dp))
        }
        content.action?.let { action ->
            WelcomeAction(
                action = action,
                onClick = onAction,
                modifier = Modifier
                    .padding(top = if (content.notice == null) 32.dp else 24.dp)
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            )
        }
    }
}

/**
 * The kaomoji stage: frames roll up one after another, with page dots that follow. It is decoration,
 * so screen readers skip it. Reduced motion, and a stage of one frame, hold the first frame still.
 */
@Composable
private fun WelcomeStage(
    frames: List<MobileWelcomeFrame>,
    modifier: Modifier = Modifier,
) {
    val sequence = frames.size > 1
    val animates = sequence && !rememberMobileMotion().reducedMotion
    val position = remember(frames) { mutableFloatStateOf(0f) }
    LaunchedEffect(frames, animates) {
        position.floatValue = 0f
        if (!animates) return@LaunchedEffect
        val start = withInfiniteAnimationFrameMillis { it }
        while (true) {
            val elapsed = withInfiniteAnimationFrameMillis { it } - start
            position.floatValue = welcomeSequencePosition(elapsed, frames.size)
            val hold = WELCOME_HOLD_MS - elapsed % WELCOME_FRAME_MS
            if (hold > 0) delay(hold)
        }
    }
    Column(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(STAGE_HEIGHT).clipToBounds()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .graphicsLayer { translationY = -position.floatValue * STAGE_HEIGHT.toPx() },
            ) {
                // The first frame repeats at the end, so the last roll brings it back.
                (if (sequence) frames + frames.first() else frames).forEach { frame ->
                    WelcomeFrame(frame = frame, modifier = Modifier.fillMaxWidth().height(STAGE_HEIGHT))
                }
            }
        }
        if (sequence) {
            WelcomeDots(
                count = frames.size,
                position = { position.floatValue },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun WelcomeFrame(
    frame: MobileWelcomeFrame,
    modifier: Modifier = Modifier,
) {
    // The kaomoji is drawn art at a fixed size; the caption scales with the system font size.
    val density = LocalDensity.current
    val kaomojiSize = remember(density) {
        with(density) {
            TextAutoSize.StepBased(minFontSize = (KAOMOJI_SIZE / 2).toSp(), maxFontSize = KAOMOJI_SIZE.toSp())
        }
    }
    val kaomojiStyle = with(density) {
        MaterialTheme.typography.headlineLarge.copy(
            color = LocalContentColor.current,
            fontSize = KAOMOJI_SIZE.toSp(),
            lineHeight = KAOMOJI_LINE.toSp(),
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
        )
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Fallback fonts for glyphs GT America lacks (ಠ, ᕕ, づ) grow the line past its height, so the
        // kaomoji is centred in a box of exactly one line. A window too narrow for it shrinks it to fit.
        BasicText(
            text = stringResource(frame.kaomoji),
            style = kaomojiStyle,
            maxLines = 1,
            softWrap = false,
            autoSize = kaomojiSize,
            modifier = Modifier.height(KAOMOJI_LINE).wrapContentHeight(unbounded = true),
        )
        Text(
            text = stringResource(frame.caption),
            style = PutioTypeScale.mono.copy(fontSize = 15.sp, lineHeight = 20.sp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun WelcomeDots(
    count: Int,
    position: () -> Float,
    modifier: Modifier = Modifier,
) {
    val idle = MaterialTheme.colorScheme.surfaceContainerHighest
    val current = MaterialTheme.colorScheme.onSurface
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            // How current this dot is: 1 while its frame holds, easing to 0 as the next rolls in.
            fun weight(): Float {
                val at = position()
                val distance = minOf(abs(at - index), abs(at - index - count))
                return (1f - distance).coerceIn(0f, 1f)
            }
            Box(
                modifier = Modifier
                    .layout { measurable, _ ->
                        val width = (DOT + (ACTIVE_DOT - DOT) * weight()).roundToPx()
                        val height = DOT.roundToPx()
                        val placeable = measurable.measure(Constraints.fixed(width, height))
                        layout(width, height) { placeable.place(0, 0) }
                    }
                    .drawBehind {
                        drawRoundRect(
                            color = lerp(idle, current, weight()),
                            cornerRadius = CornerRadius(size.height / 2),
                        )
                    },
            )
        }
    }
}

@Composable
private fun WelcomeNotice(
    notice: MobileWelcomeNotice,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(PutioRadius.large),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = stringResource(notice.title),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.height(2.dp))
            Text(text = stringResource(notice.message), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WelcomeAction(
    action: MobileWelcomeAction,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val label: @Composable () -> Unit = {
        Text(
            text = stringResource(action.label),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        )
    }
    when (action.style) {
        MobileWelcomeActionStyle.Filled -> Button(onClick = onClick, modifier = modifier) { label() }
        MobileWelcomeActionStyle.Text -> TextButton(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) { label() }
    }
}

private const val WORDMARK_ASPECT_RATIO = 376f / 112f
