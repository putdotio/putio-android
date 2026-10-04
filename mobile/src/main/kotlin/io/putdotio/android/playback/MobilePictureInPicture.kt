package io.putdotio.android.playback

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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

/** Whether the Activity hosting this composition is in picture-in-picture. */
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
 * video's shape, and its one action asks [onPlayingRequested] to pause or play the screen's own
 * player.
 */
@Composable
internal fun MobilePictureInPictureEffect(
    playing: Boolean,
    videoSize: VideoSize,
    videoFrame: Rect?,
    onPlayingRequested: (Boolean) -> Unit,
) {
    val activity = LocalActivity.current as? PictureInPictureProvider ?: return
    val context = LocalContext.current
    val actions = remember(context) { PictureInPictureActions(context) }
    val params = remember(playing, videoSize, videoFrame, actions) {
        pictureInPictureParams(playing, videoSize, videoFrame, if (playing) actions.pause else actions.play)
    }
    val currentParams by rememberUpdatedState(params)
    val currentOnPlayingRequested by rememberUpdatedState(onPlayingRequested)
    LaunchedEffect(activity, params) { activity.setPictureInPictureParams(params) }
    DisposableEffect(activity) {
        val leaveHint = Runnable {
            if (currentParams.isEnabled) activity.enterPictureInPictureMode(currentParams)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) activity.addOnUserLeaveHintListener(leaveHint)
        onDispose {
            activity.removeOnUserLeaveHintListener(leaveHint)
            activity.setPictureInPictureParams(PictureInPictureParamsCompat.Builder().setEnabled(false).build())
        }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PICTURE_IN_PICTURE_PLAY -> currentOnPlayingRequested(true)
                    ACTION_PICTURE_IN_PICTURE_PAUSE -> currentOnPlayingRequested(false)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PICTURE_IN_PICTURE_PLAY)
            addAction(ACTION_PICTURE_IN_PICTURE_PAUSE)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
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

internal fun pictureInPictureParams(
    armed: Boolean,
    videoSize: VideoSize,
    videoFrame: Rect?,
    action: RemoteAction,
): PictureInPictureParamsCompat {
    val aspectRatio = pictureInPictureAspectRatio(videoSize)
    return PictureInPictureParamsCompat.Builder()
        .setEnabled(armed)
        .setActions(listOf(action))
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

private class PictureInPictureActions(context: Context) {
    val play = remoteAction(
        context,
        ACTION_PICTURE_IN_PICTURE_PLAY,
        DesignR.drawable.ic_ph_play_fill,
        R.string.mobile_now_playing_play,
    )
    val pause = remoteAction(
        context,
        ACTION_PICTURE_IN_PICTURE_PAUSE,
        DesignR.drawable.ic_ph_pause_fill,
        R.string.mobile_now_playing_pause,
    )
}

private fun remoteAction(
    context: Context,
    action: String,
    @DrawableRes icon: Int,
    @StringRes label: Int,
): RemoteAction {
    val title = context.getString(label)
    val intent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(action).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE,
    )
    return RemoteAction(Icon.createWithResource(context, icon), title, title, intent)
}

// Inside the platform's 2.39:1 limits, so a rounding difference can never make the shape invalid.
private const val WIDEST_HUNDREDTHS = 238
private const val HUNDREDTHS = 100
private val WIDEST = Rational(WIDEST_HUNDREDTHS, HUNDREDTHS)
private val TALLEST = Rational(HUNDREDTHS, WIDEST_HUNDREDTHS)
