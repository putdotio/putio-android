package io.putdotio.android.files

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.MobileRow
import io.putdotio.android.MobileSelectedRowTint
import io.putdotio.android.MobileSelectionCheckbox
import io.putdotio.android.R
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.putioMono
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.playbackContainer
import io.putdotio.sdk.files.PutioFolderType

internal const val MOBILE_FILES_WATCHED_TAG = "mobile-files-watched"
internal const val MOBILE_FILES_DOWNLOAD_TAG = "mobile-files-download"

/**
 * A file row: a fixed 64dp line with the type glyph, the name cut in the middle on one line, and
 * meta (size in mono, date, then the played eye and the on-device mark when true). A row with
 * actions ends in an overflow button, and a folder without them in a chevron. Long-press runs
 * [onLongClick], which starts selection where the list has one, else opens the actions. In
 * selection a checkbox takes the glyph's place, the trailing slot empties, and a tap toggles the row.
 */
@Composable
internal fun MobileFilesRow(
    item: FilesItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onActions: (() -> Unit)? = null,
    actionsEnabled: Boolean = true,
    /** Long-press, when it does something other than open the actions: starting selection. */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    downloadStatus: DownloadStatus? = null,
    /** The file an outside open came for; it has no screen of its own, so its row stands out. */
    highlighted: Boolean = false,
    /** A folder's listing dates the row; search names the folder it sits in instead. */
    place: MobileFilesRowPlace = MobileFilesRowPlace.Listing,
    /** The file the tablet's detail pane shows, tinted more faintly than an outside open. */
    selected: Boolean = false,
    /** Null outside selection; otherwise whether the row is checked. */
    checked: Boolean? = null,
    onCheckedChange: (() -> Unit)? = null,
) {
    if (checked != null) {
        MobileFilesSelectableRow(item, checked, onCheckedChange, downloadStatus, place, modifier)
        return
    }
    val actionsLabel = stringResource(R.string.mobile_files_actions, item.name)
    // Touch never focuses a row; a keyboard-focused one is outlined so it reads at a glance.
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
                IconButton(
                    onClick = onActions,
                    enabled = actionsEnabled,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ph_dots_three_vertical),
                        contentDescription = actionsLabel,
                    )
                }
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

    MobileRow(
        leading = { FileTypeIcon(type = item.type, modifier = Modifier.size(FILES_ICON_SIZE)) },
        headline = { MobileMiddleCutText(text = item.name) },
        meta = { MobileFilesMeta(item, onDevice = downloadStatus is DownloadStatus.Completed, place = place) },
        trailing = trailing,
        containerColor = when {
            highlighted -> MaterialTheme.colorScheme.secondaryContainer
            selected -> PutioDesignTokens.yellowSolid.copy(alpha = SELECTED_ROW_ALPHA)
            else -> Color.Transparent
        },
        modifier = modifier
            // A playable row is the player's starting bounds.
            .playbackContainer(item.id.value, playable = item.isPlayable)
            .onFocusChanged { focused = it.isFocused }
            .focusOutline(focused, outline)
            .then(interaction)
            .semantics(mergeDescendants = true) { if (highlighted || selected) this.selected = true },
    )
}

/**
 * A row in selection: a checkbox in the glyph's place, nothing trailing, the 9% yellow tint when
 * checked, and a tap that checks or clears it.
 */
@Composable
private fun MobileFilesSelectableRow(
    item: FilesItem,
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
        meta = { MobileFilesMeta(item, onDevice = downloadStatus is DownloadStatus.Completed, place = place) },
        containerColor = if (checked) MobileSelectedRowTint else Color.Transparent,
        modifier = modifier
            .playbackContainer(item.id.value, playable = item.isPlayable)
            .onFocusChanged { focused = it.isFocused }
            .focusOutline(focused, outline)
            .then(
                onCheckedChange?.let { toggle ->
                    Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = { toggle() })
                } ?: Modifier,
            ),
    )
}

/** Where a row is listed: in a folder, which dates it, or in search results, which name its folder. */
internal sealed interface MobileFilesRowPlace {
    data object Listing : MobileFilesRowPlace

    data class SearchResult(val folderName: String?) : MobileFilesRowPlace
}

/**
 * Size in mono, then the date in a folder or the containing folder's name in search, then a grey
 * eye when played and a yellow mark when on this device. A folder dates itself by its last change,
 * "3 days ago"; the shared folders have no size of their own.
 */
@Composable
private fun MobileFilesMeta(item: FilesItem, onDevice: Boolean, place: MobileFilesRowPlace) {
    val context = LocalContext.current
    val size = formatFileSize(item.sizeBytes).takeIf { item.folderType == PutioFolderType.REGULAR }
    val date = when (place) {
        MobileFilesRowPlace.Listing ->
            item.updatedAgo(System.currentTimeMillis()) ?: item.createdAt.toDisplayDate(context)
        is MobileFilesRowPlace.SearchResult -> place.folderName
    }
    val watched = item.playback?.isWatched == true
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val parts = buildList<@Composable RowScope.() -> Unit> {
        size?.let { add { Text(it, style = style.putioMono(), color = color, maxLines = 1) } }
        // The date or folder gives way first, so the glyphs after it keep their room at large text sizes.
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
        if (watched) {
            add {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_eye),
                    contentDescription = stringResource(R.string.mobile_files_watched),
                    tint = color,
                    modifier = Modifier.size(FILES_META_ICON_SIZE).testTag(MOBILE_FILES_WATCHED_TAG),
                )
            }
        }
        if (onDevice) {
            add {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_arrow_circle_down_fill),
                    contentDescription = stringResource(R.string.mobile_files_downloaded),
                    tint = PutioDesignTokens.yellowSolid,
                    modifier = Modifier.size(FILES_META_ICON_SIZE).testTag(MOBILE_FILES_DOWNLOAD_TAG),
                )
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(FILES_META_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) Text("·", style = style, color = color, modifier = Modifier.clearAndSetSemantics {})
            part()
        }
    }
}

/**
 * Tap runs [onClick], or opens the actions when there is nothing else to do; long-press runs
 * [onLongClick] where the list has one, else opens the actions.
 */
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
private val FILES_ICON_SIZE = 26.dp
private val FILES_CHEVRON_SIZE = 20.dp
private val FILES_META_GAP = 6.dp
private val FILES_META_ICON_SIZE = 14.dp
private val FILES_FOCUS_OUTLINE = 2.dp
