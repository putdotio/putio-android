package io.putdotio.android.viewer

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.formatFileSize
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The image, fitted to the frame. Coil decodes it at the frame's size, never at its own, so a
 * 50-megapixel photo costs one screen of memory.
 */
@Composable
internal fun MobileImagePage(file: FilesItem, uri: Uri, onOpenWithAnotherApp: () -> Unit) {
    SubcomposeAsyncImage(
        model = uri,
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        loading = { MobileViewerLoading() },
        error = { MobileViewerFailure(onOpenWithAnotherApp) },
        success = { SubcomposeAsyncImageContent() },
    )
}

private sealed interface PdfState {
    data object Loading : PdfState

    data class Ready(val document: MobilePdfDocument) : PdfState

    data object Failed : PdfState
}

/** The document's pages down one column at the frame's width, each rendered while it is on screen. */
@Composable
internal fun MobilePdfPage(file: FilesItem, uri: Uri, onOpenWithAnotherApp: () -> Unit) {
    val context = LocalContext.current
    val state by produceState<PdfState>(PdfState.Loading, uri) {
        var opened: MobilePdfDocument? = null
        value = try {
            MobilePdfDocument.open(context, uri, file.sizeBytes).also { opened = it }.let(PdfState::Ready)
        } catch (_: IOException) {
            PdfState.Failed
        } catch (_: SecurityException) {
            PdfState.Failed
        }
        awaitDispose { opened?.close() }
    }
    when (val current = state) {
        PdfState.Loading -> MobileViewerLoading()
        PdfState.Failed -> MobileViewerFailure(onOpenWithAnotherApp)
        is PdfState.Ready -> MobilePdfPages(current.document, onOpenWithAnotherApp)
    }
}

@Composable
private fun MobilePdfPages(document: MobilePdfDocument, onOpenWithAnotherApp: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = viewerContentPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(count = document.pageCount) { index ->
                MobilePdfPageImage(document, index, widthPx, onOpenWithAnotherApp)
            }
        }
    }
}

private sealed interface PageState {
    data object Loading : PageState

    data class Ready(val bitmap: Bitmap, val ratio: Float) : PageState

    data object Failed : PageState
}

/** One page, A4-shaped until it renders; a page that won't render says so and offers another app. */
@Composable
private fun MobilePdfPageImage(
    document: MobilePdfDocument,
    index: Int,
    widthPx: Int,
    onOpenWithAnotherApp: () -> Unit,
) {
    val state by produceState<PageState>(PageState.Loading, document, index, widthPx) {
        value = try {
            val bitmap = document.render(index, widthPx)
            PageState.Ready(bitmap, bitmap.height.toFloat() / bitmap.width)
        } catch (_: IllegalStateException) {
            // The document closed under the page, or the renderer refused it.
            PageState.Failed
        } catch (_: IllegalArgumentException) {
            PageState.Failed
        }
    }
    when (val current = state) {
        PageState.Loading -> Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / A4_RATIO)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        PageState.Failed -> Box(Modifier.fillMaxWidth().aspectRatio(1f / A4_RATIO)) {
            MobileViewerFailure(onOpenWithAnotherApp)
        }
        is PageState.Ready -> Image(
            bitmap = current.bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f / current.ratio),
        )
    }
}

private sealed interface TextState {
    data object Loading : TextState

    data class Ready(val preview: MobileTextPreview) : TextState

    data object Failed : TextState
}

/** The file's start in mono, line by line; past [TEXT_PREVIEW_LIMIT_BYTES] it says so and offers another app. */
@Composable
internal fun MobileTextPage(uri: Uri, onOpenWithAnotherApp: () -> Unit) {
    val context = LocalContext.current
    val state by produceState<TextState>(TextState.Loading, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { TextState.Ready(it.readTextPreview()) }
                    ?: TextState.Failed
            } catch (_: IOException) {
                TextState.Failed
            } catch (_: SecurityException) {
                TextState.Failed
            }
        }
    }
    when (val current = state) {
        TextState.Loading -> MobileViewerLoading()
        TextState.Failed -> MobileViewerFailure(onOpenWithAnotherApp)
        is TextState.Ready -> MobileTextLines(current.preview, onOpenWithAnotherApp)
    }
}

@Composable
private fun MobileTextLines(preview: MobileTextPreview, onOpenWithAnotherApp: () -> Unit) {
    // A few hundred lines per item keep each layout small without a node per line.
    val chunks = preview.lines.chunked(TEXT_LINES_PER_ITEM).map { it.joinToString("\n") }
    val style = PutioTypeScale.mono.copy(color = MaterialTheme.colorScheme.onSurface)
    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = viewerContentPadding(horizontal = 16.dp),
        ) {
            items(chunks) { chunk -> Text(chunk, style = style) }
            if (preview.truncated) {
                item {
                    Text(
                        text = stringResource(R.string.mobile_viewer_text_truncated, TEXT_LIMIT_LABEL),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    TextButton(onClick = onOpenWithAnotherApp) {
                        Text(stringResource(R.string.mobile_viewer_open_with))
                    }
                }
            }
        }
    }
}

@Composable
internal fun MobileViewerLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(24.dp)
                .clearAndSetSemantics {},
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Under the bar on top and the position line at the bottom, and clear of the system bars. */
@Composable
private fun viewerContentPadding(horizontal: Dp = 0.dp): PaddingValues {
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = safe.calculateStartPadding(direction) + horizontal,
        end = safe.calculateEndPadding(direction) + horizontal,
        top = safe.calculateTopPadding() + VIEWER_CONTENT_TOP,
        bottom = safe.calculateBottomPadding() + VIEWER_CONTENT_BOTTOM,
    )
}

private const val A4_RATIO = 1.414f
private const val TEXT_LINES_PER_ITEM = 200
private val TEXT_LIMIT_LABEL = formatFileSize(TEXT_PREVIEW_LIMIT_BYTES.toLong())
