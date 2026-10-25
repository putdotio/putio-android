package io.putdotio.android.account

import android.content.ClipData
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.BuildConfig
import io.putdotio.android.R
import io.putdotio.android.settings.AppDiagnostics
import io.putdotio.sdk.files.PlaybackPreference
import kotlinx.coroutines.launch

internal const val MOBILE_APP_INFO_ROUTE = "account/app-info"
internal const val MOBILE_ABOUT_ROW_TAG = "mobile-about-row"
internal const val MOBILE_APP_INFO_TAG = "mobile-app-info"
internal const val MOBILE_ABOUT_COPY_TAG = "mobile-about-copy"
internal const val MOBILE_ABOUT_COPIED_TAG = "mobile-about-copied"

internal fun mobileAppDiagnostics(
    playbackPreference: PlaybackPreference,
    deviceClass: AppDiagnostics.DeviceClass,
): AppDiagnostics =
    AppDiagnostics(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        releaseChannel = AppDiagnostics.ReleaseChannel.fromFlavor(BuildConfig.FLAVOR),
        buildType = BuildConfig.BUILD_TYPE,
        runtime = AppDiagnostics.Runtime.Android,
        runtimeVersion = Build.VERSION.SDK_INT,
        deviceClass = deviceClass,
        player = playerLabel(playbackPreference),
    )

// Media3 is the only player; the stream method is the one playback actually uses,
// which falls back to HLS while config is loading and ignores unconfirmed saves.
private fun playerLabel(playbackPreference: PlaybackPreference): String =
    when (playbackPreference) {
        PlaybackPreference.HLS -> "media3/hls"
        PlaybackPreference.MP4 -> "media3/mp4"
    }

/** The player's name on the App info page; the support text keeps the stream method too. */
private const val PLAYER_NAME = "Media3"

/**
 * App info, a subpage of Account: what support asks for as value rows, the privacy note, and a tonal
 * Copy for support that puts [AppDiagnostics.supportText] on the clipboard.
 */
@Composable
internal fun MobileAppInfoScreen(diagnostics: AppDiagnostics, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Keyed on the support text: if playback config settles after the copy, the
    // clipboard holds stale details and the confirmation must not claim otherwise.
    val supportText = remember(diagnostics) { diagnostics.supportText() }
    var copied by remember(supportText) { mutableStateOf(false) }
    val rows = listOf(
        AppInfoRow(
            R.string.mobile_settings_about_version,
            stringResource(
                R.string.mobile_settings_about_version_value,
                diagnostics.appVersion,
                diagnostics.versionCode,
            ),
            mono = true,
        ),
        AppInfoRow(
            R.string.mobile_settings_about_channel,
            stringResource(channelLabel(diagnostics.releaseChannel), diagnostics.buildType),
        ),
        AppInfoRow(R.string.mobile_settings_about_device, stringResource(deviceClassLabel(diagnostics.deviceClass))),
        AppInfoRow(R.string.mobile_settings_about_android, diagnostics.runtimeVersion.toString(), mono = true),
        AppInfoRow(R.string.mobile_settings_about_player, PLAYER_NAME),
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(MOBILE_APP_INFO_TAG),
    ) {
        Text(
            text = stringResource(R.string.mobile_settings_about_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 16.dp),
        )
        MobileSettingsGroup {
            rows.forEachIndexed { index, row ->
                MobileSettingsSegment(
                    segment = settingsSegment(index, rows.size),
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                ) {
                    MobileSettingsLine(
                        title = stringResource(row.label),
                        icon = null,
                        trailing = settingsValue(
                            row.value,
                            mono = row.mono,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.mobile_settings_about_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
        )
        // The confirmation replaces the button label so it announces from a fixed position.
        FilledTonalButton(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("put.io app info", supportText)))
                    copied = true
                }
            },
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(MOBILE_ABOUT_COPY_TAG),
        ) {
            Icon(
                painterResource(R.drawable.ic_ph_copy),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Text(
                text = stringResource(
                    if (copied) R.string.mobile_settings_about_copied else R.string.mobile_settings_about_copy,
                ),
                modifier = Modifier
                    .padding(start = ButtonDefaults.IconSpacing)
                    .then(
                        if (copied) {
                            Modifier.testTag(MOBILE_ABOUT_COPIED_TAG).semantics { liveRegion = LiveRegionMode.Polite }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

private data class AppInfoRow(@StringRes val label: Int, val value: String, val mono: Boolean = false)

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
