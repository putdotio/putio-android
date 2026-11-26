package io.putdotio.android.files

import io.putdotio.android.playback.R as PlaybackR
import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.monoValue
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.design.PutioEmptyState
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.putioMono
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.MobileIconButton

internal const val MOBILE_FILES_DETAIL_TAG = "mobile-files-detail"
internal const val MOBILE_FILES_DETAIL_PRIMARY_TAG = "mobile-files-detail-primary"
internal const val MOBILE_FILES_DETAIL_DOWNLOAD_TAG = "mobile-files-detail-download"
internal const val MOBILE_FILES_DETAIL_SHARE_TAG = "mobile-files-detail-share"
internal const val MOBILE_FILES_DETAIL_MORE_TAG = "mobile-files-detail-more"

internal class MobileFilesDetailActions(
    val onOpen: (FilesItem) -> Unit,
    val onDownload: ((FilesItem) -> Unit)? = null,
    val onShare: ((FilesItem) -> Unit)? = null,
    val onMore: ((FilesItem) -> Unit)? = null,
    val imageUri: ((Context, FilesItem) -> Uri)? = null,
    val resumePlayback: Boolean = true,
)

@Composable
internal fun MobileFilesDetailPane(
    item: FilesItem?,
    folderPath: String?,
    downloadStatus: DownloadStatus?,
    subtitleLanguages: FilesSubtitleLanguages?,
    actions: MobileFilesDetailActions,
    modifier: Modifier = Modifier,
    onAuthenticationRequired: suspend () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(MOBILE_FILES_DETAIL_TAG),
    ) {
        if (item == null) {
            PutioEmptyState(
                glyph = null,
                headline = stringResource(R.string.mobile_files_detail_empty_title),
                message = stringResource(R.string.mobile_files_detail_empty_message),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = DETAIL_HORIZONTAL_PADDING),
            )
            return@Box
        }
        val scroll = key(item.id) { rememberScrollState() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = DETAIL_HORIZONTAL_PADDING, vertical = DETAIL_VERTICAL_PADDING),
            verticalArrangement = Arrangement.spacedBy(DETAIL_GAP),
        ) {
            MobileFilesDetailPreview(item, actions)
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            MobileFilesDetailButtons(item, downloadStatus, actions)
            MobileFilesDetailFacts(item, folderPath, subtitleLanguages, onAuthenticationRequired)
        }
    }
}

@Composable
private fun MobileFilesDetailPreview(item: FilesItem, actions: MobileFilesDetailActions) {
    val context = LocalContext.current
    val model: Any? = when {
        item.type == PutioFileType.VIDEO -> item.screenshotUrl
        item.type == PutioFileType.IMAGE -> actions.imageUri?.invoke(context, item)
        else -> null
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(PREVIEW_ASPECT_RATIO)
            .clip(RoundedCornerShape(PutioRadius.extraLarge))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = { actions.onOpen(item) })
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val glyph = @Composable { FileTypeIcon(item.type, Modifier.size(PREVIEW_GLYPH_SIZE)) }
        if (model == null) {
            glyph()
        } else {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Box(Modifier.fillMaxSize()) },
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { glyph() } },
                success = { SubcomposeAsyncImageContent() },
            )
        }
        if (item.isPlayable) {
            Box(
                modifier = Modifier
                    .size(PREVIEW_PLAY_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = PREVIEW_PLAY_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(DesignR.drawable.ic_ph_play_fill),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(PREVIEW_PLAY_GLYPH_SIZE),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MobileFilesDetailButtons(
    item: FilesItem,
    downloadStatus: DownloadStatus?,
    actions: MobileFilesDetailActions,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { actions.onOpen(item) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = DETAIL_BUTTON_PADDING,
            modifier = Modifier.testTag(MOBILE_FILES_DETAIL_PRIMARY_TAG),
        ) {
            val (icon, label) = primaryAction(item, actions.resumePlayback)
            MobileFilesDetailButtonContent(icon, label)
        }
        val download = actions.onDownload?.takeIf { item.isPlayable }
        if (download != null) {
            val (icon, label, enabled) = downloadAction(downloadStatus)
            MobileFilesDetailTonalButton(
                icon = icon,
                label = label,
                enabled = enabled,
                tag = MOBILE_FILES_DETAIL_DOWNLOAD_TAG,
                onClick = { download(item) },
            )
        }
        actions.onShare?.let { share ->
            MobileFilesDetailTonalButton(
                icon = R.drawable.ic_ph_share_network,
                label = stringResource(R.string.mobile_files_share_action),
                tag = MOBILE_FILES_DETAIL_SHARE_TAG,
                onClick = { share(item) },
            )
        }
        actions.onMore?.let { more ->
            MobileIconButton(
                stringResource(R.string.mobile_files_actions, item.name),
                R.drawable.ic_ph_dots_three_vertical,
                onClick = { more(item) },
                modifier = Modifier.testTag(MOBILE_FILES_DETAIL_MORE_TAG),
            )
        }
    }
}

@Composable
private fun primaryAction(item: FilesItem, resumePlayback: Boolean): Pair<Int, AnnotatedString> {
    val resumeAt = item.resumeAt(resumePlayback)
    return when {
        resumeAt != null ->
            DesignR.drawable.ic_ph_play_fill to resumeLabel(resumeAt, MaterialTheme.typography.labelLarge)
        item.isPlayable ->
            DesignR.drawable.ic_ph_play_fill to AnnotatedString(stringResource(R.string.mobile_files_play))
        else -> R.drawable.ic_ph_arrow_square_out to AnnotatedString(stringResource(R.string.mobile_files_detail_open))
    }
}

private data class DetailDownload(@param:DrawableRes val icon: Int, val label: String, val enabled: Boolean)

@Composable
private fun downloadAction(status: DownloadStatus?): DetailDownload =
    when (status) {
        null, is DownloadStatus.Failed, DownloadStatus.Missing ->
            DetailDownload(DOWNLOAD_ICON, stringResource(R.string.mobile_files_download_action), enabled = true)
        is DownloadStatus.Completed ->
            DetailDownload(DOWNLOADED_ICON, stringResource(R.string.mobile_files_downloaded), enabled = false)
        else -> DetailDownload(DOWNLOAD_ICON, stringResource(R.string.mobile_files_downloading), enabled = false)
    }

private val DOWNLOAD_ICON = R.drawable.ic_ph_arrow_circle_down
private val DOWNLOADED_ICON = R.drawable.ic_ph_arrow_circle_down_fill

@Composable
private fun MobileFilesDetailTonalButton(
    @DrawableRes icon: Int,
    label: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = DETAIL_BUTTON_PADDING,
        modifier = Modifier.testTag(tag),
    ) {
        MobileFilesDetailButtonContent(icon, AnnotatedString(label))
    }
}

@Composable
private fun MobileFilesDetailButtonContent(@DrawableRes icon: Int, label: AnnotatedString) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(DETAIL_BUTTON_ICON_SIZE),
    )
    Text(label, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
}

@Composable
private fun MobileFilesDetailFacts(
    item: FilesItem,
    folderPath: String?,
    subtitleLanguages: FilesSubtitleLanguages?,
    onAuthenticationRequired: suspend () -> Unit,
) {
    val context = LocalContext.current
    val style = PutioTypeScale.bodySm
    val currentOnAuthenticationRequired by rememberUpdatedState(onAuthenticationRequired)
    val unknownLanguage = stringResource(PlaybackR.string.playback_subtitle_unknown)
    val subtitles by produceState<List<String?>?>(null, item.id, subtitleLanguages) {
        value = null
        val reader = subtitleLanguages?.takeIf { item.type == PutioFileType.VIDEO && item.id.value > 0L }
        when (val result = reader?.subtitleLanguages(item.id)) {
            is PutioResult.Success -> value = result.value.languages
            is PutioResult.Failure ->
                if (result.failure is PutioFailure.AuthenticationRequired) currentOnAuthenticationRequired()
            null -> Unit
        }
    }
    val facts = buildList {
        add(DetailFact(R.string.mobile_files_detail_size, formatFileSize(item.sizeBytes), mono = true))
        item.createdAt.toFullDate(context)?.let { add(DetailFact(R.string.mobile_files_detail_added, it)) }
        item.playback?.durationSeconds?.let { seconds ->
            val length = DateUtils.formatElapsedTime(seconds.toLong())
            add(DetailFact(R.string.mobile_files_detail_length, length, mono = true))
        }
        subtitles?.takeIf { it.isNotEmpty() }?.let {
            val names = it.joinToString(", ") { name -> name ?: unknownLanguage }
            add(DetailFact(R.string.mobile_files_detail_subtitles, names))
        }
        folderPath?.let { add(DetailFact(R.string.mobile_files_detail_folder, it)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(FACT_ROW_GAP)) {
        facts.forEach { fact -> MobileFilesDetailFact(fact, style) }
    }
}

private data class DetailFact(val label: Int, val value: String, val mono: Boolean = false)

@Composable
private fun MobileFilesDetailFact(fact: DetailFact, style: TextStyle) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(FACT_COLUMN_GAP),
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = stringResource(fact.label),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(FACT_LABEL_WIDTH),
        )
        val mono = style.putioMono().toSpanStyle()
        Text(
            text = if (fact.mono) monoValue(fact.value, mono) else AnnotatedString(fact.value),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun FilesBrowserState.folderPath(): String? {
    var start = stack.lastIndex
    while (start > 0 && stack[start - 1].content.items().any { it.id == stack[start].folder.id }) start--
    val fromRoot = start == 0 && stack[0].folder.id == FilesFolder.Root.id
    val chain = if (fromRoot) stack.drop(1) else listOf(stack.last())
    return chain.mapNotNull { it.folder.name?.takeIf(String::isNotBlank) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" / ")
}

private const val PREVIEW_ASPECT_RATIO = 2f
private const val PREVIEW_PLAY_ALPHA = 0.45f
private val PREVIEW_PLAY_SIZE = 72.dp
private val PREVIEW_PLAY_GLYPH_SIZE = 34.dp
private val PREVIEW_GLYPH_SIZE = 48.dp
private val DETAIL_HORIZONTAL_PADDING = 32.dp
private val DETAIL_VERTICAL_PADDING = 24.dp
private val DETAIL_GAP = 16.dp
private val FACT_LABEL_WIDTH = 120.dp
private val FACT_ROW_GAP = 10.dp
private val FACT_COLUMN_GAP = 16.dp

private val DETAIL_BUTTON_PADDING = PaddingValues(horizontal = 24.dp)
private val DETAIL_BUTTON_ICON_SIZE = 14.dp
