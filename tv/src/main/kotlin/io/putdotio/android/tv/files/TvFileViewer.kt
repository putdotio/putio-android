package io.putdotio.android.tv.files

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.putdotio.android.R
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPdfDocument
import io.putdotio.android.files.FilesStreamUrlResult
import io.putdotio.android.files.FilesTextPreview
import io.putdotio.android.files.FilesViewerKind
import io.putdotio.android.files.TEXT_PREVIEW_LIMIT_BYTES
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.files.readTextPreview
import io.putdotio.android.files.viewerKind
import io.putdotio.android.tv.TvEmptyState
import io.putdotio.android.tv.TvEmptyStateAction
import io.putdotio.android.tv.TvLoadingIndicator
import io.putdotio.android.tv.returnFocusFromSheet
import io.putdotio.android.tv.tvSafeAreaMargins
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal const val TV_FILE_VIEWER_TAG = "tv-file-viewer"

/** The original file's URL for this app's own viewer; it carries the download token, so it goes nowhere else. */
internal typealias TvViewerUrl = suspend (FilesItemId) -> FilesStreamUrlResult

/** A file the viewer shows and where focus goes back to when it closes. */
internal class TvFileViewerOpen(val id: FilesItemId, val returnFocus: () -> FocusRequester)

/** Which file, if any, a pane shows in the viewer. */
@Stable
internal class TvFileViewerState {
    var open: TvFileViewerOpen? by mutableStateOf(null)

    /** Shows [item] when the viewer takes its kind; otherwise hands it to [otherwise]. */
    fun openOr(
        item: FilesItem,
        viewerUrl: TvViewerUrl?,
        returnFocus: () -> FocusRequester,
        otherwise: (FilesItem) -> Unit,
    ) {
        if (viewerUrl != null && item.viewerKind != null) {
            open = TvFileViewerOpen(item.id, returnFocus)
        } else {
            otherwise(item)
        }
    }
}

/**
 * The viewer over a pane while [state] names one of [items]; it closes when that file leaves them.
 * With [siblings], an image pages through the other images in [items].
 */
@Composable
internal fun TvFileViewerLayer(
    state: TvFileViewerState,
    items: List<FilesItem>,
    viewerUrl: TvViewerUrl?,
    siblings: Boolean,
) {
    val open = state.open ?: return
    val shown = items.firstOrNull { it.id == open.id }
    if (shown == null || viewerUrl == null) {
        LaunchedEffect(Unit) { state.open = null }
        return
    }
    TvFileViewer(
        files = if (siblings) viewerFilesFor(items, shown) else listOf(shown),
        shownId = shown.id,
        viewerUrl = viewerUrl,
        returnFocus = open.returnFocus(),
        onDismiss = { state.open = null },
    )
}

/** Images page through the folder's other images; a PDF or text file stands alone. */
internal fun viewerFilesFor(items: List<FilesItem>, shown: FilesItem): List<FilesItem> =
    if (shown.viewerKind == FilesViewerKind.Image) {
        items.filter { it.viewerKind == FilesViewerKind.Image }
    } else {
        listOf(shown)
    }

/**
 * Images, PDFs and text on black over the whole screen. Left and Right move between [files], or
 * between a PDF's pages, Up and Down scroll text, and Back closes it and returns focus to the opener.
 */
@Composable
internal fun TvFileViewer(
    files: List<FilesItem>,
    shownId: FilesItemId,
    viewerUrl: TvViewerUrl,
    returnFocus: FocusRequester,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // A crashed viewer's copies go before this one copies its first document.
    val cacheCleared by produceState(false) {
        withContext(Dispatchers.IO) { FilesPdfDocument.clearCache(context) }
        value = true
    }
    val opener by rememberUpdatedState(returnFocus)
    DisposableEffect(Unit) {
        onDispose { opener.returnFocusFromSheet() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        if (cacheCleared) {
            TvFileViewerContent(files, shownId, viewerUrl, onDismiss)
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 1f)))
        }
    }
}

@Composable
internal fun TvFileViewerContent(
    files: List<FilesItem>,
    shownId: FilesItemId,
    viewerUrl: TvViewerUrl,
    onDismiss: () -> Unit,
) {
    var index by remember { mutableIntStateOf(files.indexOfFirst { it.id == shownId }.coerceAtLeast(0)) }
    val file = files.getOrNull(index) ?: return
    val keys = remember { TvViewerKeys() }
    val focus = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 1f))
            .testTag(TV_FILE_VIEWER_TAG)
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown &&
                    (keys.onKey(event.key) || stepFiles(event.key, index, files.size)?.also { index = it } != null)
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        key(file.id) { TvViewerFile(file, viewerUrl, keys, onDismiss) }
        if (files.size > 1) {
            TvViewerPosition(index + 1, files.size, Modifier.align(Alignment.BottomCenter))
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        focus.requestFocus()
    }
}

/** The file index Left or Right moves to, or null when the key doesn't move between files. */
private fun stepFiles(key: Key, index: Int, count: Int): Int? = when {
    count < 2 -> null
    key == Key.DirectionLeft -> (index - 1).coerceAtLeast(0)
    key == Key.DirectionRight -> (index + 1).coerceAtMost(count - 1)
    else -> null
}

@Composable
private fun TvViewerFile(file: FilesItem, viewerUrl: TvViewerUrl, keys: TvViewerKeys, onDismiss: () -> Unit) {
    val source by produceState<TvViewerSource>(TvViewerSource.Loading, file.id) {
        value = when (val url = viewerUrl(file.id)) {
            is FilesStreamUrlResult.Ready -> TvViewerSource.Ready(url.url)
            FilesStreamUrlResult.DownloadTokenUnavailable,
            is FilesStreamUrlResult.Failure,
            -> TvViewerSource.Failed
        }
    }
    when (val current = source) {
        TvViewerSource.Loading -> TvViewerLoading()
        TvViewerSource.Failed -> TvViewerFailure(onDismiss)
        is TvViewerSource.Ready -> when (file.viewerKind) {
            FilesViewerKind.Image -> TvImagePage(file, current.url, onDismiss)
            FilesViewerKind.Pdf -> TvPdfPage(file, current.url, keys, onDismiss)
            FilesViewerKind.Text -> TvTextPage(current.url, keys, onDismiss)
            null -> TvViewerFailure(onDismiss)
        }
    }
}

/** The page's own handling of a D-pad key, ahead of moving between files. */
private class TvViewerKeys {
    var onKey: (Key) -> Boolean = { false }
}

private sealed interface TvViewerSource {
    data object Loading : TvViewerSource

    class Ready(val url: String) : TvViewerSource {
        override fun toString(): String = "Ready(<redacted stream url>)"
    }

    data object Failed : TvViewerSource
}

/**
 * The image fitted to the screen and decoded at its size. It is cached in memory by file id and never
 * on disk, so the tokened URL is not kept as a cache key.
 */
@Composable
private fun TvImagePage(file: FilesItem, url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey("tv-viewer-${file.id.value}")
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        loading = { TvViewerLoading() },
        error = { TvViewerFailure(onDismiss) },
        success = { SubcomposeAsyncImageContent() },
    )
}

private sealed interface PdfState {
    data object Loading : PdfState

    data class Ready(val document: FilesPdfDocument) : PdfState

    data object Failed : PdfState
}

/** One page at a time, fitted to the screen; Left or Up goes back a page, Right or Down forward. */
@Composable
private fun TvPdfPage(file: FilesItem, url: String, keys: TvViewerKeys, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by produceState<PdfState>(PdfState.Loading, url) {
        var opened: FilesPdfDocument? = null
        value = try {
            FilesPdfDocument.open(context, file.sizeBytes) { openViewerStream(url) }
                .also { opened = it }
                .let(PdfState::Ready)
        } catch (_: IOException) {
            PdfState.Failed
        } catch (_: SecurityException) {
            PdfState.Failed
        }
        awaitDispose { opened?.close() }
    }
    when (val current = state) {
        PdfState.Loading -> TvViewerLoading()
        PdfState.Failed -> TvViewerFailure(onDismiss)
        is PdfState.Ready -> TvPdfPages(current.document, keys, onDismiss)
    }
}

private sealed interface PageState {
    data object Loading : PageState

    data class Ready(val bitmap: Bitmap) : PageState

    data object Failed : PageState
}

@Composable
private fun TvPdfPages(document: FilesPdfDocument, keys: TvViewerKeys, onDismiss: () -> Unit) {
    var page by remember(document) { mutableIntStateOf(0) }
    val count = document.pageCount
    DisposableEffect(keys, document) {
        keys.onKey = { key ->
            when (key) {
                Key.DirectionLeft, Key.DirectionUp -> {
                    page = (page - 1).coerceAtLeast(0)
                    true
                }
                Key.DirectionRight, Key.DirectionDown -> {
                    page = (page + 1).coerceAtMost(count - 1)
                    true
                }
                else -> false
            }
        }
        onDispose { keys.onKey = { false } }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margins = with(LocalDensity.current) { tvSafeAreaMargins(constraints) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = margins.vertical),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val widthPx = constraints.maxWidth
                val heightPx = constraints.maxHeight
                val state by produceState<PageState>(PageState.Loading, document, page, widthPx, heightPx) {
                    value = PageState.Loading
                    value = try {
                        PageState.Ready(document.renderFitting(page, widthPx, heightPx))
                    } catch (_: IllegalStateException) {
                        // The document closed under the page, or the renderer refused it.
                        PageState.Failed
                    } catch (_: IllegalArgumentException) {
                        PageState.Failed
                    }
                }
                when (val current = state) {
                    PageState.Loading -> TvViewerLoading()
                    PageState.Failed -> TvViewerFailure(onDismiss)
                    is PageState.Ready -> Image(
                        bitmap = current.bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.tv_viewer_page, page + 1, count),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (count > 1) TvViewerPositionText(page + 1, count, Modifier.padding(top = PAGE_POSITION_GAP))
        }
    }
}

private sealed interface TextState {
    data object Loading : TextState

    data class Ready(val preview: FilesTextPreview) : TextState

    data object Failed : TextState
}

/** The file's start in mono; Up and Down scroll most of a screen. Past the limit it says so. */
@Composable
private fun TvTextPage(url: String, keys: TvViewerKeys, onDismiss: () -> Unit) {
    val state by produceState<TextState>(TextState.Loading, url) {
        value = withContext(Dispatchers.IO) {
            try {
                openViewerStream(url).use { input -> TextState.Ready(input.readTextPreview { isActive }) }
            } catch (_: IOException) {
                TextState.Failed
            }
        }
    }
    when (val current = state) {
        TextState.Loading -> TvViewerLoading()
        TextState.Failed -> TvViewerFailure(onDismiss)
        is TextState.Ready -> TvTextLines(current.preview, keys)
    }
}

@Composable
private fun TvTextLines(preview: FilesTextPreview, keys: TvViewerKeys) {
    val chunks = remember(preview) { preview.lines.chunked(TEXT_LINES_PER_ITEM).map { it.joinToString("\n") } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    DisposableEffect(keys, list) {
        keys.onKey = { key ->
            val step = list.layoutInfo.viewportSize.height * TEXT_SCROLL_FRACTION
            when (key) {
                Key.DirectionDown -> {
                    scope.launch { list.animateScrollBy(step) }
                    true
                }
                Key.DirectionUp -> {
                    scope.launch { list.animateScrollBy(-step) }
                    true
                }
                else -> false
            }
        }
        onDispose { keys.onKey = { false } }
    }
    val style = PutioTypeScale.tvMono.copy(color = MaterialTheme.colorScheme.onSurface)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margins = with(LocalDensity.current) { tvSafeAreaMargins(constraints) }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = margins.side, vertical = margins.vertical),
        ) {
            items(chunks) { chunk -> Text(chunk, style = style) }
            if (preview.truncated) {
                item {
                    Text(
                        text = stringResource(R.string.tv_viewer_text_truncated, TEXT_LIMIT_LABEL),
                        style = PutioTypeScale.tvMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = margins.vertical),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvViewerLoading() {
    TvLoadingIndicator(stringResource(R.string.tv_state_loading))
}

@Composable
private fun TvViewerFailure(onDismiss: () -> Unit) {
    TvEmptyState(
        glyph = PutioEmptyGlyph.CouldNotLoad,
        headline = stringResource(R.string.tv_viewer_failed_title),
        message = stringResource(R.string.tv_viewer_failed_message),
        action = TvEmptyStateAction(stringResource(R.string.tv_files_go_back), onDismiss),
        modifier = Modifier.fillMaxSize(),
    )
}

/** "n / total" in mono over a scrim that fades up, so it stays readable over a light picture. */
@Composable
private fun TvViewerPosition(position: Int, count: Int, modifier: Modifier = Modifier) {
    val scrim = MaterialTheme.colorScheme.scrim
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, scrim.copy(alpha = POSITION_SCRIM_ALPHA)))),
        contentAlignment = Alignment.Center,
    ) {
        val margins = with(LocalDensity.current) { tvSafeAreaMargins(constraints) }
        TvViewerPositionText(
            position,
            count,
            Modifier.padding(top = margins.vertical, bottom = margins.vertical / 2),
        )
    }
}

@Composable
private fun TvViewerPositionText(position: Int, count: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.tv_viewer_position, position, count)
    Text(
        text = "$position / $count",
        style = PutioTypeScale.tvMono,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { contentDescription = description },
    )
}

/** The file's bytes from put.io; blocking, so callers run it off the main thread. */
private fun openViewerStream(url: String): InputStream {
    val response = viewerHttp.newCall(Request.Builder().url(url).build()).execute()
    if (!response.isSuccessful) {
        response.close()
        throw IOException("put.io answered ${response.code}")
    }
    return response.body.byteStream()
}

private val viewerHttp: OkHttpClient by lazy { OkHttpClient() }

private const val TEXT_LINES_PER_ITEM = 200
private const val POSITION_SCRIM_ALPHA = 0.6f
private val PAGE_POSITION_GAP = 8.dp
private const val TEXT_SCROLL_FRACTION = 0.8f
private val TEXT_LIMIT_LABEL = formatFileSize(TEXT_PREVIEW_LIMIT_BYTES.toLong())
