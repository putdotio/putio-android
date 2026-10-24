package io.putdotio.android.playback

import android.app.Activity
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.OnPictureInPictureModeChangedProvider
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.app.PictureInPictureParamsCompat
import androidx.core.app.PictureInPictureProvider
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.VideoSize
import io.putdotio.android.R
import io.putdotio.android.design.R as DesignR
import kotlin.math.roundToInt

internal const val ACTION_PICTURE_IN_PICTURE_PLAY = "io.putdotio.android.action.PICTURE_IN_PICTURE_PLAY"
internal const val ACTION_PICTURE_IN_PICTURE_PAUSE = "io.putdotio.android.action.PICTURE_IN_PICTURE_PAUSE"
internal const val ACTION_PICTURE_IN_PICTURE_SEEK_BACK = "io.putdotio.android.action.PICTURE_IN_PICTURE_SEEK_BACK"
internal const val ACTION_PICTURE_IN_PICTURE_SEEK_FORWARD = "io.putdotio.android.action.PICTURE_IN_PICTURE_SEEK_FORWARD"

@Composable
internal fun rememberPictureInPictureMode(): State<Boolean> {
    val activity = LocalActivity.current
    val mode = remember(activity) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val provider = activity as? OnPictureInPictureModeChangedProvider
        val listener = Consumer<PictureInPictureModeChangedInfo> { mode.value = it.isInPictureInPictureMode }
        provider?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { provider?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return mode
}

/** A started Activity in picture-in-picture is the viewer's foreground, as a resumed one is. */
internal fun Lifecycle.State.withPictureInPicture(pictureInPicture: Boolean): Lifecycle.State =
    if (pictureInPicture && isAtLeast(Lifecycle.State.STARTED)) Lifecycle.State.RESUMED else this

/**
 * Offers the video to picture-in-picture while it is [playing]: Android 12 and later enter on
 * their own when the viewer leaves, earlier versions on the leave hint. The window takes the
 * video's shape, and its actions ask [onSeekRequested] to go 10 seconds back or forward and
 * [onPlayingRequested] to pause or play the screen's own player. Returns what the top bar's button
 * calls to enter the window now, or null when the host Activity does not declare the window or
 * the device has none.
 */
@Composable
internal fun MobilePictureInPictureEffect(
    playing: Boolean,
    videoSize: VideoSize,
    videoFrame: Rect?,
    onPlayingRequested: (Boolean) -> Unit,
    onSeekRequested: (SeekDirection) -> Unit = {},
): (() -> Unit)? {
    val host = LocalActivity.current ?: return null
    // The platform throws for an Activity that does not declare the window.
    val declared = remember(host) { host.declaresPictureInPicture() }
    val activity = host as? PictureInPictureProvider
    if (!declared || activity == null) return null
    val context = LocalContext.current
    val actions = remember(context) { PictureInPictureActions(context) }
    val params = remember(playing, videoSize, videoFrame, actions) {
        pictureInPictureParams(playing, videoSize, videoFrame, actions.forState(playing))
    }
    val currentOnSeekRequested by rememberUpdatedState(onSeekRequested)
    val currentParams by rememberUpdatedState(params)
    val currentOnPlayingRequested by rememberUpdatedState(onPlayingRequested)
    LaunchedEffect(activity, params) {
        if (!pictureInPictureCall { activity.setPictureInPictureParams(params) }) activity.withdrawPictureInPicture()
    }
    DisposableEffect(activity) {
        onDispose { activity.withdrawPictureInPicture() }
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        DisposableEffect(activity) {
            val leaveHint = Runnable {
                if (currentParams.isEnabled) pictureInPictureCall { activity.enterPictureInPictureMode(currentParams) }
            }
            activity.addOnUserLeaveHintListener(leaveHint)
            onDispose { activity.removeOnUserLeaveHintListener(leaveHint) }
        }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PICTURE_IN_PICTURE_PLAY -> currentOnPlayingRequested(true)
                    ACTION_PICTURE_IN_PICTURE_PAUSE -> currentOnPlayingRequested(false)
                    ACTION_PICTURE_IN_PICTURE_SEEK_BACK -> currentOnSeekRequested(SeekDirection.Backward)
                    ACTION_PICTURE_IN_PICTURE_SEEK_FORWARD -> currentOnSeekRequested(SeekDirection.Forward)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PICTURE_IN_PICTURE_PLAY)
            addAction(ACTION_PICTURE_IN_PICTURE_PAUSE)
            addAction(ACTION_PICTURE_IN_PICTURE_SEEK_BACK)
            addAction(ACTION_PICTURE_IN_PICTURE_SEEK_FORWARD)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val supported = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    if (!supported) return null
    return {
        // Asked for, the window opens even for a paused video, which then shows Play.
        pictureInPictureCall {
            activity.enterPictureInPictureMode(
                pictureInPictureParams(true, videoSize, videoFrame, actions.forState(playing)),
            )
        }
    }
}

/**
 * Closing the window stops the Activity and ends picture-in-picture, in either order and without
 * resuming in between. [onDismissed] runs when the Activity starts again after that, before its
 * player is rebuilt.
 */
@Composable
internal fun PictureInPictureDismissalEffect(onDismissed: () -> Unit) {
    val activity = LocalActivity.current as? OnPictureInPictureModeChangedProvider ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    DisposableEffect(activity, lifecycle) {
        var leftWindow = false
        var stopped = false
        val modeListener = Consumer<PictureInPictureModeChangedInfo> {
            if (!it.isInPictureInPictureMode) leftWindow = true
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stopped = true
                Lifecycle.Event.ON_START -> {
                    if (leftWindow && stopped) currentOnDismissed()
                    leftWindow = false
                    stopped = false
                }
                Lifecycle.Event.ON_RESUME -> leftWindow = false
                else -> Unit
            }
        }
        activity.addOnPictureInPictureModeChangedListener(modeListener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            activity.removeOnPictureInPictureModeChangedListener(modeListener)
        }
    }
}

private fun PictureInPictureProvider.withdrawPictureInPicture() {
    pictureInPictureCall { setPictureInPictureParams(PictureInPictureParamsCompat.Builder().setEnabled(false).build()) }
}

/**
 * A device may narrow the platform's shape limits or refuse the window outright; the platform
 * then throws, and the video simply gets no window.
 */
private inline fun pictureInPictureCall(call: () -> Unit): Boolean =
    try {
        call()
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

// The ComponentInfoFlags overload exists only from API 33; this one answers on every supported level.
@Suppress("DEPRECATION")
private fun Activity.declaresPictureInPicture(): Boolean {
    val flags = try {
        packageManager.getActivityInfo(componentName, 0).flags
    } catch (_: PackageManager.NameNotFoundException) {
        0
    }
    return flags and FLAG_SUPPORTS_PICTURE_IN_PICTURE != 0
}

internal fun pictureInPictureParams(
    armed: Boolean,
    videoSize: VideoSize,
    videoFrame: Rect?,
    actions: List<RemoteAction>,
): PictureInPictureParamsCompat {
    val aspectRatio = pictureInPictureAspectRatio(videoSize)
    return PictureInPictureParamsCompat.Builder()
        .setEnabled(armed)
        .setActions(actions)
        .setSeamlessResizeEnabled(true)
        .apply {
            aspectRatio?.let(::setAspectRatio)
            aspectRatio?.let { videoFrame?.fittedVideoBounds(it) }?.let(::setSourceRectHint)
        }
        .build()
}

/** The video's display shape, held inside the 1:2.39 to 2.39:1 range every device accepts. */
internal fun pictureInPictureAspectRatio(videoSize: VideoSize): Rational? {
    val ratio = videoSize.displayAspectRatioOrNull() ?: return null
    return when {
        ratio > WIDEST.toFloat() -> WIDEST
        ratio < TALLEST.toFloat() -> TALLEST
        else -> Rational((videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt(), videoSize.height)
    }
}

/** Where the video sits inside [this] frame, letterboxed and centred as the content frame draws it. */
internal fun Rect.fittedVideoBounds(aspectRatio: Rational): android.graphics.Rect? {
    if (width <= 0f || height <= 0f) return null
    val ratio = aspectRatio.toFloat()
    val videoWidth = if (width / height > ratio) height * ratio else width
    val videoHeight = videoWidth / ratio
    val videoLeft = left + (width - videoWidth) / 2f
    val videoTop = top + (height - videoHeight) / 2f
    return android.graphics.Rect(
        videoLeft.roundToInt(),
        videoTop.roundToInt(),
        (videoLeft + videoWidth).roundToInt(),
        (videoTop + videoHeight).roundToInt(),
    )
}

/** The window's actions, in the player's order: 10 seconds back, play or pause, 10 seconds forward. */
private class PictureInPictureActions(context: Context) {
    private val seconds = (MOBILE_SEEK_INTERVAL_MILLIS / 1_000L).toInt()
    private val back = remoteAction(
        context,
        ACTION_PICTURE_IN_PICTURE_SEEK_BACK,
        R.drawable.ic_ph_arrow_counter_clockwise,
        context.resources.getQuantityString(R.plurals.mobile_playback_seek_back, seconds, seconds),
    )
    private val play = remoteAction(
        context,
        ACTION_PICTURE_IN_PICTURE_PLAY,
        DesignR.drawable.ic_ph_play_fill,
        context.getString(R.string.mobile_now_playing_play),
    )
    private val pause = remoteAction(
        context,
        ACTION_PICTURE_IN_PICTURE_PAUSE,
        DesignR.drawable.ic_ph_pause_fill,
        context.getString(R.string.mobile_now_playing_pause),
    )
    private val forward = remoteAction(
        context,
        ACTION_PICTURE_IN_PICTURE_SEEK_FORWARD,
        R.drawable.ic_ph_arrow_clockwise,
        context.resources.getQuantityString(R.plurals.mobile_playback_seek_forward, seconds, seconds),
    )

    fun forState(playing: Boolean): List<RemoteAction> = listOf(back, if (playing) pause else play, forward)
}

private fun remoteAction(
    context: Context,
    action: String,
    @DrawableRes icon: Int,
    title: String,
): RemoteAction {
    val intent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(action).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE,
    )
    return RemoteAction(Icon.createWithResource(context, icon), title, title, intent)
}

// ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE, which the SDK hides; set from the manifest's
// supportsPictureInPicture since Android 7.
private const val FLAG_SUPPORTS_PICTURE_IN_PICTURE = 0x400000

// Inside the platform's 2.39:1 limits, so a rounding difference can never make the shape invalid.
private const val WIDEST_HUNDREDTHS = 238
private const val HUNDREDTHS = 100
private val WIDEST = Rational(WIDEST_HUNDREDTHS, HUNDREDTHS)
private val TALLEST = Rational(HUNDREDTHS, WIDEST_HUNDREDTHS)
