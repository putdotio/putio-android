package io.putdotio.android.tv.files

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.files.toDisplayDate
import io.putdotio.android.tv.TV_LIST_ROW_META
import io.putdotio.android.tv.TV_LIST_ROW_META_ICON
import io.putdotio.android.tv.TV_LIST_ROW_META_MONO
import io.putdotio.android.tv.TvListRow
import io.putdotio.android.tv.TvListRowMetaParts

internal const val TV_FILES_ROW_TAG = "tv-files-row"

@Composable
internal fun TvFilesRow(
    item: FilesItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Long-press Center or the Menu key; null when the row offers nothing beyond opening. */
    onActions: (() -> Unit)? = null,
    /** What Center does here, when it is not the Files default of open, play, or explain. */
    label: String = when {
        item.isFolder -> stringResource(R.string.tv_files_open_folder, item.name)
        item.isPlayable -> stringResource(R.string.tv_files_play_media, item.name)
        else -> item.name
    },
    showsFocus: Boolean = true,
) {
    TvListRow(
        glyph = fileTypeIconRes(item.type),
        title = item.name,
        onClick = onClick,
        onLongClick = onActions,
        trailingIcon = R.drawable.ic_ph_caret_right.takeIf { item.isFolder },
        showsFocus = showsFocus,
        modifier = modifier
            .onKeyEvent { event ->
                if (onActions != null && event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                    onActions()
                    true
                } else {
                    false
                }
            }
            .semantics { contentDescription = label }
            .testTag(TV_FILES_ROW_TAG),
    ) { secondary -> TvFilesRowMeta(item, secondary) }
}

@Composable
private fun TvFilesRowMeta(item: FilesItem, color: Color) {
    val context = LocalContext.current
    val size = if (item.isFolder) null else formatFileSize(item.sizeBytes)
    val date = remember(item.createdAt, context) { item.createdAt.toDisplayDate(context) }
    val watched = item.playback?.isWatched == true
    val parts = buildList<@Composable () -> Unit> {
        size?.let { add { Text(it, style = TV_LIST_ROW_META_MONO, color = color, maxLines = 1) } }
        date?.let { add { Text(it, style = TV_LIST_ROW_META, color = color, maxLines = 1) } }
        if (watched) {
            add {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_eye),
                    contentDescription = stringResource(R.string.tv_files_watched),
                    tint = color,
                    modifier = Modifier.size(TV_LIST_ROW_META_ICON),
                )
            }
        }
    }
    TvListRowMetaParts(parts, color)
}
