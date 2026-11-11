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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.compose.SubcomposeAsyncImageScope
import io.putdotio.android.R
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesPdfDocument
import io.putdotio.android.files.FilesPdfUnreadable
import io.putdotio.android.files.FilesTextPreview
import io.putdotio.android.files.TEXT_PREVIEW_LIMIT_BYTES
import io.putdotio.android.files.readTextPreview
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The image, fitted to the frame over a checkerboard. Coil decodes it at the frame's size, never at
 * its own, so a 50-megapixel photo costs one screen of memory. Bytes that don't arrive offer Try
 * again; bytes that don't decode offer another app.
 */
@Composable
internal fun MobileImagePage(
    file: FilesItem,
    uri: Uri,
    zoom: MobileViewerZoom?,
    onOpenWithAnotherApp: () -> Unit,
) {
    var attempt by remember(uri) { mutableIntStateOf(0) }
    key(attempt) {
        SubcomposeAsyncImage(
            model = uri,
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            loading = { MobileViewerLoading() },
            error = { failure ->
                if (failure.result.throwable is IOException) {
                    MobileViewerLoadFailure(onRetry = { attempt++ }, onOpenWithAnotherApp = onOpenWithAnotherApp)
                } else {
                    MobileViewerFailure(onOpenWithAnotherApp)
                }
            },
            success = { MobileImageContent(zoom) },
        )
    }
}

@Composable
private fun SubcomposeAsyncImageScope.MobileImageContent(zoom: MobileViewerZoom?) {
    val image = this
    val size = painter.intrinsicSize
    if (size.isSpecified && size.width > 0f && size.height > 0f) {
        val aspect = size.width / size.height
        Box(Modifier.fillMaxSize().viewerZoom(zoom, aspect), contentAlignment = Alignment.Center) {
            image.SubcomposeAsyncImageContent(
                Modifier
                    .aspectRatio(aspect)
                    .transparencyGrid(),
            )
        }
    } else {
        SubcomposeAsyncImageContent(Modifier.viewerZoom(zoom))
    }
}

@Composable
private fun Modifier.transparencyGrid(): Modifier {
    val light = MaterialTheme.colorScheme.surfaceContainerHighest
    val dark = MaterialTheme.colorScheme.surfaceContainerHigh
    val cell = with(LocalDensity.current) { TRANSPARENCY_CELL.roundToPx() }
    val brush = remember(light, dark, cell) {
        val tile = ImageBitmap(cell * 2, cell * 2)
        Canvas(tile).apply {
            val paint = Paint()
            paint.color = dark
            drawRect(0f, 0f, cell * 2f, cell * 2f, paint)
            paint.color = light
            drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), paint)
            drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, paint)
        }
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
    return drawBehind { drawRect(brush) }
}

private sealed interface PdfState {
    data object Loading : PdfState

    data class Ready(val document: FilesPdfDocument) : PdfState

    data object LoadFailed : PdfState

    data object Failed : PdfState
}

/**
 * The document's pages down one column at the frame's width, each rendered while it is on screen.
 * Bytes that don't arrive offer Try again; a document the renderer refuses offers another app.
 */
@Composable
internal fun MobilePdfPage(
    file: FilesItem,
    uri: Uri,
    zoom: MobileViewerZoom?,
    onOpenWithAnotherApp: () -> Unit,
) {
    val context = LocalContext.current
    var attempt by remember(uri) { mutableIntStateOf(0) }
    val state by produceState<PdfState>(PdfState.Loading, uri, attempt) {
        value = PdfState.Loading
        var opened: FilesPdfDocument? = null
        value = try {
            FilesPdfDocument.open(context, file.sizeBytes) { context.contentResolver.openInputStream(uri) }
                .also { opened = it }
                .let(PdfState::Ready)
        } catch (_: FilesPdfUnreadable) {
            PdfState.Failed
        } catch (_: IOException) {
            PdfState.LoadFailed
        } catch (_: SecurityException) {
            PdfState.Failed
        }
        awaitDispose { opened?.close() }
    }
    when (val current = state) {
        PdfState.Loading -> MobileViewerLoading()
        PdfState.LoadFailed -> MobileViewerLoadFailure(
            onRetry = { attempt++ },
            onOpenWithAnotherApp = onOpenWithAnotherApp,
        )
        PdfState.Failed -> MobileViewerFailure(onOpenWithAnotherApp)
        is PdfState.Ready -> MobilePdfPages(current.document, zoom, onOpenWithAnotherApp)
    }
}

@Composable
private fun MobilePdfPages(
    document: FilesPdfDocument,
    zoom: MobileViewerZoom?,
    onOpenWithAnotherApp: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        // The pages are pictures, so readers get the way to an app that reads the document's text.
        val openElsewhere = stringResource(R.string.mobile_viewer_open_with)
        val list = rememberLazyListState()
        LazyColumn(
            state = list,
            userScrollEnabled = zoom?.zoomed != true,
            modifier = Modifier
                .fillMaxSize()
                .viewerZoom(zoom) { overflow -> list.dispatchRawDelta(-overflow.y) }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(openElsewhere) {
                            onOpenWithAnotherApp()
                            true
                        },
                    )
                },
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
    document: FilesPdfDocument,
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
            contentDescription = stringResource(R.string.mobile_viewer_page, index + 1, document.pageCount),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f / current.ratio),
        )
    }
}

private sealed interface TextState {
    data object Loading : TextState

    data class Ready(val preview: FilesTextPreview) : TextState

    data object LoadFailed : TextState

    data object Failed : TextState
}

/**
 * The file's start in mono, line by line; past [TEXT_PREVIEW_LIMIT_BYTES] it says so and offers
 * another app. Bytes that don't arrive offer Try again.
 */
@Composable
internal fun MobileTextPage(uri: Uri, onOpenWithAnotherApp: () -> Unit) {
    val context = LocalContext.current
    var attempt by remember(uri) { mutableIntStateOf(0) }
    val state by produceState<TextState>(TextState.Loading, uri, attempt) {
        value = TextState.Loading
        value = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    TextState.Ready(input.readTextPreview { isActive })
                }
                    ?: TextState.Failed
            } catch (_: IOException) {
                TextState.LoadFailed
            } catch (_: SecurityException) {
                TextState.Failed
            }
        }
    }
    when (val current = state) {
        TextState.Loading -> MobileViewerLoading()
        TextState.LoadFailed -> MobileViewerLoadFailure(
            onRetry = { attempt++ },
            onOpenWithAnotherApp = onOpenWithAnotherApp,
        )
        TextState.Failed -> MobileViewerFailure(onOpenWithAnotherApp)
        is TextState.Ready -> MobileTextLines(current.preview, onOpenWithAnotherApp)
    }
}

@Composable
private fun MobileTextLines(preview: FilesTextPreview, onOpenWithAnotherApp: () -> Unit) {
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

@Composable
private fun viewerContentPadding(horizontal: Dp = 0.dp): PaddingValues {
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = safe.calculateStartPadding(direction) + horizontal,
        end = safe.calculateEndPadding(direction) + horizontal,
        top = safe.calculateTopPadding() + VIEWER_CONTENT_TOP,
        bottom = VIEWER_CONTENT_BOTTOM,
    )
}

private const val A4_RATIO = 1.414f
private const val TEXT_LINES_PER_ITEM = 200
private val TRANSPARENCY_CELL = 12.dp
private val TEXT_LIMIT_LABEL = formatFileSize(TEXT_PREVIEW_LIMIT_BYTES.toLong())
