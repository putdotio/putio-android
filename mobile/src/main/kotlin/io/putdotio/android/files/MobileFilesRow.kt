package io.putdotio.android.files

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.MobileRow
import io.putdotio.android.MobileSelectedRowTint
import io.putdotio.android.MobileSelectionCheckbox
import io.putdotio.android.design.RowText
import io.putdotio.android.design.rememberRowText
import io.putdotio.android.monoValue
import io.putdotio.android.R
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.putioMono
import io.putdotio.android.downloads.DownloadRingTrack
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.label
import io.putdotio.android.playbackContainer
import io.putdotio.sdk.files.PutioFolderType
import io.putdotio.android.MobileRowIconButton

internal const val MOBILE_FILES_WATCHED_TAG = "mobile-files-watched"
internal const val MOBILE_FILES_DOWNLOAD_TAG = "mobile-files-download"
internal const val MOBILE_FILES_DOWNLOAD_RING_TAG = "mobile-files-download-ring"
internal const val MOBILE_FILES_DOWNLOAD_FAILED_TAG = "mobile-files-download-failed"

@Composable
internal fun MobileFilesRow(
    item: FilesItem,
    text: FilesRowText,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onActions: (() -> Unit)? = null,
    actionsEnabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    downloadStatus: DownloadStatus? = null,
    highlighted: Boolean = false,
    place: MobileFilesRowPlace = MobileFilesRowPlace.Listing,
    selected: Boolean = false,
    checked: Boolean? = null,
    onCheckedChange: (() -> Unit)? = null,
    playbackBounds: Boolean = true,
) {
    if (checked != null) {
        MobileFilesSelectableRow(item, text, checked, onCheckedChange, downloadStatus, place, modifier)
        return
    }
    val actionsLabel = stringResource(R.string.mobile_files_actions, item.name)
    var focused by remember { mutableStateOf(false) }
    val outline = MaterialTheme.colorScheme.primary
    val interaction = Modifier.rowInteraction(
        onClick = onClick,
        onClickLabel = onClickLabel,
        onActions = onActions?.takeIf { actionsEnabled },
        onLongClick = onLongClick,
        onLongClickLabel = if (onLongClick != null) onLongClickLabel else actionsLabel,
    )

    val trailing: (@Composable () -> Unit)? = when {
        onActions != null -> {
            {
                MobileRowIconButton(
                    actionsLabel,
                    R.drawable.ic_ph_dots_three_vertical,
                    onActions,
                    enabled = actionsEnabled,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
        item.isFolder -> {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_caret_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp).size(FILES_CHEVRON_SIZE),
                )
            }
        }
        else -> null
    }

    val tracksPlaybackBounds = item.isPlayable && playbackBounds
    var visibleInViewport by remember(tracksPlaybackBounds) { mutableStateOf(false) }
    val visibilityGate = if (tracksPlaybackBounds) {
        Modifier.onVisibilityChanged(minFractionVisible = MIN_SHARED_BOUNDS_VISIBLE_FRACTION) { visible ->
            visibleInViewport = visible
        }
    } else {
        Modifier
    }

    MobileRow(
        leading = { FileTypeIcon(type = item.type, modifier = Modifier.size(FILES_ICON_SIZE)) },
        headline = { MobileMiddleCutText(text = item.name) },
        meta = { MobileFilesMeta(item, text, downloadStatus = downloadStatus, place = place) },
        trailing = trailing,
        containerColor = when {
            highlighted -> MaterialTheme.colorScheme.secondaryContainer
            selected -> PutioDesignTokens.yellowSolid.copy(alpha = SELECTED_ROW_ALPHA)
            else -> Color.Transparent
        },
        modifier = modifier
            .then(visibilityGate)
            .playbackContainer(item.id.value, playable = tracksPlaybackBounds && visibleInViewport)
            .onFocusChanged { focused = it.isFocused }
            .focusOutline(focused, outline)
            .then(interaction)
            .semantics(mergeDescendants = true) { if (highlighted || selected) this.selected = true },
    )
}

@Composable
private fun MobileFilesSelectableRow(
    item: FilesItem,
    text: FilesRowText,
    checked: Boolean,
    onCheckedChange: (() -> Unit)?,
    downloadStatus: DownloadStatus?,
    place: MobileFilesRowPlace,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val outline = MaterialTheme.colorScheme.primary
    MobileRow(
        leading = { MobileSelectionCheckbox(checked) },
        headline = { MobileMiddleCutText(text = item.name) },
        meta = { MobileFilesMeta(item, text, downloadStatus = downloadStatus, place = place) },
        containerColor = if (checked) MobileSelectedRowTint else Color.Transparent,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .focusOutline(focused, outline)
            .then(
                onCheckedChange?.let { toggle ->
                    Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = { toggle() })
                } ?: Modifier,
            ),
    )
}

internal data class FilesRowText(val size: String?, val date: String?)

private fun FilesItem.rowText(context: Context, dated: Boolean): FilesRowText =
    FilesRowText(
        size = formatFileSize(sizeBytes).takeIf { folderType == PutioFolderType.REGULAR },
        date = if (dated) updatedAgo(System.currentTimeMillis()) ?: createdAt.toDisplayDate(context) else null,
    )

@Composable
internal fun rememberFilesRowText(
    items: List<FilesItem>,
    dated: Boolean = true,
): RowText<FilesItem, FilesRowText> {
    val context = LocalContext.current.applicationContext
    val locales = LocalConfiguration.current.locales
    return rememberRowText(
        items,
        context,
        locales,
        dated,
        key = { it.id.value },
        placeholder = FilesRowText(size = null, date = null),
        everyMinute = dated,
    ) {
        it.rowText(context, dated)
    }
}

internal sealed interface MobileFilesRowPlace {
    data object Listing : MobileFilesRowPlace

    data class SearchResult(val folderName: String?) : MobileFilesRowPlace
}

@Composable
private fun MobileFilesMeta(
    item: FilesItem,
    text: FilesRowText,
    downloadStatus: DownloadStatus?,
    place: MobileFilesRowPlace,
) {
    val size = text.size
    val date = when (place) {
        MobileFilesRowPlace.Listing -> text.date
        is MobileFilesRowPlace.SearchResult -> place.folderName
    }
    val finished = item.watchedChoice() == false
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val parts = buildList<@Composable RowScope.() -> Unit> {
        size?.let {
            add { Text(monoValue(it, style.putioMono().toSpanStyle()), style = style, color = color, maxLines = 1) }
        }
        date?.let {
            add {
                Text(
                    it,
                    style = style,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (finished) {
            add {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_eye),
                    contentDescription = stringResource(R.string.mobile_files_watched),
                    tint = color,
                    modifier = Modifier.size(FILES_META_ICON_SIZE).testTag(MOBILE_FILES_WATCHED_TAG),
                )
            }
        }
        downloadStatus?.takeIf { it != DownloadStatus.Missing }?.let { status ->
            add { MobileFilesDownloadMark(status) }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(FILES_META_GAP),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = with(LocalDensity.current) { style.lineHeight.toDp() }),
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) Text("·", style = style, color = color, modifier = Modifier.clearAndSetSemantics {})
            part()
        }
    }
}

@Composable
private fun MobileFilesDownloadMark(status: DownloadStatus) {
    when (status) {
        is DownloadStatus.Completed -> Icon(
            painter = painterResource(R.drawable.ic_ph_arrow_circle_down_fill),
            contentDescription = stringResource(R.string.mobile_files_downloaded),
            tint = PutioDesignTokens.yellowSolid,
            modifier = Modifier.size(FILES_META_ICON_SIZE).testTag(MOBILE_FILES_DOWNLOAD_TAG),
        )
        is DownloadStatus.Failed -> Icon(
            painter = painterResource(R.drawable.ic_ph_warning_circle),
            contentDescription = stringResource(R.string.mobile_downloads_meta_failed),
            tint = PutioDesignTokens.redTextSecondary,
            modifier = Modifier.size(FILES_META_ICON_SIZE).testTag(MOBILE_FILES_DOWNLOAD_FAILED_TAG),
        )
        DownloadStatus.Queued -> MobileFilesDownloadRing(status, R.string.mobile_downloads_status_queued)
        is DownloadStatus.Downloading -> MobileFilesDownloadRing(status, R.string.mobile_files_downloading)
        is DownloadStatus.Paused -> MobileFilesDownloadRing(status, status.reason.label)
        DownloadStatus.Missing -> Unit
    }
}

@Composable
private fun MobileFilesDownloadRing(status: DownloadStatus, @StringRes labelRes: Int) {
    val label = stringResource(labelRes)
    DownloadRingTrack(
        status,
        FILES_META_ICON_SIZE,
        Modifier.testTag(MOBILE_FILES_DOWNLOAD_RING_TAG).clearAndSetSemantics { contentDescription = label },
    )
}

private fun Modifier.rowInteraction(
    onClick: (() -> Unit)?,
    onClickLabel: String?,
    onActions: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onLongClickLabel: String?,
): Modifier {
    val longClick = onLongClick ?: onActions
    val tap = onClick ?: onActions
    return when {
        longClick != null -> combinedClickable(
            onClickLabel = onClickLabel,
            role = Role.Button,
            onClick = tap ?: {},
            onLongClickLabel = onLongClickLabel,
            onLongClick = longClick,
        )
        tap != null -> clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = tap)
        else -> this
    }
}

private fun Modifier.focusOutline(focused: Boolean, color: Color): Modifier =
    if (focused) border(FILES_FOCUS_OUTLINE, color) else this

private const val SELECTED_ROW_ALPHA = 0.09f
private const val MIN_SHARED_BOUNDS_VISIBLE_FRACTION = 0.001f
private val FILES_ICON_SIZE = 26.dp
private val FILES_CHEVRON_SIZE = 20.dp
private val FILES_META_GAP = 6.dp
private val FILES_META_ICON_SIZE = 14.dp
private val FILES_FOCUS_OUTLINE = 2.dp
