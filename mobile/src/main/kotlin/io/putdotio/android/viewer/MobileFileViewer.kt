package io.putdotio.android.viewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.MobileFileViewerKind
import io.putdotio.android.files.viewerKind

internal const val MOBILE_FILE_VIEWER_TAG = "mobile-file-viewer"
internal const val MOBILE_FILE_VIEWER_POSITION_TAG = "mobile-file-viewer-position"

/** What a viewer reaches outside itself: the file's bytes and the same actions the Files sheet runs. */
internal class MobileFileViewerActions(
    val documentUri: (FilesItem) -> Uri,
    val onOpenWithAnotherApp: (FilesItem) -> Unit,
    val onShare: ((FilesItem) -> Unit)? = null,
    val onMore: ((FilesItem) -> Unit)? = null,
)

/**
 * Images, PDFs and text on a black frame, over the whole window. The name and the file's actions
 * sit on a top scrim; a swipe moves between the folder's files of the same kind, and the position
 * shows in mono below. [files] are those files in listing order; [onShown] follows the swipe.
 */
@Composable
internal fun MobileFileViewer(
    files: List<FilesItem>,
    shownId: FilesItemId,
    actions: MobileFileViewerActions,
    onShown: (FilesItemId) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MobilePdfDocument.clearCache(context) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MobileFileViewerContent(files, shownId, actions, onShown, onDismiss)
    }
}

@Composable
internal fun MobileFileViewerContent(
    files: List<FilesItem>,
    shownId: FilesItemId,
    actions: MobileFileViewerActions,
    onShown: (FilesItemId) -> Unit,
    onDismiss: () -> Unit,
) {
    val shownIndex = files.indexOfFirst { it.id == shownId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = shownIndex) { files.size }
    // A file that left the folder, moved or deleted from the sheet, keeps the pager on the one shown.
    LaunchedEffect(shownIndex, files.size) {
        if (pager.currentPage != shownIndex && !pager.isScrollInProgress) pager.scrollToPage(shownIndex)
    }
    val currentFiles by rememberUpdatedState(files)
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            currentFiles.getOrNull(page)?.let { currentOnShown(it.id) }
        }
    }
    val frame = MaterialTheme.colorScheme.scrim
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(frame)
            .testTag(MOBILE_FILE_VIEWER_TAG),
    ) {
        HorizontalPager(
            state = pager,
            key = { files.getOrNull(it)?.id?.value ?: it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val file = files.getOrNull(page) ?: return@HorizontalPager
            val uri = actions.documentUri(file)
            val openElsewhere = { actions.onOpenWithAnotherApp(file) }
            when (file.viewerKind) {
                MobileFileViewerKind.Image -> MobileImagePage(file, uri, openElsewhere)
                MobileFileViewerKind.Pdf -> MobilePdfPage(file, uri, openElsewhere)
                MobileFileViewerKind.Text -> MobileTextPage(uri, openElsewhere)
                null -> MobileViewerFailure(openElsewhere)
            }
        }
        files.getOrNull(pager.currentPage)?.let { shown ->
            MobileFileViewerBar(
                file = shown,
                actions = actions,
                onBack = onDismiss,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (files.size > 1) {
            MobileFileViewerPosition(
                position = pager.currentPage + 1,
                count = files.size,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Back, the name cut in the middle, then Share and the overflow, over a scrim that fades out. */
@Composable
private fun MobileFileViewerBar(
    file: FilesItem,
    actions: MobileFileViewerActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrim = MaterialTheme.colorScheme.scrim
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(scrim.copy(alpha = VIEWER_SCRIM_ALPHA), Color.Transparent)))
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_ph_arrow_left), stringResource(R.string.mobile_action_back))
            }
            MobileMiddleCutText(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
            )
            actions.onShare?.let { share ->
                IconButton(onClick = { share(file) }) {
                    Icon(painterResource(R.drawable.ic_ph_share_network), stringResource(R.string.mobile_files_share))
                }
            }
            actions.onMore?.let { more ->
                IconButton(onClick = { more(file) }) {
                    Icon(
                        painterResource(R.drawable.ic_ph_dots_three_vertical),
                        stringResource(R.string.mobile_files_actions, file.name),
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileFileViewerPosition(position: Int, count: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.mobile_viewer_position, position, count)
    Text(
        text = "$position / $count",
        style = PutioTypeScale.mono,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = 16.dp, bottom = VIEWER_POSITION_BOTTOM)
            .semantics { contentDescription = description }
            .testTag(MOBILE_FILE_VIEWER_POSITION_TAG),
    )
}

/** The empty-state voice for a file the viewer can't show, with the one way left to see it. */
@Composable
internal fun MobileViewerFailure(onOpenWithAnotherApp: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.mobile_viewer_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenWithAnotherApp) {
            Text(stringResource(R.string.mobile_viewer_open_with))
        }
    }
}

/** Top clear of the bar, bottom clear of the position line, for pages that scroll under both. */
internal val VIEWER_CONTENT_TOP = 64.dp
internal val VIEWER_CONTENT_BOTTOM = 56.dp

private const val VIEWER_SCRIM_ALPHA = 0.6f
private val VIEWER_POSITION_BOTTOM = 16.dp
