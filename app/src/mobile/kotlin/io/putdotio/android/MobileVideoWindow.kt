package io.putdotio.android

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun MobileVideoWindow(fileId: Long, landscape: Boolean = true) {
    val activity = LocalActivity.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val configuration = LocalConfiguration.current
    // The route owns this baseline across autoplay and Activity recreation, not each media file.
    val original = rememberSaveable(saver = VideoWindowBaselineSaver) { activity.captureVideoWindowBaseline() }
    val requestedOrientation = videoWindowOrientation(
        original = original.requestedOrientation,
        landscape = landscape,
        smallestWidthDp = configuration.smallestScreenWidthDp,
        multiWindow = activity.isInMultiWindowMode,
    )
    val currentOrientation = rememberUpdatedState(requestedOrientation)
    DisposableEffect(activity, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> activity.applyVideoWindow(currentOrientation.value)
                Lifecycle.Event.ON_PAUSE -> original.restoreBars(activity)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            activity.applyVideoWindow(currentOrientation.value)
        }
        onDispose {
            lifecycle.removeObserver(observer)
            // Restoring portrait while Android recreates a landscape Activity triggers another rotation.
            if (!activity.isChangingConfigurations) {
                original.restoreBars(activity)
                if (activity.requestedOrientation != original.requestedOrientation) {
                    activity.requestedOrientation = original.requestedOrientation
                }
            }
        }
    }
    LaunchedEffect(activity, fileId, requestedOrientation) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            activity.applyVideoWindow(requestedOrientation)
        }
    }
}

internal fun videoWindowOrientation(
    original: Int,
    landscape: Boolean,
    smallestWidthDp: Int,
    multiWindow: Boolean,
): Int =
    if (landscape && smallestWidthDp < TABLET_SMALLEST_WIDTH_DP && !multiWindow) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        original
    }

private fun Activity.applyVideoWindow(orientation: Int) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
    if (requestedOrientation != orientation) requestedOrientation = orientation
}

private data class VideoWindowBaseline(
    val requestedOrientation: Int,
    val visibleBars: Int,
    val barsBehavior: Int,
) {
    fun restoreBars(activity: Activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = barsBehavior
        controller.show(visibleBars)
        controller.hide(WindowInsetsCompat.Type.systemBars() and visibleBars.inv())
    }
}

private fun Activity.captureVideoWindowBaseline(): VideoWindowBaseline {
    val view = window.decorView
    val insets = ViewCompat.getRootWindowInsets(view)
    val barTypes = listOf(
        WindowInsetsCompat.Type.statusBars(),
        WindowInsetsCompat.Type.navigationBars(),
        WindowInsetsCompat.Type.captionBar(),
    )
    val visible = barTypes.fold(0) { mask, type ->
        val fallbackVisible = type != WindowInsetsCompat.Type.captionBar()
        if (insets?.isVisible(type) ?: fallbackVisible) mask or type else mask
    }
    return VideoWindowBaseline(
        requestedOrientation = requestedOrientation,
        visibleBars = visible,
        barsBehavior = WindowCompat.getInsetsController(window, view).systemBarsBehavior,
    )
}

private val VideoWindowBaselineSaver = listSaver<VideoWindowBaseline, Int>(
    save = { listOf(it.requestedOrientation, it.visibleBars, it.barsBehavior) },
    restore = { VideoWindowBaseline(it[0], it[1], it[2]) },
)

private const val TABLET_SMALLEST_WIDTH_DP = 600
