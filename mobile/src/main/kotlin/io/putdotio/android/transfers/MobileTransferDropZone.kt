package io.putdotio.android.transfers

import android.app.Activity
import android.view.DragEvent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import io.putdotio.android.R
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

@Composable
internal fun MobileTransferDropOverlay(over: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = if (over) OVER_SCRIM else AVAILABLE_SCRIM))
            .padding(16.dp)
            .border(if (over) 4.dp else 2.dp, accent, MaterialTheme.shapes.large)
            .testTag(MOBILE_TRANSFER_DROP_TARGET_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_arrow_circle_down_fill),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(R.string.mobile_transfers_drop_title),
                style = MaterialTheme.typography.titleLarge,
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

private const val AVAILABLE_SCRIM = 0.55f
private const val OVER_SCRIM = 0.75f
