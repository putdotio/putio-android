package io.putdotio.android.tv.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesStreamUrlResult
import io.putdotio.android.tv.TvDetailTile
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.delay

@Stable
internal class TvItemThumbnails(private val imageUrl: TvViewerUrl? = null) {
    suspend fun source(item: FilesItem): TvThumbnailSource? =
        when {
            !item.hasThumbnail -> null
            item.screenshotSource != null -> item.screenshotSource
            item.type == PutioFileType.IMAGE -> (imageUrl?.invoke(item.id) as? FilesStreamUrlResult.Ready)
                ?.let { TvThumbnailSource(it.url, tokened = true) }
            else -> null
        }

    companion object {
        val None = TvItemThumbnails()
    }
}

internal data class TvThumbnailSource(val url: String, val tokened: Boolean)

private val FilesItem.screenshotSource: TvThumbnailSource?
    get() = screenshotUrl?.let { TvThumbnailSource(it, tokened = false) }

private val FilesItem.hasThumbnail: Boolean
    get() = type == PutioFileType.VIDEO || type == PutioFileType.IMAGE

@Composable
internal fun TvItemTile(item: FilesItem, thumbnails: TvItemThumbnails, modifier: Modifier = Modifier) {
    TvDetailTile(glyph = fileTypeIconRes(item.type), modifier = modifier) {
        if (item.hasThumbnail) key(item.id.value, item.updatedAt) { TvSettledThumbnail(item, thumbnails) }
    }
}

@Composable
private fun BoxScope.TvSettledThumbnail(item: FilesItem, thumbnails: TvItemThumbnails) {
    val context = LocalContext.current
    val cacheKey = remember { "tv-tile-${item.id.value}-${item.updatedAt.orEmpty()}" }
    val loader = remember(context) { SingletonImageLoader.get(context) }
    val cached = remember(loader) { loader.memoryCache?.get(MemoryCache.Key(cacheKey)) != null }
    val source by produceState(item.screenshotSource.takeIf { cached }, thumbnails) {
        if (!cached) delay(THUMBNAIL_SETTLE_MILLIS)
        value = thumbnails.source(item)
    }
    val loaded = source ?: return
    val widthPx = with(LocalDensity.current) { TILE_DECODE_WIDTH.roundToPx() }
    val request = remember(loaded, widthPx) {
        ImageRequest.Builder(context)
            .data(loaded.url)
            .size(widthPx, widthPx * TILE_RATIO_HEIGHT / TILE_RATIO_WIDTH)
            .precision(Precision.INEXACT)
            .memoryCacheKey(cacheKey)
            .diskCachePolicy(if (loaded.tokened) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .build()
    }
    var drawn by remember(request) { mutableStateOf(false) }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onState = { drawn = it is AsyncImagePainter.State.Success },
        modifier = Modifier
            .fillMaxSize()
            .then(if (drawn) Modifier.background(PutioDesignTokens.componentBgActive) else Modifier),
    )
}

private const val THUMBNAIL_SETTLE_MILLIS = 300L
private const val TILE_RATIO_WIDTH = 16
private const val TILE_RATIO_HEIGHT = 9
private val TILE_DECODE_WIDTH = 80.dp
