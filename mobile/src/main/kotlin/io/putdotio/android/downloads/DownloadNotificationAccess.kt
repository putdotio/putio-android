package io.putdotio.android.downloads

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.RequiresApi
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Whether download outcomes would be shown, and how to turn them on from a visible screen. */
@Immutable
internal class DownloadNotificationAccess(
    val enabled: Boolean,
    val turnOn: () -> Unit,
)

/**
 * Re-read on every resume, so a change made in system settings shows at once. Turning on asks
 * for the Android 13 permission while the system still allows a prompt, and otherwise opens the
 * app's notification settings.
 */
@Composable
internal fun rememberDownloadNotificationAccess(): DownloadNotificationAccess {
    val context = LocalContext.current
    val settings = remember(context) { MobileDownloadSettings(context) }
    var enabled by remember(context) { mutableStateOf(MobileDownloadNotifications.canPost(context)) }
    LifecycleResumeEffect(context) {
        enabled = MobileDownloadNotifications.canPost(context)
        onPauseOrDispose {}
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = MobileDownloadNotifications.canPost(context)
    }
    return DownloadNotificationAccess(enabled) {
        val activity = context.findActivity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity.mayPromptForNotifications(settings)) {
            settings.notificationPermissionAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            (activity ?: context).startActivity(intent)
        }
    }
}

/** The first download asks once, so the viewer can hear when it finishes; later asks come from Downloads. */
@Composable
internal fun rememberFirstDownloadNotificationPrompt(): () -> Unit {
    val context = LocalContext.current
    val settings = remember(context) { MobileDownloadSettings(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.notificationPermissionGranted() &&
            !settings.notificationPermissionAsked
        ) {
            settings.notificationPermissionAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** The system still shows its prompt before the first ask and after one denial. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Activity?.mayPromptForNotifications(settings: MobileDownloadSettings): Boolean =
    this != null && !notificationPermissionGranted() &&
        (!settings.notificationPermissionAsked ||
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))

private fun Context.notificationPermissionGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
