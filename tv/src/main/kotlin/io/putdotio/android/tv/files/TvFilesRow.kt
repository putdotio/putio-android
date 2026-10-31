package io.putdotio.android.tv.files

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.files.toDisplayDate
import io.putdotio.android.tv.TvFocus
import io.putdotio.android.tv.tvAccent

internal const val TV_FILES_ROW_TAG = "tv-files-row"

/**
 * The phone's file row on TV: the type glyph, the name, then size, date and the watched eye;
 * a folder adds a caret. Focus turns the whole row light and the glyph dark.
 */
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
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val secondary = if (focused) TvFocus.secondaryContent else colors.onSurfaceVariant
    Surface(
        onClick = onClick,
        onLongClick = onActions,
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PutioRadius.medium)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = colors.onSurface,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ROW_PADDING),
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(fileTypeIconRes(item.type)),
                contentDescription = null,
                tint = tvAccent(focused),
                modifier = Modifier.size(ROW_GLYPH),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = ROW_TITLE, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TvFilesRowMeta(item, secondary, Modifier.padding(top = 1.dp))
            }
            if (item.isFolder) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_caret_right),
                    contentDescription = null,
                    tint = secondary,
                    modifier = Modifier.size(ROW_CARET),
                )
            }
        }
    }
}

/** Size in mono for files, the date, then the grey eye when played; no progress in rows. */
@Composable
private fun TvFilesRowMeta(item: FilesItem, color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val size = if (item.isFolder) null else formatFileSize(item.sizeBytes)
    val date = remember(item.createdAt, context) { item.createdAt.toDisplayDate(context) }
    val watched = item.playback?.isWatched == true
    val parts = buildList<@Composable () -> Unit> {
        size?.let { add { Text(it, style = ROW_META_MONO, color = color, maxLines = 1) } }
        date?.let { add { Text(it, style = ROW_META, color = color, maxLines = 1) } }
        if (watched) {
            add {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_eye),
                    contentDescription = stringResource(R.string.tv_files_watched),
                    tint = color,
                    modifier = Modifier.size(ROW_META_ICON),
                )
            }
        }
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ROW_META_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Text("·", style = ROW_META, color = color, modifier = Modifier.clearAndSetSemantics {})
            }
            part()
        }
    }
}

private val ROW_HEIGHT = 48.dp
private val ROW_PADDING = 12.dp
private val ROW_GAP = 16.dp
private val ROW_GLYPH = 20.dp
private val ROW_CARET = 16.dp
private val ROW_META_GAP = 5.dp
private val ROW_META_ICON = 13.dp
private val ROW_TITLE = PutioTypeScale.tvBody.copy(lineHeight = 16.sp)
private val ROW_META = PutioTypeScale.tvMeta.copy(lineHeight = 14.sp)
private val ROW_META_MONO = PutioTypeScale.tvMono.copy(lineHeight = 14.sp)
