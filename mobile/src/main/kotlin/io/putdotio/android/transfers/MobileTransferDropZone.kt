package io.putdotio.android.transfers

import android.app.Activity
import android.view.DragEvent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.putdotio.android.R
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.share.MobileFileDrag

internal const val MOBILE_TRANSFER_DROP_TARGET_TAG = "mobile-transfer-drop-target"

internal enum class MobileDropHighlight { None, Available, Over }

/**
 * Takes links, magnet links and `.torrent` files dragged in from another app into the Add transfer
 * sheet, through the same intake as a share. While such a drag is under way the whole signed-in
 * screen shows where to drop it. A torrent's read grant is requested for this drop only and released
 * once its read is over or cancelled. Drags of this app's own files are not taken; their end is
 * reported to [onOwnDragEnded], which forgets a drag nobody received.
 */
@Composable
internal fun MobileTransferDropZone(
    enabled: Boolean,
    draft: MobileTransferDraft,
    onOwnDragEnded: (MobileFileDrag, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val activity = LocalActivity.current
    var highlight by remember { mutableStateOf(MobileDropHighlight.None) }
    val currentDraft by rememberUpdatedState(draft)
    val currentOnOwnDragEnded by rememberUpdatedState(onOwnDragEnded)
    val target = remember(activity) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                highlight = MobileDropHighlight.None
                val dragEvent = event.toAndroidDragEvent()
                return dragEvent.localState == null && activity != null && currentDraft.receiveDrop(activity, dragEvent)
            }

            override fun onStarted(event: DragAndDropEvent) {
                if (event.toAndroidDragEvent().localState == null) highlight = MobileDropHighlight.Available
            }

            override fun onEntered(event: DragAndDropEvent) {
                if (highlight != MobileDropHighlight.None) highlight = MobileDropHighlight.Over
            }

            override fun onExited(event: DragAndDropEvent) {
                if (highlight != MobileDropHighlight.None) highlight = MobileDropHighlight.Available
            }

            override fun onEnded(event: DragAndDropEvent) {
                highlight = MobileDropHighlight.None
                val dragEvent = event.toAndroidDragEvent()
                (dragEvent.localState as? MobileFileDrag)?.let { currentOnOwnDragEnded(it, dragEvent.result) }
            }
        }
    }
    Box(
        modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                val dragEvent = event.toAndroidDragEvent()
                when {
                    dragEvent.localState is MobileFileDrag -> true
                    !enabled || dragEvent.localState != null -> false
                    else -> dragEvent.clipDescription?.isMobileTransferDrop() == true
                }
            },
            target = target,
        ),
    ) {
        content()
        if (highlight != MobileDropHighlight.None) MobileTransferDropOverlay(highlight == MobileDropHighlight.Over)
    }
}

/** Reads a drop into the draft; false when it carries nothing the intake takes. */
internal fun MobileTransferDraft.receiveDrop(activity: Activity, event: DragEvent): Boolean {
    val incoming = event.clipData?.toMobileIncomingTransfer() ?: return false
    // Only a file needs the source's grant; text arrives inside the drop itself.
    val permissions =
        (incoming as? MobileIncomingTransfer.Torrent)?.let { activity.requestDragAndDropPermissions(event) }
    receive(incoming, activity.contentResolver, activity.packageName, afterRead = { permissions?.release() })
    return true
}

/**
 * One dashed yellow area over the signed-in screen, clear of the system bars and the navigation:
 * "Drop to add a transfer". It fills more while the drag is over it.
 */
@Composable
internal fun MobileTransferDropOverlay(over: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val shape = RoundedCornerShape(PutioRadius.extraLarge)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp)
            .padding(top = DROP_TOP_CLEARANCE, bottom = DROP_BOTTOM_CLEARANCE)
            .background(accent.copy(alpha = if (over) OVER_TINT else AVAILABLE_TINT).compositeOver(surface), shape)
            .drawBehind {
                val stroke = 2.dp.toPx()
                val radius = PutioRadius.extraLarge.toPx() - stroke / 2
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(DASH.map { it.toPx() }.toFloatArray()),
                    ),
                )
            }
            .testTag(MOBILE_TRANSFER_DROP_TARGET_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_magnet),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(R.string.mobile_transfers_drop_title),
                style = PutioTypeScale.titleLg.copy(fontSize = DROP_TITLE_SIZE),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.mobile_transfers_drop_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val AVAILABLE_TINT = 0.08f
private const val OVER_TINT = 0.16f
private val DASH = listOf(8.dp, 6.dp)
private val DROP_TOP_CLEARANCE = 132.dp
private val DROP_BOTTOM_CLEARANCE = 100.dp
private val DROP_TITLE_SIZE = 20.sp
