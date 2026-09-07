package io.putdotio.android

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.settings.AppDiagnostics
import io.putdotio.android.settings.VideoPlaybackType
import kotlinx.coroutines.launch

internal const val MOBILE_ABOUT_ROW_TAG = "mobile-about-row"
internal const val MOBILE_ABOUT_DIALOG_TAG = "mobile-about-dialog"
internal const val MOBILE_ABOUT_COPY_TAG = "mobile-about-copy"
internal const val MOBILE_ABOUT_COPIED_TAG = "mobile-about-copied"

internal fun mobileAppDiagnostics(
    videoPlaybackType: VideoPlaybackType?,
    deviceClass: AppDiagnostics.DeviceClass,
): AppDiagnostics =
    AppDiagnostics(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        releaseChannel = AppDiagnostics.ReleaseChannel.fromFlavor(BuildConfig.FLAVOR_channel),
        buildType = BuildConfig.BUILD_TYPE,
        runtime = AppDiagnostics.Runtime.Android,
        runtimeVersion = Build.VERSION.SDK_INT,
        deviceClass = deviceClass,
        player = playerLabel(videoPlaybackType),
    )

// Media3 is the only player today; the stream method is the configured playback type.
private fun playerLabel(videoPlaybackType: VideoPlaybackType?): String =
    "media3" + (videoPlaybackType?.let { "/${it.wireValue}" } ?: "")

internal fun LazyListScope.aboutItem(
    diagnostics: AppDiagnostics,
    onOpen: () -> Unit,
) {
    item(key = ABOUT_ROW_KEY) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.mobile_settings_about)) },
            supportingContent = { Text(stringResource(R.string.mobile_settings_about_description)) },
            leadingContent = {
                Icon(painter = painterResource(R.drawable.ic_ph_info), contentDescription = null)
            },
            trailingContent = { Text(diagnostics.appVersion) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(MOBILE_ABOUT_ROW_TAG)
                .clickable(role = Role.Button, onClick = onOpen),
        )
    }
}

@Composable
internal fun MobileAboutDialog(
    diagnostics: AppDiagnostics,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val rows = listOf(
        R.string.mobile_settings_about_version to
            stringResource(R.string.mobile_settings_about_version_value, diagnostics.appVersion, diagnostics.versionCode),
        R.string.mobile_settings_about_channel to
            stringResource(channelLabel(diagnostics.releaseChannel), diagnostics.buildType),
        R.string.mobile_settings_about_android to diagnostics.runtimeVersion.toString(),
        R.string.mobile_settings_about_device to stringResource(deviceClassLabel(diagnostics.deviceClass)),
        R.string.mobile_settings_about_player to diagnostics.player,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(MOBILE_ABOUT_DIALOG_TAG),
        title = { Text(stringResource(R.string.mobile_settings_about)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { (label, value) ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = stringResource(label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(value, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Text(
                    text = stringResource(R.string.mobile_settings_about_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        // The confirmation replaces the button label so it stays visible and announces
        // from a fixed position, regardless of how tall the scrolling body is.
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("put.io app info", diagnostics.supportText())),
                        )
                        copied = true
                    }
                },
                modifier = Modifier.testTag(MOBILE_ABOUT_COPY_TAG),
            ) {
                if (copied) {
                    Text(
                        text = stringResource(R.string.mobile_settings_about_copied),
                        modifier = Modifier
                            .testTag(MOBILE_ABOUT_COPIED_TAG)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    Text(stringResource(R.string.mobile_settings_about_copy))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mobile_action_close)) }
        },
    )
}

private fun channelLabel(channel: AppDiagnostics.ReleaseChannel): Int =
    when (channel) {
        AppDiagnostics.ReleaseChannel.Stable -> R.string.mobile_settings_about_channel_stable
        AppDiagnostics.ReleaseChannel.Internal -> R.string.mobile_settings_about_channel_internal
    }

private fun deviceClassLabel(deviceClass: AppDiagnostics.DeviceClass): Int =
    when (deviceClass) {
        AppDiagnostics.DeviceClass.Phone -> R.string.mobile_settings_about_device_phone
        AppDiagnostics.DeviceClass.Tablet -> R.string.mobile_settings_about_device_tablet
        AppDiagnostics.DeviceClass.Tv -> R.string.mobile_settings_about_device_tv
    }

private const val ABOUT_ROW_KEY = "account-about"
