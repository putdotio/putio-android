package io.putdotio.android.playback

import android.Manifest
import android.annotation.SuppressLint
import androidx.annotation.DrawableRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import io.putdotio.android.design.PutioRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.MobileSheetHandle
import io.putdotio.android.R
import io.putdotio.android.neutralTextButtonColors
import io.putdotio.android.design.PutioDesignTokens

internal const val MOBILE_CAST_BUTTON_TAG = "mobile-cast-button"
internal const val MOBILE_CAST_PICKER_TAG = "mobile-cast-picker"
internal const val MOBILE_CAST_STOP_TAG = "mobile-cast-stop"
internal const val MOBILE_CAST_LABEL_TAG = "mobile-cast-label"
internal const val MOBILE_CAST_RETRY_TAG = "mobile-cast-retry"

/**
 * Cast in a player's top bar, once a receiver is in reach; yellow while casting. Where Android needs
 * the local network allowed first, the tap asks for it and opens the picker once allowed.
 */
@Composable
internal fun MobileCastButton(
    availability: CastAvailability,
    onClick: () -> Unit,
    onLocalNetworkAnswer: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!availability.showsButton) return
    val device = availability.connectedDevice
    val askLocalNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onLocalNetworkAnswer(granted)
        if (granted) onClick()
    }
    IconButton(
        onClick = {
            if (availability == CastAvailability.NeedsLocalNetwork) {
                askLocalNetwork.launch(LOCAL_NETWORK_PERMISSION)
            } else {
                onClick()
            }
        },
        modifier = modifier.testTag(MOBILE_CAST_BUTTON_TAG),
    ) {
        Icon(
            painterResource(R.drawable.ic_ph_screencast),
            contentDescription = device?.let { stringResource(R.string.mobile_cast_casting_to, it) }
                ?: stringResource(R.string.mobile_cast),
            tint = if (device != null) PutioDesignTokens.yellowSolid else LocalContentColor.current,
        )
    }
}

/**
 * "Cast to": the receivers in reach, each with its kind's glyph, its name and its model or "Audio
 * only". The one playing carries a check, and Stop casting ends the session. The sheet lives in the
 * player's own window, as its side sheets do, so an immersive video keeps its system bars hidden;
 * Back or a tap on the dimmed player closes it.
 */
@Composable
internal fun MobileCastPicker(gateway: CastGateway, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(gateway) {
        gateway.startDiscovery()
        onDispose { gateway.stopDiscovery() }
    }
    val devices by gateway.devices.collectAsState()
    val availability by gateway.availability.collectAsState()
    BackHandler(onBack = onDismiss)
    // Keys move through the picker, not the player behind it.
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    val title = stringResource(R.string.mobile_cast_to)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = CAST_PICKER_SCRIM_ALPHA))
                .clearAndSetSemantics {}
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        )
        Column(
            Modifier
                .widthIn(max = CAST_PICKER_MAX_WIDTH)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    RoundedCornerShape(topStart = PutioRadius.extraLarge, topEnd = PutioRadius.extraLarge),
                )
                // A tap inside the sheet never reaches the scrim behind it.
                .pointerInput(Unit) { detectTapGestures {} }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
                .semantics {
                    paneTitle = title
                    isTraversalGroup = true
                }
                .focusRequester(focus)
                // Focus stays in the picker until it closes.
                .focusProperties { onExit = { cancelFocusChange() } }
                // Focusable itself, so it takes focus before any receiver row exists.
                .focusable()
                .focusGroup()
                .testTag(MOBILE_CAST_PICKER_TAG),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MobileSheetHandle()
            MobileCastPickerRows(gateway, devices, availability, onDismiss)
        }
    }
}

@Composable
private fun MobileCastPickerRows(
    gateway: CastGateway,
    devices: List<CastDevice>,
    availability: CastAvailability,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.mobile_cast_to),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp)
                .semantics { heading() },
        )
        if (devices.isEmpty()) MobileCastSearchingRow()
        devices.forEach { device ->
            MobileCastDeviceRow(device) {
                if (!device.selected) gateway.select(device.id)
                onDismiss()
            }
        }
        if (availability is CastAvailability.Connected || availability is CastAvailability.Connecting) {
            MobileCastActionRow(
                icon = R.drawable.ic_ph_stop_circle,
                title = stringResource(R.string.mobile_cast_stop),
                modifier = Modifier.testTag(MOBILE_CAST_STOP_TAG),
            ) {
                gateway.stopCasting()
                onDismiss()
            }
        }
    }
}

@Composable
private fun MobileCastDeviceRow(device: CastDevice, onClick: () -> Unit) {
    MobileCastActionRow(
        icon = when (device.kind) {
            CastDeviceKind.Tv -> R.drawable.ic_ph_television
            CastDeviceKind.Speaker -> R.drawable.ic_ph_speaker_hifi
            CastDeviceKind.Other -> R.drawable.ic_ph_television_simple
        },
        title = device.name,
        supporting = if (device.audioOnly) stringResource(R.string.mobile_cast_audio_only) else device.model,
        selected = device.selected,
        onClick = onClick,
    )
}

@Composable
private fun MobileCastSearchingRow() {
    Row(
        Modifier.fillMaxWidth().height(CAST_ROW_HEIGHT).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Text(
            stringResource(R.string.mobile_cast_searching),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A 64dp row: the glyph in the secondary text colour, the name over its supporting line. */
@Composable
private fun MobileCastActionRow(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            // Large text grows the row past its 64dp.
            .heightIn(min = CAST_ROW_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_ph_check),
                contentDescription = null,
                tint = PutioDesignTokens.yellowSolid,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Over the casting video's dark frame, just above the transport: the cast glyph and "Playing on
 * Living room TV", or why the receiver can't play it, with Try again when [onRetry] can fix that.
 */
@Composable
internal fun MobileCastFrameLabel(text: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(Modifier.offset(y = -CAST_LABEL_LIFT), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier
                    .padding(horizontal = 24.dp)
                    .testTag(MOBILE_CAST_LABEL_TAG),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_ph_screencast),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onRetry != null) MobileCastRetry(onRetry)
        }
    }
}

/** Sends the file to the receiver again after its lookup failed. */
@Composable
internal fun MobileCastRetry(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onRetry,
        colors = neutralTextButtonColors(),
        modifier = modifier.testTag(MOBILE_CAST_RETRY_TAG),
    ) {
        Text(stringResource(R.string.mobile_action_retry))
    }
}

// Only asked for on Android 17 and later, where the platform defines it.
@SuppressLint("InlinedApi")
private const val LOCAL_NETWORK_PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK

private val CAST_PICKER_MAX_WIDTH = 560.dp
private const val CAST_PICKER_SCRIM_ALPHA = 0.55f
private val CAST_ROW_HEIGHT = 64.dp

/** Half the transport's 80dp, then room for the line itself over it. */
private val CAST_LABEL_LIFT = 72.dp
