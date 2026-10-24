package io.putdotio.android

import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.putdotio.android.design.putioMono
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MOBILE_TAB_TITLE_TAG = "mobile-tab-title"
internal const val MOBILE_TAB_SUMMARY_TAG = "mobile-tab-summary"

/**
 * History, Transfers, Downloads and Account open on a tab title: an action row when the tab has
 * actions, the title in headline-lg, and an optional one-line summary with its numbers in mono.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileTabTitle(
    title: String,
    summary: AnnotatedString?,
    hasActions: Boolean,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
        if (hasActions) {
            Row(
                Modifier.fillMaxWidth().height(TOP_APP_BAR_HEIGHT).padding(end = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        } else {
            Spacer(Modifier.height(16.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { heading() }
                .testTag(MOBILE_TAB_TITLE_TAG),
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp).testTag(MOBILE_TAB_SUMMARY_TAG),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** One summary part: a template with one `%1$s` slot whose value sets in mono. */
internal data class SummaryPart(val template: String, val value: String)

internal fun summaryOf(parts: List<SummaryPart>, mono: SpanStyle): AnnotatedString? =
    parts.takeIf { it.isNotEmpty() }?.let {
        buildAnnotatedString {
            parts.forEachIndexed { index, part ->
                if (index > 0) append(" · ")
                val slot = part.template.indexOf(VALUE_SLOT)
                if (slot < 0) {
                    append(part.template)
                } else {
                    append(part.template.substring(0, slot))
                    withStyle(mono) { append(part.value) }
                    append(part.template.substring(slot + VALUE_SLOT.length))
                }
            }
        }
    }

@Composable
internal fun summaryMonoStyle(): SpanStyle = MaterialTheme.typography.bodyMedium.putioMono().toSpanStyle()

/** Running and failed counts and the combined download speed, over the transfers loaded so far. */
internal fun transfersSummaryParts(context: Context, state: TransfersState): List<SummaryPart> {
    val items = (state.content as? TransfersContent.Ready)?.items.orEmpty()
    val active = items.filter(TransferItem::isActiveTransfer)
    val failed = items.count { it.status == AppTransferStatus.Failed }
    val speed = active.sumOf { it.downloadSpeedBytesPerSecond?.takeIf { bytes -> bytes > 0.0 } ?: 0.0 }
    return buildList {
        if (active.isNotEmpty()) {
            val template = context.getString(R.string.mobile_transfers_summary_active, VALUE_SLOT)
            add(SummaryPart(template, active.size.toString()))
        }
        if (failed > 0) {
            add(SummaryPart(context.getString(R.string.mobile_transfers_summary_failed, VALUE_SLOT), failed.toString()))
        }
        if (speed > 0.0) {
            add(SummaryPart(VALUE_SLOT, context.getString(
                R.string.mobile_transfer_speed,
                Formatter.formatShortFileSize(context, speed.toLong()),
            )))
        }
    }
}

/** A transfer put.io is working on now; queued and waiting ones are not active yet. */
private val TransferItem.isActiveTransfer: Boolean
    get() = when (status) {
        AppTransferStatus.PreparingDownload,
        AppTransferStatus.Downloading,
        AppTransferStatus.Completing,
        -> true
        AppTransferStatus.Waiting,
        AppTransferStatus.Queued,
        AppTransferStatus.WaitingForCompleteQueue,
        AppTransferStatus.WaitingForDownloader,
        AppTransferStatus.Stopping,
        AppTransferStatus.Seeding,
        AppTransferStatus.PreparingSeed,
        AppTransferStatus.Completed,
        AppTransferStatus.Failed,
        is AppTransferStatus.Unknown,
        -> false
    }

/** Space the finished downloads take, and what the app's storage volume has free. */
internal fun downloadsSummaryParts(context: Context, storageBytes: Long, freeBytes: Long?): List<SummaryPart> =
    buildList {
        add(SummaryPart(
            context.getString(R.string.mobile_downloads_storage, VALUE_SLOT),
            Formatter.formatShortFileSize(context, storageBytes),
        ))
        freeBytes?.let {
            add(SummaryPart(
                context.getString(R.string.mobile_downloads_free, VALUE_SLOT),
                Formatter.formatShortFileSize(context, it),
            ))
        }
    }

@Composable
internal fun downloadsSummary(state: DownloadsState, mono: SpanStyle): AnnotatedString? {
    val context = LocalContext.current
    // Downloads live in the app's files directory, so its volume's free space is what they can use.
    val freeBytes by produceState<Long?>(null, state.storageBytes, state.entries.size) {
        value = withContext(Dispatchers.IO) {
            runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull()
        }
    }
    return summaryOf(downloadsSummaryParts(context, state.storageBytes, freeBytes), mono)
}

private const val VALUE_SLOT = "%1\$s"
private val TOP_APP_BAR_HEIGHT = 64.dp
