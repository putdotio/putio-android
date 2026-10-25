package io.putdotio.android.transfers

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.putioMono
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.mobileMessage
import io.putdotio.android.neutralTextButtonColors
import kotlinx.coroutines.launch

internal const val MOBILE_TRANSFER_ADD_FIELD_TAG = "mobile-transfer-add-field"
internal const val MOBILE_TRANSFER_ADD_CONFIRM_TAG = "mobile-transfer-add-confirm"
internal const val MOBILE_TRANSFER_PASTE_TAG = "mobile-transfer-paste"
internal const val MOBILE_TRANSFER_PICK_TORRENT_TAG = "mobile-transfer-pick-torrent"

/** The Add transfer sheet, its destination picker and the replace-draft question, while the draft is open. */
@Composable
internal fun MobileAddTransfer(
    draft: MobileTransferDraft,
    state: TransfersState,
    onEvent: (TransfersEvent) -> Unit,
    enabled: Boolean,
    filesRepository: FilesRepository?,
    onFilesAuthenticationRequired: suspend () -> Unit,
) {
    val input by draft.state.collectAsStateWithLifecycle()
    val adding = (state.mutation as? TransferMutation.Running)?.action is TransferAction.Add
    var choosingDestination by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    if (input.open) {
        val addFailure = (state.mutation as? TransferMutation.Failed)?.takeIf { it.action is TransferAction.Add }
        val dismissAddFailure = { if (addFailure != null) onEvent(TransfersEvent.DismissMutationFailure) }
        MobileAddTransferSheet(
            input = input,
            failure = addFailure,
            adding = adding,
            submitEnabled = enabled && !input.readingTorrent && input.validation != MobileShareValidation.TooLong,
            actions = MobileAddTransferActions(
                onInputChanged = {
                    draft.edit(it)
                    dismissAddFailure()
                },
                onPickTorrent = { uri ->
                    draft.receiveLater(picked = true) {
                        context.contentResolver.readMobileTorrent(uri, context.packageName)
                    }
                    dismissAddFailure()
                },
                onRemoveTorrent = {
                    draft.removeTorrent()
                    dismissAddFailure()
                },
                onChooseDestination = filesRepository?.let { { choosingDestination = true } },
                onResetDestination = { draft.chooseDestination(null) },
                onDismiss = {
                    if (!adding) {
                        draft.dismiss()
                        dismissAddFailure()
                    }
                },
                onSubmit = {
                    if (enabled) draft.validate()?.let { add ->
                        dismissAddFailure()
                        onEvent(add)
                    }
                },
            ),
        )
    }
    if (input.open && choosingDestination && filesRepository != null) {
        MobileTransferDestinationPicker(
            repository = filesRepository,
            onAuthenticationRequired = onFilesAuthenticationRequired,
            onDismiss = { choosingDestination = false },
            onChoose = { folder ->
                draft.chooseDestination(folder)
                choosingDestination = false
            },
        )
    }
    if (input.pendingReplacement) {
        MobileShareReplacementDialog(draft = draft, mutation = state.mutation, onEvent = onEvent)
    }
}

internal data class MobileAddTransferActions(
    val onInputChanged: (String) -> Unit,
    val onPickTorrent: (Uri) -> Unit,
    val onRemoveTorrent: () -> Unit,
    val onChooseDestination: (() -> Unit)?,
    val onResetDestination: () -> Unit,
    val onDismiss: () -> Unit,
    val onSubmit: () -> Unit,
)

@Composable
private fun MobileShareReplacementDialog(
    draft: MobileTransferDraft,
    mutation: TransferMutation,
    onEvent: (TransfersEvent) -> Unit,
) {
    val mutationRunning = mutation is TransferMutation.Running
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    AlertDialog(
        modifier = if (landscape) {
            Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth()
        } else {
            Modifier
        },
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
        onDismissRequest = { if (!mutationRunning) draft.keepDraft() },
        title = { Text(stringResource(R.string.mobile_share_replace_title)) },
        text = {
            Text(
                stringResource(R.string.mobile_share_replace_message),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                draft.useSharedLink()
                val failedAction = (mutation as? TransferMutation.Failed)?.action
                if (failedAction is TransferAction.Add) onEvent(TransfersEvent.DismissMutationFailure)
            }, enabled = !mutationRunning) {
                Text(stringResource(R.string.mobile_share_use_link))
            }
        },
        dismissButton = {
            TextButton(
                onClick = draft::keepDraft,
                enabled = !mutationRunning,
                colors = neutralTextButtonColors(),
            ) {
                Text(stringResource(R.string.mobile_share_keep_draft))
            }
        },
    )
}

/**
 * One field for links and magnets, checked a line at a time, with Paste and .torrent chips under it;
 * then where the transfers save, and Cancel and Add, which counts the links that will go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileAddTransferSheet(
    input: MobileTransferDraftState,
    failure: TransferMutation.Failed?,
    adding: Boolean,
    submitEnabled: Boolean,
    actions: MobileAddTransferActions,
) {
    val lines = remember(input.input) { checkMobileTransferLines(input.input) }
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = SHEET_BOTTOM_PADDING),
        ) {
            Text(
                text = stringResource(R.string.mobile_transfers_add_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            val reason = input.reason(lines, failure?.failure?.mobileMessage())
            if (input.torrent != null) {
                MobileTransferTorrentRow(input.torrent, enabled = !adding, onRemove = actions.onRemoveTorrent)
            } else {
                MobileAddTransferField(
                    value = input.input,
                    lines = lines,
                    isError = input.validation != null || failure != null,
                    enabled = !adding,
                    submitEnabled = submitEnabled,
                    actions = actions,
                )
            }
            reason?.let { MobileAddTransferReason(it) }
            if (input.torrent == null) {
                MobileAddTransferChips(enabled = !adding && !input.readingTorrent, input.input, actions)
            }
            MobileTransferDestinationRow(
                input.destination,
                enabled = !adding,
                onChoose = actions.onChooseDestination,
                onReset = actions.onResetDestination,
            )
            MobileAddTransferButtons(
                count = if (input.torrent == null) lines.links.size else 0,
                adding = adding,
                submitEnabled = submitEnabled,
                onDismiss = actions.onDismiss,
                onSubmit = actions.onSubmit,
            )
        }
    }
}

@Composable
private fun MobileAddTransferField(
    value: String,
    lines: MobileTransferLines,
    isError: Boolean,
    enabled: Boolean,
    submitEnabled: Boolean,
    actions: MobileAddTransferActions,
) {
    val label = stringResource(R.string.mobile_transfers_add_label)
    val dimmed = MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedTextField(
        value = value,
        onValueChange = actions.onInputChanged,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .testTag(MOBILE_TRANSFER_ADD_FIELD_TAG),
        enabled = enabled,
        isError = isError,
        textStyle = MaterialTheme.typography.bodyMedium.putioMono(),
        placeholder = {
            Text(stringResource(R.string.mobile_transfers_add_placeholder), style = MaterialTheme.typography.bodyLarge)
        },
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
        ),
        visualTransformation = remember(lines, dimmed) { DimBadLines(lines.badRanges, dimmed) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (submitEnabled) actions.onSubmit() }),
        minLines = 3,
        maxLines = 6,
    )
}

/** Lines that won't go stay in the field, dimmed, so the good ones read first. */
private class DimBadLines(
    private val ranges: List<IntRange>,
    private val color: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (ranges.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val dimmed = buildAnnotatedString {
            append(text)
            ranges.forEach { range ->
                val end = (range.last + 1).coerceAtMost(text.length)
                if (range.first < end) addStyle(SpanStyle(color = color), range.first, end)
            }
        }
        return TransformedText(dimmed, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean =
        other is DimBadLines && other.ranges == ranges && other.color == color

    override fun hashCode(): Int = 31 * ranges.hashCode() + color.hashCode()
}

@Composable
private fun MobileAddTransferReason(reason: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MobileTransferDotMark(MobileTransferDot.Failed)
        Spacer(Modifier.width(8.dp))
        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MobileAddTransferChips(enabled: Boolean, value: String, actions: MobileAddTransferActions) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val pickTorrent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(actions.onPickTorrent)
    }
    val chipColors = AssistChipDefaults.assistChipColors(leadingIconContentColor = MaterialTheme.colorScheme.onSurface)
    Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = {
                scope.launch {
                    // Text and link items only: coerceToText would read a content URI on the main thread.
                    val item = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    val link = item?.uri?.takeIf { it.scheme in PASTE_SCHEMES }?.toString()
                    val pasted = (item?.text?.toString() ?: link)?.trim()
                    if (!pasted.isNullOrEmpty()) {
                        actions.onInputChanged(if (value.isBlank()) pasted else value.trimEnd() + "\n" + pasted)
                    }
                }
            },
            enabled = enabled,
            label = { Text(stringResource(R.string.mobile_transfers_paste)) },
            leadingIcon = { ChipIcon(R.drawable.ic_ph_clipboard_text) },
            colors = chipColors,
            modifier = Modifier.testTag(MOBILE_TRANSFER_PASTE_TAG),
        )
        AssistChip(
            onClick = { pickTorrent.launch(TORRENT_PICKER_TYPES) },
            enabled = enabled,
            label = { Text(stringResource(R.string.mobile_transfers_pick_torrent)) },
            leadingIcon = { ChipIcon(R.drawable.ic_ph_file_arrow_up) },
            colors = chipColors,
            modifier = Modifier.testTag(MOBILE_TRANSFER_PICK_TORRENT_TAG),
        )
    }
}

@Composable
private fun ChipIcon(icon: Int) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
}

@Composable
private fun MobileAddTransferButtons(
    count: Int,
    adding: Boolean,
    submitEnabled: Boolean,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(colors = neutralTextButtonColors(), onClick = onDismiss, enabled = !adding) {
            Text(stringResource(R.string.mobile_action_cancel))
        }
        val addingDescription = stringResource(R.string.mobile_transfers_adding)
        val mono = MaterialTheme.typography.labelLarge.putioMono().toSpanStyle()
        val label = if (count > 0) {
            val template = stringResource(R.string.mobile_transfers_add_count, COUNT_SLOT)
            buildAnnotatedString {
                append(template.substringBefore(COUNT_SLOT))
                withStyle(mono) { append(count.toString()) }
                append(template.substringAfter(COUNT_SLOT))
            }
        } else {
            AnnotatedString(stringResource(R.string.mobile_transfers_add_confirm))
        }
        Button(
            onClick = onSubmit,
            enabled = submitEnabled,
            modifier = Modifier
                .testTag(MOBILE_TRANSFER_ADD_CONFIRM_TAG)
                .semantics {
                    if (adding) {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = addingDescription
                    }
                },
        ) {
            if (adding) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(label)
        }
    }
}

/**
 * What the sheet says under the field: the draft's own problem first, then why put.io refused the last
 * Add, then the lines that won't parse.
 */
@Composable
private fun MobileTransferDraftState.reason(lines: MobileTransferLines, failure: String?): String? {
    val own = validation?.takeUnless { it == MobileShareValidation.InvalidLink && lines.badLines.isNotEmpty() }
    return when {
        own != null -> stringResource(own.messageResource())
        failure != null -> failure
        torrent != null || lines.badLines.isEmpty() -> null
        else -> pluralStringResource(
            R.plurals.mobile_transfers_bad_lines,
            lines.badLines.size,
            lines.badLines.joinToString(", "),
        )
    }
}

private val TORRENT_PICKER_TYPES = arrayOf(TORRENT_MEDIA_TYPE, "application/octet-stream")
private const val COUNT_SLOT = "\u0001"
private val PASTE_SCHEMES = setOf("http", "https", "magnet")
