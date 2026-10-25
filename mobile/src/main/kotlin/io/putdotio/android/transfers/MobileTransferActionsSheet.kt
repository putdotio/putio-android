package io.putdotio.android.transfers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.putioMono
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepository

internal const val MOBILE_TRANSFER_ACTIONS_TAG = "mobile-transfer-actions"

/** What Transfer actions can do; each is offered only when the transfer allows it. */
internal class MobileTransferActions(
    val onOpenFile: () -> Unit,
    val onOpenFolder: () -> Unit,
    val onCopySource: () -> Unit,
    val onStop: () -> Unit,
    val onRetry: () -> Unit,
    val onRemove: () -> Unit,
)

/**
 * Transfer actions: the full name, the state and a progress bar, the facts in mono, then Open folder
 * and Copy source link when put.io returned a link. A running transfer ends with Stop transfer in red;
 * a failed one offers Retry; a finished one Open file and Remove from list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileTransferActionsSheet(
    item: TransferItem,
    enabled: Boolean,
    folders: FilesRepository?,
    actions: MobileTransferActions,
    onDismiss: () -> Unit,
    onAuthenticationRequired: suspend () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = SHEET_BOTTOM_PADDING)
                .testTag(MOBILE_TRANSFER_ACTIONS_TAG),
        ) {
            MobileTransferActionsHeader(item)
            MobileTransferFacts(item, folders, onAuthenticationRequired)
            HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            MobileTransferActionRows(item, enabled, actions, onDismiss)
        }
    }
}

@Composable
private fun MobileTransferActionRows(
    item: TransferItem,
    enabled: Boolean,
    actions: MobileTransferActions,
    onDismiss: () -> Unit,
) {
    fun run(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    if (item.status == AppTransferStatus.Failed) {
        MobileTransferActionRow(
            R.drawable.ic_ph_arrow_clockwise,
            R.string.mobile_transfer_retry,
            enabled,
            run(actions.onRetry),
        )
    }
    if (item.canOpen) {
        MobileTransferActionRow(R.drawable.ic_ph_file, R.string.mobile_transfers_open, enabled, run(actions.onOpenFile))
    }
    if (item.saveParentId != null) {
        MobileTransferActionRow(
            R.drawable.ic_ph_folder_open,
            R.string.mobile_transfer_open_folder,
            enabled,
            run(actions.onOpenFolder),
        )
    }
    if (item.source != null) {
        MobileTransferActionRow(
            R.drawable.ic_ph_copy,
            R.string.mobile_transfer_copy_source,
            enabled = true,
            onClick = run(actions.onCopySource),
        )
    }
    when {
        item.status.canCancel -> {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            MobileTransferActionRow(
                R.drawable.ic_ph_stop_circle,
                R.string.mobile_transfers_cancel_confirm,
                enabled,
                run(actions.onStop),
                destructive = true,
            )
        }
        item.canRemove -> {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            MobileTransferActionRow(R.drawable.ic_ph_x, R.string.mobile_transfer_remove, enabled, run(actions.onRemove))
        }
    }
}

@Composable
private fun MobileTransferActionsHeader(item: TransferItem) {
    val status = stringResource(item.statusLabelRes())
    Column(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SelectionContainer {
            Text(
                text = item.name,
                style = PutioTypeScale.bodyLg.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val state = buildList {
            add(MobileTransferPart(status))
            item.percentText()?.takeIf { item.section != MobileTransferSection.NeedsAttention }?.let {
                add(MobileTransferPart(META_SEPARATOR))
                add(MobileTransferPart(it, mono = true))
            }
        }
        MobileTransferMeta(item.dot, state, maxLines = 1)
        // A running transfer without a reported share shows an empty track rather than no bar.
        val reported = item.percentDone?.takeIf { it in 0.0..100.0 }
        val percent = when (item.section) {
            MobileTransferSection.NeedsAttention -> null
            MobileTransferSection.Active -> reported ?: 0.0
            MobileTransferSection.Completed -> reported
        }
        if (percent != null) {
            LinearProgressIndicator(
                progress = { (percent / 100.0).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(4.dp)
                    .clearAndSetSemantics {},
                color = if (item.status == AppTransferStatus.Seeding) {
                    PutioDesignTokens.greenSolid
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun MobileTransferFacts(
    item: TransferItem,
    folders: FilesRepository?,
    onAuthenticationRequired: suspend () -> Unit,
) {
    val context = LocalContext.current
    item.sizeParts(context).takeIf { it.isNotEmpty() }?.let {
        MobileTransferFact(R.string.mobile_transfer_fact_size, it)
    }
    item.speedText(context)?.let {
        MobileTransferFact(R.string.mobile_transfer_fact_speed, listOf(MobileTransferPart(it, mono = true)))
    }
    item.timeLeftText(context)?.let {
        MobileTransferFact(R.string.mobile_transfer_fact_time_left, listOf(MobileTransferPart(it, mono = true)))
    }
    item.ratioText()?.takeIf { item.status == AppTransferStatus.Seeding }?.let {
        MobileTransferFact(R.string.mobile_transfer_fact_ratio, listOf(MobileTransferPart(it, mono = true)))
    }
    val rootName = stringResource(R.string.mobile_destination_files)
    val folderName by produceState<String?>(null, item.saveParentId, folders) {
        val id = item.saveParentId
        val resolved = if (id == null || id == 0L) null else folders?.resolveItem(FilesItemId(id))
        // A folder that can't be read only hides the row; a rejected session signs out as anywhere else.
        if ((resolved as? PutioResult.Failure)?.failure is PutioFailure.AuthenticationRequired) {
            onAuthenticationRequired()
        }
        value = if (id == 0L) rootName else (resolved as? PutioResult.Success)?.value?.name
    }
    folderName?.let { MobileTransferFact(R.string.mobile_transfer_fact_saves_to, listOf(MobileTransferPart(it))) }
}

@Composable
private fun MobileTransferFact(label: Int, value: List<MobileTransferPart>) {
    val style = PutioTypeScale.bodySm
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(label),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Weighted after the label, so a long folder name wraps instead of pushing the label out.
        Text(
            value.annotated(style.putioMono().toSpanStyle()),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MobileTransferActionRow(
    @DrawableRes icon: Int,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val text = if (destructive) PutioDesignTokens.redTextSecondary else MaterialTheme.colorScheme.onSurface
    val tint = if (destructive) PutioDesignTokens.redTextSecondary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = tint.dimmed(enabled),
            modifier = Modifier.size(24.dp),
        )
        Text(stringResource(label), style = PutioTypeScale.bodyLg, color = text.dimmed(enabled))
    }
}

private fun Color.dimmed(enabled: Boolean): Color = if (enabled) this else copy(alpha = DISABLED_ALPHA)

private const val DISABLED_ALPHA = 0.38f

/** The sheet already pads for the navigation bar; together they make the board's 28dp. */
internal val SHEET_BOTTOM_PADDING = 4.dp
