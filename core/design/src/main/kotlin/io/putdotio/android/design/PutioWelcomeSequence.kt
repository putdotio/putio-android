package io.putdotio.android.design

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

private const val WELCOME_HOLD_MS = 1_900L
private const val WELCOME_ROLL_MS = 600L
private const val WELCOME_FRAME_MS = WELCOME_HOLD_MS + WELCOME_ROLL_MS
private val WelcomeRollEasing = CubicBezierEasing(a = 0.7f, b = 0f, c = 0.2f, d = 1f)

public fun welcomeSequencePosition(elapsedMs: Long, frameCount: Int): Float {
    val frame = (elapsedMs / WELCOME_FRAME_MS) % frameCount
    val roll = (elapsedMs % WELCOME_FRAME_MS - WELCOME_HOLD_MS).coerceAtLeast(0L)
    return frame + WelcomeRollEasing.transform(roll.toFloat() / WELCOME_ROLL_MS)
}

@Composable
public fun rememberWelcomeSequencePosition(
    frames: List<Any>,
    animates: Boolean,
): FloatState {
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
    return position
}

@Composable
public fun rememberAnimationsOff(): Boolean {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val atLifecycle = remember(lifecycleState) { !ValueAnimator.areAnimatorsEnabled() }
    val resolver = LocalContext.current.contentResolver
    val off = remember { mutableStateOf(atLifecycle) }
    LaunchedEffect(atLifecycle) { off.value = atLifecycle }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                off.value = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return off.value
}
