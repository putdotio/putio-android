package io.putdotio.android.sharing

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.apiReason
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.mobileMessageResource
import kotlinx.coroutines.launch

internal const val MOBILE_PUBLIC_LINKS_ROUTE = "account/public-links"
internal const val MOBILE_PUBLIC_LINK_SHEET_TAG = "mobile-public-link-sheet"
internal const val MOBILE_PUBLIC_LINKS_LIST_TAG = "mobile-public-links-list"
internal const val MOBILE_PUBLIC_LINK_CREATE_TAG = "mobile-public-link-create"
internal const val MOBILE_PUBLIC_LINK_OUTCOME_TAG = "mobile-public-link-outcome"
internal const val MOBILE_PUBLIC_LINK_REVOKE_CONFIRM_TAG = "mobile-public-link-revoke-confirm"

internal fun mobilePublicLinkCopyTag(id: PublicLinkId): String = "mobile-public-link-copy-${id.value}"

internal fun mobilePublicLinkShareTag(id: PublicLinkId): String = "mobile-public-link-share-${id.value}"

internal fun mobilePublicLinkRevokeTag(id: PublicLinkId): String = "mobile-public-link-revoke-${id.value}"

/** The session's public links and how to change them, handed from the shell to Files and Account. */
internal class MobilePublicLinks(
    val state: PublicLinksState,
    val onEvent: (PublicLinksEvent) -> Boolean,
)

/** Exclusive access for one of the viewer's own items: its links, a new one, copy, share and revoke. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobilePublicLinkSheet(
    item: FilesItem,
    publicLinks: MobilePublicLinks,
    onDismiss: () -> Unit,
) {
    val state = publicLinks.state
    val onEvent = publicLinks.onEvent
    LaunchedEffect(item.id) { onEvent(PublicLinksEvent.Load) }
    val actions = rememberPublicLinkActions(state)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(MOBILE_PUBLIC_LINK_SHEET_TAG),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.mobile_public_links_action),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            PublicLinksWarning()
            val creating = (state.mutation as? PublicLinksMutation.Creating)?.fileId == item.id
            Button(
                onClick = { onEvent(PublicLinksEvent.Create(item.id)) },
                enabled = state.canCreate,
                modifier = Modifier.fillMaxWidth().testTag(MOBILE_PUBLIC_LINK_CREATE_TAG),
            ) {
                Text(
                    stringResource(
                        if (creating) R.string.mobile_public_links_creating else R.string.mobile_public_links_create,
                    ),
                )
            }
            state.outcome?.takeIf { it.fileId == item.id }?.let { outcome ->
                PublicLinksOutcomeLine(outcome, onDismiss = { onEvent(PublicLinksEvent.DismissOutcome) })
            }
            when (val content = state.content) {
                PublicLinksContent.Idle, PublicLinksContent.Loading -> PublicLinksInlineLoading()
                is PublicLinksContent.Failed -> PublicLinksInlineFailure(content.failure) {
                    onEvent(PublicLinksEvent.Load)
                }
                is PublicLinksContent.Ready -> {
                    val links = state.linksFor(item.id).orEmpty()
                    if (links.isEmpty()) {
                        Text(
                            text = stringResource(R.string.mobile_public_links_none_for_item),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    links.forEach { link ->
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(expiryText(link), style = MaterialTheme.typography.bodyMedium)
                            PublicLinkButtons(link, state, actions)
                        }
                    }
                }
            }
        }
    }
    PublicLinkRevokeDialog(actions, state, onEvent)
}

/** Account's list of every public link the account holds, as web's Sharing page shows them. */
@Composable
internal fun MobilePublicLinksScreen(
    publicLinks: MobilePublicLinks,
    modifier: Modifier = Modifier,
) {
    val state = publicLinks.state
    val onEvent = publicLinks.onEvent
    LaunchedEffect(Unit) { onEvent(PublicLinksEvent.Load) }
    val actions = rememberPublicLinkActions(state)
    LazyColumn(modifier.fillMaxSize().testTag(MOBILE_PUBLIC_LINKS_LIST_TAG)) {
        item(key = "warning") {
            Column(Modifier.fillMaxWidth().padding(16.dp)) { PublicLinksWarning() }
        }
        state.outcome?.let { outcome ->
            item(key = "outcome") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    PublicLinksOutcomeLine(outcome, onDismiss = { onEvent(PublicLinksEvent.DismissOutcome) })
                }
            }
        }
        when (val content = state.content) {
            PublicLinksContent.Idle, PublicLinksContent.Loading -> item(key = "loading") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) { PublicLinksInlineLoading() }
            }
            is PublicLinksContent.Failed -> item(key = "failed") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    PublicLinksInlineFailure(content.failure) { onEvent(PublicLinksEvent.Load) }
                }
            }
            is PublicLinksContent.Ready -> {
                if (content.links.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.mobile_public_links_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(content.links, key = { it.id.value }) { link ->
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(link.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(expiryText(link))
                                PublicLinkButtons(link, state, actions)
                            }
                        },
                        leadingContent = { FileTypeIcon(link.fileType, Modifier.size(24.dp)) },
                    )
                }
            }
        }
    }
    PublicLinkRevokeDialog(actions, state, onEvent)
}

@Composable
private fun PublicLinksWarning() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.mobile_public_links_warning_title),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.mobile_public_links_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PublicLinksInlineLoading() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.mobile_public_links_loading))
    }
}

@Composable
private fun PublicLinksInlineFailure(failure: PutioFailure, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = failure.publicLinksMessage(),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.mobile_action_retry)) }
    }
}

@Composable
private fun PublicLinkButtons(link: PublicLink, state: PublicLinksState, actions: PublicLinkActions) {
    val revoking = (state.mutation as? PublicLinksMutation.Revoking)?.link?.id == link.id
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { actions.copy(link) }, modifier = Modifier.testTag(mobilePublicLinkCopyTag(link.id))) {
            Text(
                stringResource(
                    if (actions.copiedId == link.id) R.string.mobile_public_links_copied
                    else R.string.mobile_public_links_copy,
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        TextButton(onClick = { actions.share(link) }, modifier = Modifier.testTag(mobilePublicLinkShareTag(link.id))) {
            Text(stringResource(R.string.mobile_public_links_share))
        }
        TextButton(
            onClick = { actions.confirmRevoke(link) },
            enabled = state.canRevoke,
            modifier = Modifier.testTag(mobilePublicLinkRevokeTag(link.id)),
        ) {
            Text(
                stringResource(
                    if (revoking) R.string.mobile_public_links_revoking else R.string.mobile_public_links_revoke,
                ),
                color = if (state.canRevoke) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Copy, share and the revoke confirmation one surface offers for its links. */
private class PublicLinkActions(
    val copiedId: PublicLinkId?,
    val confirming: PublicLink?,
    val copy: (PublicLink) -> Unit,
    val share: (PublicLink) -> Unit,
    val confirmRevoke: (PublicLink) -> Unit,
    val dismissRevoke: () -> Unit,
)

@Composable
private fun rememberPublicLinkActions(state: PublicLinksState): PublicLinkActions {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copiedId by remember { mutableStateOf<PublicLinkId?>(null) }
    var confirmingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val label = stringResource(R.string.mobile_public_links_clip_label)
    return PublicLinkActions(
        copiedId = copiedId,
        confirming = (state.content as? PublicLinksContent.Ready)?.links?.firstOrNull { it.id.value == confirmingId },
        copy = { link ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(sensitiveClip(label, link.url.value)))
                copiedId = link.id
            }
        },
        share = { link -> context.sharePublicLink(link) },
        confirmRevoke = { link -> confirmingId = link.id.value },
        dismissRevoke = { confirmingId = null },
    )
}

@Composable
private fun PublicLinkRevokeDialog(
    actions: PublicLinkActions,
    state: PublicLinksState,
    onEvent: (PublicLinksEvent) -> Boolean,
) {
    val link = actions.confirming ?: return
    AlertDialog(
        onDismissRequest = actions.dismissRevoke,
        title = { Text(stringResource(R.string.mobile_public_links_revoke_title)) },
        text = { Text(stringResource(R.string.mobile_public_links_revoke_message, link.fileName)) },
        confirmButton = {
            TextButton(
                onClick = {
                    actions.dismissRevoke()
                    onEvent(PublicLinksEvent.Revoke(link.id))
                },
                enabled = state.canRevoke,
                modifier = Modifier.testTag(MOBILE_PUBLIC_LINK_REVOKE_CONFIRM_TAG),
            ) {
                Text(stringResource(R.string.mobile_public_links_revoke), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.dismissRevoke) { Text(stringResource(R.string.mobile_action_cancel)) }
        },
    )
}

@Composable
private fun PublicLinksOutcomeLine(outcome: PublicLinksOutcome, onDismiss: () -> Unit) {
    val failed = outcome is PublicLinksOutcome.CreateFailed || outcome is PublicLinksOutcome.RevokeFailed
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag(MOBILE_PUBLIC_LINK_OUTCOME_TAG),
    ) {
        Text(
            text = outcome.message(),
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.mobile_action_ok)) }
    }
}

@Composable
private fun PublicLinksOutcome.message(): String =
    when (this) {
        is PublicLinksOutcome.Created -> stringResource(R.string.mobile_public_links_created)
        is PublicLinksOutcome.Revoked -> stringResource(R.string.mobile_public_links_revoked)
        is PublicLinksOutcome.CreateFailed -> refusal?.let { stringResource(it.messageResource()) }
            ?: stringResource(R.string.mobile_public_links_create_failed, failure.publicLinksMessage())
        is PublicLinksOutcome.RevokeFailed ->
            stringResource(R.string.mobile_public_links_revoke_failed, failure.publicLinksMessage())
    }

/** put.io's reason for a refused request, else the shared copy; a bare 403 names links, not a folder. */
@Composable
private fun PutioFailure.publicLinksMessage(): String =
    apiReason ?: stringResource(
        if (this is PutioFailure.AccessDenied) R.string.mobile_public_links_access_denied else mobileMessageResource(),
    )

@StringRes
private fun PublicLinkRefusal.messageResource(): Int =
    when (this) {
        PublicLinkRefusal.PLAN_NOT_ALLOWED -> R.string.mobile_public_links_refused_plan
        PublicLinkRefusal.UNSUPPORTED_FILE_TYPE -> R.string.mobile_public_links_refused_file_type
        PublicLinkRefusal.LINK_LIMIT -> R.string.mobile_public_links_refused_limit
        PublicLinkRefusal.FOLDER_TOO_BIG -> R.string.mobile_public_links_refused_folder_size
        PublicLinkRefusal.FOLDER_TOO_MANY_FILES -> R.string.mobile_public_links_refused_folder_children
        PublicLinkRefusal.DAILY_LIMIT -> R.string.mobile_public_links_refused_daily
        PublicLinkRefusal.WEEKLY_LIMIT -> R.string.mobile_public_links_refused_weekly
    }

@Composable
private fun expiryText(link: PublicLink): String {
    val context = LocalContext.current
    val expiresAt = link.expiresAt ?: return stringResource(R.string.mobile_public_links_expiry_unknown)
    return stringResource(
        R.string.mobile_public_links_expires,
        DateUtils.formatDateTime(
            context,
            expiresAt.toEpochMilli(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        ),
    )
}

/**
 * The address is a bearer link to the file, so Android 13+ keeps it out of the clipboard preview.
 */
private fun sensitiveClip(label: String, url: String): ClipData =
    ClipData.newPlainText(label, url).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
    }

/** The link alone, as text: the address carries only the share's token, never the session's. */
private fun Context.sharePublicLink(link: PublicLink) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, link.url.value)
        .putExtra(Intent.EXTRA_TITLE, link.fileName)
    startActivity(Intent.createChooser(send, null))
}
