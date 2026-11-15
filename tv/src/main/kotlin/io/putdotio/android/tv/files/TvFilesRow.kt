package io.putdotio.android.tv.files

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import io.putdotio.android.R
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.files.toDisplayDate
import io.putdotio.android.files.updatedAgo
import io.putdotio.android.tv.TV_LIST_ROW_META_ICON
import io.putdotio.android.tv.TvListRow
import io.putdotio.android.tv.TvListRowMeta
import io.putdotio.android.tv.TvRowMeta
import io.putdotio.android.tv.TvRowMetaPart
import io.putdotio.sdk.files.PutioFolderType

internal const val TV_FILES_ROW_TAG = "tv-files-row"

internal sealed interface TvFilesRowPlace {
    data object Listing : TvFilesRowPlace

    data class SearchResult(val folderName: String?) : TvFilesRowPlace
}

internal fun FilesItem.rowMeta(context: Context, place: TvFilesRowPlace, nowMillis: Long): TvRowMeta {
    val where = when (place) {
        TvFilesRowPlace.Listing -> metaDate(context, nowMillis)
        is TvFilesRowPlace.SearchResult -> place.folderName
    }
    val parts = listOfNotNull(
        metaSize?.let { TvRowMetaPart(it, mono = true) },
        where?.let { TvRowMetaPart(it, shrinks = true) },
    )
    return TvRowMeta(parts, endsWithDot = showsWatched && parts.isNotEmpty())
}

private fun FilesItem.rowLabel(context: Context, place: TvFilesRowPlace): String =
    when {
        isPlayable -> context.getString(R.string.tv_files_play_media, name)
        place is TvFilesRowPlace.SearchResult -> context.getString(R.string.tv_search_open_result, name)
        isFolder -> context.getString(R.string.tv_files_open_folder, name)
        else -> name
    }

@Composable
internal fun TvFilesRow(
    item: FilesItem,
    meta: TvRowMeta,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Long-press Center or the Menu key; null when the row offers nothing beyond opening. */
    onActions: (() -> Unit)? = null,
    showsFocus: Boolean = true,
    place: TvFilesRowPlace = TvFilesRowPlace.Listing,
) {
    val context = LocalContext.current
    val label = remember(item, place, context) { lazy { item.rowLabel(context, place) } }
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
            .semantics { contentDescription = label.value }
            .testTag(TV_FILES_ROW_TAG),
    ) { secondary ->
        TvListRowMeta(meta, secondary)
        if (item.showsWatched && meta !== TvRowMeta.Pending) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_eye),
                contentDescription = stringResource(R.string.tv_files_watched),
                tint = secondary,
                modifier = Modifier.size(TV_LIST_ROW_META_ICON),
            )
        }
    }
}

/** A file's size, or a folder's own; the shared folders have none. */
internal val FilesItem.metaSize: String?
    get() = formatFileSize(sizeBytes).takeIf { !isFolder || folderType == PutioFolderType.REGULAR }

/** A folder dates itself by its last change, "3 days ago"; a file by when it was added, "Oct 13". */
@Composable
internal fun rememberMetaDate(item: FilesItem): String? {
    val context = LocalContext.current
    return remember(item.updatedAt, item.createdAt, item.type, context) {
        item.metaDate(context, System.currentTimeMillis())
    }
}

private fun FilesItem.metaDate(context: Context, nowMillis: Long): String? =
    updatedAgo(nowMillis) ?: createdAt.toDisplayDate(context)
