package io.putdotio.android

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.putdotio.android.design.PutioRadius
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Material's standard motion scheme springs. Material 3 1.4 keeps `MotionScheme` internal, so these
 * repeat its `StandardMotionTokens` until the scheme is public.
 */
internal object MobileMotionSprings {
    private const val SPATIAL_DAMPING = 0.9f
    private const val EFFECTS_DAMPING = 1f
    private const val DEFAULT_SPATIAL_STIFFNESS = 700f
    private const val DEFAULT_EFFECTS_STIFFNESS = 1600f
    private const val FAST_EFFECTS_STIFFNESS = 3800f

    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, DEFAULT_SPATIAL_STIFFNESS)

    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, DEFAULT_EFFECTS_STIFFNESS)

    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, FAST_EFFECTS_STIFFNESS)
}

/**
 * Fade through runs in two phases, which springs cannot delay: Material's 300 ms, the outgoing tab
 * fading out in the first 90 ms and the incoming one fading and scaling in after it.
 */
private fun <T> fadeThroughIn(): FiniteAnimationSpec<T> =
    tween(FADE_THROUGH_IN_MS, delayMillis = FADE_THROUGH_OUT_MS, easing = LinearOutSlowInEasing)

/** How one screen replaces another (DESIGN.md Motion). */
internal enum class MobileTransition { FadeThrough, Push, Pop, Container, Fade }

/**
 * Between tabs: fade through. Within a tab: shared axis X, forward on a push and back on a pop.
 * The player opens as a container transform from its row. Reduced motion turns each into a fade.
 */
internal fun mobileTransition(from: String?, to: String?, pop: Boolean, reducedMotion: Boolean): MobileTransition =
    when {
        reducedMotion -> MobileTransition.Fade
        from == MOBILE_PLAYBACK_ROUTE || to == MOBILE_PLAYBACK_ROUTE -> MobileTransition.Container
        MobileDestination.ofRoute(from) != MobileDestination.ofRoute(to) -> MobileTransition.FadeThrough
        pop -> MobileTransition.Pop
        else -> MobileTransition.Push
    }

/** The shared axis offset, signed for the layout direction, and whether the system turned animation off. */
@Immutable
internal data class MobileMotion(val axisOffsetPx: Int, val reducedMotion: Boolean) {
    fun between(from: NavBackStackEntry, to: NavBackStackEntry, pop: Boolean): MobileTransition =
        mobileTransition(from.destination.route, to.destination.route, pop, reducedMotion)

    fun enter(transition: MobileTransition): EnterTransition =
        when (transition) {
            MobileTransition.FadeThrough ->
                fadeIn(fadeThroughIn()) + scaleIn(fadeThroughIn(), initialScale = FADE_THROUGH_SCALE)
            MobileTransition.Push ->
                slideInHorizontally(MobileMotionSprings.defaultSpatial()) { axisOffsetPx } +
                    fadeIn(MobileMotionSprings.defaultEffects())
            MobileTransition.Pop ->
                slideInHorizontally(MobileMotionSprings.defaultSpatial()) { -axisOffsetPx } +
                    fadeIn(MobileMotionSprings.defaultEffects())
            MobileTransition.Container, MobileTransition.Fade -> fadeIn(MobileMotionSprings.defaultEffects())
        }

    fun exit(transition: MobileTransition): ExitTransition =
        when (transition) {
            MobileTransition.FadeThrough -> fadeOut(tween(FADE_THROUGH_OUT_MS, easing = FastOutLinearInEasing))
            MobileTransition.Push ->
                slideOutHorizontally(MobileMotionSprings.defaultSpatial()) { -axisOffsetPx } +
                    fadeOut(MobileMotionSprings.fastEffects())
            MobileTransition.Pop ->
                slideOutHorizontally(MobileMotionSprings.defaultSpatial()) { axisOffsetPx } +
                    fadeOut(MobileMotionSprings.fastEffects())
            MobileTransition.Container, MobileTransition.Fade -> fadeOut(MobileMotionSprings.fastEffects())
        }

    /** A move within one tab, such as a folder push, as one content transform. */
    fun axis(forward: Boolean): ContentTransform {
        val transition = when {
            reducedMotion -> MobileTransition.Fade
            forward -> MobileTransition.Push
            else -> MobileTransition.Pop
        }
        return ContentTransform(enter(transition), exit(transition), sizeTransform = SizeTransform(clip = false))
    }
}

@Composable
internal fun rememberMobileMotion(): MobileMotion {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val offset = remember(density) { with(density) { SHARED_AXIS_OFFSET.roundToPx() } }
    // "Remove animations" sets the animator scale to 0, which is Android's reduced motion. It is
    // read again whenever the screen's lifecycle moves, as it does on return from Settings.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val reducedMotion = remember(lifecycleState) { !ValueAnimator.areAnimatorsEnabled() }
    return remember(offset, direction, reducedMotion) {
        MobileMotion(axisOffsetPx = if (direction == LayoutDirection.Rtl) -offset else offset, reducedMotion)
    }
}

/**
 * Within-tab navigation the NavHost does not own, such as folders: [forward] tells a push, which
 * slides in from the end, from a pop, which slides back.
 */
@Composable
internal fun <S> MobileAxisContent(
    state: S,
    contentKey: (S) -> Any?,
    forward: (initial: S, target: S) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (S) -> Unit,
) {
    val motion = rememberMobileMotion()
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        contentKey = contentKey,
        transitionSpec = { motion.axis(forward = forward(initialState, targetState)) },
        label = "mobile-axis",
    ) { shown -> content(shown) }
}

/**
 * The in-app predictive back preview for pops the NavHost does not own: the screen shrinks toward
 * the gesture's edge while the viewer drags, and springs back once the gesture ends either way.
 */
@Stable
internal class MobileBackPreview(private val scope: CoroutineScope) {
    var value by mutableFloatStateOf(0f)
        private set
    var fromLeftEdge by mutableStateOf(true)
        private set
    private var settling: Job? = null

    fun seek(event: NavigationEvent) {
        settling?.cancel()
        fromLeftEdge = event.swipeEdge == NavigationEvent.EDGE_LEFT
        value = event.progress
    }

    fun settle() {
        settling?.cancel()
        val from = value
        if (from == 0f) return
        settling = scope.launch {
            animate(from, 0f, animationSpec = MobileMotionSprings.defaultSpatial()) { current, _ -> value = current }
        }
    }
}

@Composable
internal fun rememberMobileBackPreview(): MobileBackPreview {
    val scope = rememberCoroutineScope()
    return remember(scope) { MobileBackPreview(scope) }
}

/**
 * Back for a pop the NavHost does not own, previewed on [preview] while the gesture runs. Like the
 * NavHost's own handler it completes synchronously, so a key or button Back acts at once; one
 * composed earlier yields to handlers composed after it.
 */
@Composable
internal fun MobilePredictiveBackHandler(
    enabled: Boolean,
    preview: MobileBackPreview?,
    onBack: () -> Unit,
) {
    val dispatcher = checkNotNull(LocalNavigationEventDispatcherOwner.current) {
        "No NavigationEventDispatcherOwner was provided via LocalNavigationEventDispatcherOwner"
    }.navigationEventDispatcher
    val handler = remember { MobileBackEventHandler() }
    // Reduced motion keeps the screen still under the gesture; the pop's own fade remains.
    val reducedMotion = rememberMobileMotion().reducedMotion
    SideEffect {
        handler.preview = preview.takeUnless { reducedMotion }
        handler.onBack = onBack
    }
    LifecycleStartEffect(enabled, handler) {
        handler.isBackEnabled = enabled
        onStopOrDispose { handler.isBackEnabled = false }
    }
    DisposableEffect(dispatcher, handler) {
        dispatcher.addHandler(handler)
        onDispose { handler.remove() }
    }
}

private class MobileBackEventHandler :
    NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, isBackEnabled = false) {
    var preview: MobileBackPreview? = null
    var onBack: () -> Unit = {}

    override fun onBackStarted(event: NavigationEvent) {
        preview?.seek(event)
    }

    override fun onBackProgressed(event: NavigationEvent) {
        preview?.seek(event)
    }

    override fun onBackCancelled() {
        preview?.settle()
    }

    override fun onBackCompleted() {
        onBack()
        preview?.settle()
    }
}

internal fun Modifier.mobileBackPreview(preview: MobileBackPreview): Modifier = graphicsLayer {
    val fraction = preview.value
    if (fraction > 0f) {
        val scale = 1f - (1f - BACK_PREVIEW_SCALE) * fraction
        scaleX = scale
        scaleY = scale
        val shift = (size.width / BACK_PREVIEW_SHIFT_DIVISOR - BACK_PREVIEW_MARGIN.toPx()).coerceAtLeast(0f)
        translationX = shift * fraction * if (preview.fromLeftEdge) 1f else -1f
        shape = RoundedCornerShape(PutioRadius.extraLarge * fraction)
        clip = true
    }
}

/** The NavHost's layout, so the player can grow out of the row that opened it. */
internal val LocalMobileSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The enter and exit of the route a composable sits in; rows on that route share bounds with the player. */
internal val LocalMobileRouteAnimation = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
internal fun MobileRouteAnimation(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMobileRouteAnimation provides scope, content = content)
}

internal fun Modifier.playbackContainer(
    fileId: Long,
    playable: Boolean = true,
    route: AnimatedVisibilityScope? = null,
): Modifier = if (!playable) this else this.composed {
    val shared = LocalMobileSharedTransition.current
    val scope = route ?: LocalMobileRouteAnimation.current
    // Reduced motion keeps the route's fade and drops the moving bounds.
    val reducedMotion = rememberMobileMotion().reducedMotion
    if (shared == null || scope == null || reducedMotion) {
        Modifier
    } else {
        with(shared) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(PlaybackContainerKey(fileId)),
                animatedVisibilityScope = scope,
                enter = fadeIn(MobileMotionSprings.defaultEffects()),
                exit = fadeOut(MobileMotionSprings.fastEffects()),
                boundsTransform = { _, _ -> MobileMotionSprings.defaultSpatial() },
            )
        }
    }
}

private data class PlaybackContainerKey(val fileId: Long)

private const val FADE_THROUGH_SCALE = 0.92f
private const val FADE_THROUGH_OUT_MS = 90
private const val FADE_THROUGH_IN_MS = 210
private val SHARED_AXIS_OFFSET = 30.dp
private const val BACK_PREVIEW_SCALE = 0.9f
private const val BACK_PREVIEW_SHIFT_DIVISOR = 20f
private val BACK_PREVIEW_MARGIN = 8.dp
