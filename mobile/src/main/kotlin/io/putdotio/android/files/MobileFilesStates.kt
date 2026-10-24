package io.putdotio.android.files

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.R
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioEmptyState
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.putioMono
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.rememberMobileMotion
import io.putdotio.sdk.files.PutioFileType
import java.time.Instant

internal const val MOBILE_FILES_CONTINUE_WATCHING_TAG = "mobile-files-continue-watching"
internal const val MOBILE_FILES_SUMMARY_TAG = "mobile-files-summary"
internal const val MOBILE_FILES_SKELETON_TAG = "mobile-files-skeleton"
internal const val MOBILE_FILES_OFFLINE_TAG = "mobile-files-offline"
internal const val MOBILE_FILES_NEW_FOLDER_TAG = "mobile-files-new-folder"

/** What sits above a listing's rows: the root's Continue watching row, or a folder's count line. */
internal data class MobileFilesHeaders(
    val continueWatching: List<ContinueWatchingEntry> = emptyList(),
    val summary: FilesFolderSummary? = null,
) {
    /** List items above the first row; the viewport counts rows only. */
    val itemCount: Int
        get() = when {
            continueWatching.isNotEmpty() -> CONTINUE_WATCHING_ITEMS
            summary != null -> 1
            else -> 0
        }
}

internal fun LazyListScope.mobileFilesHeaderItems(
    headers: MobileFilesHeaders,
    onPlay: (ContinueWatchingEntry) -> Unit,
) {
    if (headers.continueWatching.isNotEmpty()) {
        item(key = "continue-watching-label") {
            MobileFilesSectionLabel(stringResource(R.string.mobile_files_continue_watching))
        }
        item(key = "continue-watching") { MobileContinueWatchingRow(headers.continueWatching, onPlay) }
        item(key = "all-files-label") {
            MobileFilesSectionLabel(stringResource(R.string.mobile_files_all_files), top = 12.dp)
        }
    } else {
        headers.summary?.let { summary -> item(key = "folder-summary") { MobileFolderSummaryLine(summary) } }
    }
}

@Composable
private fun MobileFilesSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    top: Dp = 20.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = top, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** "6 items" with the count in mono, and the folder's total size in mono at the end. */
@Composable
private fun MobileFolderSummaryLine(summary: FilesFolderSummary) {
    val style = MaterialTheme.typography.titleSmall
    val mono = style.putioMono()
    val count = summary.itemCount
    val template = pluralStringResource(R.plurals.mobile_files_item_count, count, "\u0000")
    val label = buildAnnotatedString {
        val parts = template.split("\u0000")
        append(parts.first())
        withStyle(mono.toSpanStyle()) { append(count.toString()) }
        parts.drop(1).forEach(::append)
    }
    val size = summary.sizeBytes?.let(::formatFileSize)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(MOBILE_FILES_SUMMARY_TAG),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        size?.let { Text(it, style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun MobileContinueWatchingRow(
    entries: List<ContinueWatchingEntry>,
    onPlay: (ContinueWatchingEntry) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .testTag(MOBILE_FILES_CONTINUE_WATCHING_TAG),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.fileId.value }) { entry -> MobileContinueWatchingCard(entry, onPlay) }
    }
}

/** A started video: its name, a yellow progress line and the time left in mono. */
@Composable
private fun MobileContinueWatchingCard(
    entry: ContinueWatchingEntry,
    onPlay: (ContinueWatchingEntry) -> Unit,
) {
    val left = formatRemaining(entry.remainingSeconds)
    val description = stringResource(R.string.mobile_files_continue_watching_resume, entry.name, left)
    Surface(
        shape = RoundedCornerShape(PutioRadius.large),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .width(CONTINUE_WATCHING_CARD_WIDTH)
            .clip(RoundedCornerShape(PutioRadius.large))
            .clickable(role = Role.Button, onClick = { onPlay(entry) })
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FileTypeIcon(type = PutioFileType.VIDEO, modifier = Modifier.size(20.dp))
                MobileMiddleCutText(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            LinearProgressIndicator(
                progress = { entry.fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Text(
                text = remainingLabel(left),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = null),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun remainingLabel(left: String): AnnotatedString {
    val mono = MaterialTheme.typography.labelSmall.putioMono().toSpanStyle()
    val template = stringResource(R.string.mobile_files_continue_watching_left, "\u0000")
    return buildAnnotatedString {
        val parts = template.split("\u0000")
        append(parts.first())
        withStyle(mono) { append(left) }
        parts.drop(1).forEach(::append)
    }
}

/** "4:12" under an hour, "1:13:40" from one. */
internal fun formatRemaining(seconds: Long): String {
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/** Skeleton rows in the row's slots, pulsing on surface-container-high; held still when motion is off. */
@Composable
internal fun MobileFilesSkeleton(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.mobile_files_loading)
    val still = rememberMobileMotion().reducedMotion
    val transition = rememberInfiniteTransition(label = "skeleton")
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(MOBILE_FILES_SKELETON_TAG)
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        SKELETON_ROWS.forEachIndexed { index, (headline, meta) ->
            val alpha = if (still) {
                1f
            } else {
                transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = SKELETON_PULSE_MILLIS
                            1f at 0 using SkeletonEasing
                            SKELETON_DIM at SKELETON_PULSE_MILLIS / 2 using SkeletonEasing
                        },
                        repeatMode = RepeatMode.Restart,
                        initialStartOffset = StartOffset(skeletonLead(index), StartOffsetType.FastForward),
                    ),
                    label = "skeleton-$index",
                ).value
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SKELETON_ROW_HEIGHT)
                    .padding(horizontal = 16.dp)
                    .alpha(alpha),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(26.dp),
                    radius = 6.dp,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(headline).height(12.dp),
                    )
                    SkeletonBlock(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(meta).height(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(color: Color, modifier: Modifier, radius: Dp = 6.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(color))
}

/**
 * put.io didn't answer: what's on this device stays usable under a banner, then the offline state.
 * It tries again by itself once the system reports a working network.
 */
@Composable
internal fun MobileFilesOffline(
    onDevice: List<FilesItem>,
    onRetry: () -> Unit,
    onPlayMedia: (FilesItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    RetryWhenOnline(onRetry)
    LazyColumn(modifier = modifier.fillMaxSize().testTag(MOBILE_FILES_OFFLINE_TAG)) {
        if (onDevice.isNotEmpty()) {
            item(key = "offline-banner") { MobileOfflineBanner() }
            items(onDevice, key = { it.id.value }) { item ->
                MobileFilesRow(
                    item = item,
                    downloadStatus = DownloadStatus.Completed(item.sizeBytes),
                    onClick = if (item.isPlayable) ({ onPlayMedia(item) }) else null,
                    onClickLabel = stringResource(R.string.mobile_files_play_media, item.name)
                        .takeIf { item.isPlayable },
                )
            }
        }
        item(key = "offline-state") {
            MobileFilesEmptyState(
                glyph = PutioEmptyGlyph.Offline,
                headline = stringResource(R.string.mobile_files_offline_title),
                message = stringResource(R.string.mobile_files_offline_message),
                modifier = if (onDevice.isEmpty()) Modifier.fillParentMaxSize() else Modifier.padding(top = 40.dp),
                bottomPadding = if (onDevice.isEmpty()) EMPTY_BOTTOM_PADDING else 0.dp,
                action = {
                    // DESIGN.md's button-tonal: neutral, so the screen keeps no yellow action.
                    FilledTonalButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) { Text(stringResource(R.string.mobile_files_offline_retry)) }
                },
            )
        }
    }
}

@Composable
private fun MobileOfflineBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(PutioRadius.large))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ph_wifi_slash),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(R.string.mobile_files_offline_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Calls [onRetry] once the default network is validated again, while this is on screen. */
@Composable
private fun RetryWhenOnline(onRetry: () -> Unit) {
    val context = LocalContext.current
    val retry by rememberUpdatedState(onRetry)
    DisposableEffect(context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    ContextCompat.getMainExecutor(context).execute { retry() }
                }
            }
        }
        val registered = connectivity?.runCatching { registerDefaultNetworkCallback(callback) }?.isSuccess == true
        onDispose { if (registered) connectivity?.unregisterNetworkCallback(callback) }
    }
}

/** The put.io empty-state voice placed for Files: centred a little above the middle, as the board sets it. */
@Composable
internal fun MobileFilesEmptyState(
    glyph: PutioEmptyGlyph,
    headline: String,
    message: String,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = EMPTY_BOTTOM_PADDING,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, bottom = bottomPadding),
        contentAlignment = Alignment.Center,
    ) {
        PutioEmptyState(glyph = glyph, headline = headline, message = message, action = action)
    }
}

@Composable
internal fun MobileNewFolderButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.testTag(MOBILE_FILES_NEW_FOLDER_TAG)) {
        Icon(
            painter = painterResource(R.drawable.ic_ph_folder_plus),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.mobile_files_new_folder))
    }
}

/** Finished downloads as Files rows, newest first, for the offline state. */
internal fun DownloadsState.onDeviceItems(): List<FilesItem> =
    onDevice.sortedByDescending { it.createdAt }.map { entry ->
        FilesItem(
            id = entry.fileId,
            parentId = null,
            name = entry.name,
            type = entry.type,
            sizeBytes = (entry.status as? DownloadStatus.Completed)?.bytes ?: 0L,
            createdAt = Instant.ofEpochMilli(entry.createdAt).toString(),
            playback = FilesPlaybackProgress(entry.startFromSeconds, entry.durationSeconds)
                .takeIf { entry.type == PutioFileType.VIDEO || entry.type == PutioFileType.AUDIO },
        )
    }

private const val CONTINUE_WATCHING_ITEMS = 3
private val CONTINUE_WATCHING_CARD_WIDTH = 172.dp
private val SKELETON_ROW_HEIGHT = 72.dp
private val EMPTY_BOTTOM_PADDING = 80.dp
private const val SKELETON_PULSE_MILLIS = 1_400
private const val SKELETON_DIM = 0.45f

/** CSS ease-out, the board's pulse curve. */
@Suppress("MagicNumber") // The control points are the CSS curve's definition.
private val SkeletonEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** Rows pulse out of step: every third row runs 450ms ahead, every other even row 200ms. */
private fun skeletonLead(index: Int): Int {
    val row = index + 1
    return when {
        row % SKELETON_THIRD == 0 -> SKELETON_THIRD_LEAD_MILLIS
        row % 2 == 0 -> SKELETON_EVEN_LEAD_MILLIS
        else -> 0
    }
}

private const val SKELETON_THIRD = 3
private const val SKELETON_THIRD_LEAD_MILLIS = 450
private const val SKELETON_EVEN_LEAD_MILLIS = 200
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** Headline and meta widths of the eight skeleton rows, as fractions of the text column. */
@Suppress("MagicNumber") // The board's widths, row by row.
private val SKELETON_ROWS = listOf(
    0.52f to 0.30f,
    0.70f to 0.36f,
    0.44f to 0.28f,
    0.82f to 0.40f,
    0.60f to 0.34f,
    0.74f to 0.30f,
    0.38f to 0.26f,
    0.66f to 0.38f,
)
