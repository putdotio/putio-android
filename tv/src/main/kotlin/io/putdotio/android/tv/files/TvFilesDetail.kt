package io.putdotio.android.tv.files

import io.putdotio.android.playback.R as PlaybackR
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.FilesSubtitleLanguages
import io.putdotio.android.files.FilesSubtitles
import io.putdotio.android.files.items
import io.putdotio.android.files.playbackFinishedAt
import io.putdotio.android.tv.TvDetailBody
import io.putdotio.android.tv.TvDetailDivider
import io.putdotio.android.tv.TvSheetOption
import io.putdotio.android.tv.TvDetailFact
import io.putdotio.android.tv.TvPlayStart
import io.putdotio.android.tv.TV_MONO_RUN
import io.putdotio.android.tv.asMonoRun
import io.putdotio.android.tv.focusRequesterIf
import io.putdotio.android.design.R as DesignR
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.delay

internal const val TV_FILES_DETAIL_PRIMARY_TAG = "tv-files-detail-primary"

internal class TvFilesDetailSources(
    private val folderCounts: FilesFolderCounts? = null,
    private val subtitleLanguages: FilesSubtitleLanguages? = null,
    private val onAuthenticationRequired: suspend () -> Unit = {},
    val thumbnails: TvItemThumbnails = TvItemThumbnails.None,
) {
    private val counts = mutableMapOf<Long, Int?>()
    private val subtitles = mutableMapOf<Long, FilesSubtitles>()
    private val countListings = mutableMapOf<Long, Any>()
    private val subtitleListings = mutableMapOf<Long, Any>()

    fun knownCount(id: FilesItemId): Int? = counts[id.value]

    fun knownSubtitles(id: FilesItemId): FilesSubtitles? = subtitles[id.value]

    fun countSettled(id: FilesItemId, listing: Any): Boolean = countListings[id.value] === listing

    fun subtitlesSettled(id: FilesItemId, listing: Any): Boolean = subtitleListings[id.value] === listing

    suspend fun count(id: FilesItemId, listing: Any): Int? =
        if (countSettled(id, listing)) {
            counts[id.value]
        } else {
            folderCounts?.takeIf { id.value >= 0L }?.itemCount(id)?.settle {
                counts[id.value] = it
                countListings[id.value] = listing
            }
        }

    suspend fun subtitles(id: FilesItemId, listing: Any): FilesSubtitles? =
        if (subtitlesSettled(id, listing)) {
            subtitles[id.value]
        } else {
            subtitleLanguages?.takeIf { id.value > 0L }?.subtitleLanguages(id)?.settle {
                subtitles[id.value] = it
                subtitleListings[id.value] = listing
            }
        }

    private suspend fun <T> PutioResult<T>.settle(keep: (T) -> Unit): T? =
        when (this) {
            is PutioResult.Success -> value.also(keep)
            is PutioResult.Failure -> {
                if (failure is PutioFailure.AuthenticationRequired) onAuthenticationRequired()
                null
            }
        }

    companion object {
        val None = TvFilesDetailSources()
    }
}

@Composable
internal fun rememberTvFilesListing(folder: FilesFolderState): Any {
    val folderId = folder.folder.id.value
    val reads = remember(folderId) { TvFilesListingReads() }
    val completedReads = reads.completedAfter(folder.fullReadRequest())
    return remember(folderId, folder.content.items(), completedReads) { Any() }
}

private class TvFilesListingReads {
    private var inFlight: FilesRequestId? = null
    private var completed = 0L

    fun completedAfter(read: FilesRequestId?): Long {
        if (read != inFlight) {
            if (inFlight != null) completed++
            inFlight = read
        }
        return completed
    }
}

private fun FilesFolderState.fullReadRequest(): FilesRequestId? {
    val content = content
    val operation = operation
    return when {
        content is FilesContent.Loading -> content.requestId
        operation is FilesFolderOperation.Loading && operation.phase == FilesFolderOperationPhase.RELOADING ->
            operation.requestId
        else -> null
    }
}

@Stable
internal class TvFilesDetailFocus {
    val primary = FocusRequester()
    val secondary = FocusRequester()
    var pressed by mutableStateOf<TvFilesDetailPress?>(null)
    var hasFocus by mutableStateOf(false)
        private set
    val owns = mutableStateOf(false)

    fun onDetailFocusChanged(focused: Boolean) {
        hasFocus = focused
        if (focused) owns.value = true
    }

    fun onListFocusChanged(focused: Boolean) {
        if (focused) owns.value = false
    }

    fun dialogReturn(
        item: FilesItem?,
        secondaryOf: (FilesItem) -> List<TvFilesAction>,
        entry: FocusRequester,
    ): FocusRequester {
        val held = item != null && pressed?.holds(item, secondaryOf(item).size) == true
        return if (owns.value && held) secondary else entry
    }
}

internal data class TvFilesDetailPress(val itemId: Long, val index: Int) {
    fun holds(item: FilesItem, secondaryCount: Int): Boolean = item.id.value == itemId && index < secondaryCount
}

internal fun List<TvFilesAction>.secondary(): List<TvFilesAction> =
    filterNot { it is TvFilesAction.Tile && it != TvFilesAction.StartOver }

internal class TvFilesDetailActions(
    val onOpenFolder: (FilesItem) -> Unit,
    val onPlay: (FilesItem, TvPlayStart) -> Unit,
    val onUnsupported: (FilesItem) -> Unit,
    val secondary: List<TvFilesAction> = emptyList(),
    val onSecondary: (FilesItem, TvFilesAction) -> Unit = { _, _ -> },
    val enabled: TvFilesDetailEnabled = TvFilesDetailEnabled(),
)

internal class TvFilesDetailEnabled(val delete: Boolean = true, val edits: Boolean = true)

@Composable
internal fun ColumnScope.TvFilesDetail(
    item: FilesItem,
    focus: TvFilesDetailFocus,
    resumePlayback: Boolean,
    sources: TvFilesDetailSources,
    listing: Any,
    actions: TvFilesDetailActions,
    place: TvFilesRowPlace = TvFilesRowPlace.Listing,
) {
    val count by produceState(sources.knownCount(item.id), item.id, sources, listing) {
        value = sources.knownCount(item.id)
        if (item.isFolder && !sources.countSettled(item.id, listing)) {
            delay(LOOKUP_DELAY_MILLIS)
            value = sources.count(item.id, listing)
        }
    }
    val subtitles by produceState(sources.knownSubtitles(item.id), item.id, sources, listing) {
        value = sources.knownSubtitles(item.id)
        if (item.type == PutioFileType.VIDEO && !sources.subtitlesSettled(item.id, listing)) {
            delay(LOOKUP_DELAY_MILLIS)
            value = sources.subtitles(item.id, listing)
        }
    }
    val current by rememberUpdatedState(item)
    val secondary = actions.secondary
    TvDetailBody(
        tile = { TvItemTile(item, sources.thumbnails) },
        key = item.id,
        title = item.name,
        meta = detailMeta(item, count),
        facts = detailFacts(subtitles, place),
        rows = { firstRow ->
            TvFilesPrimaryRow(
                item = item,
                resumeAt = item.resumePosition(resumePlayback),
                actions = actions,
                current = { current },
                modifier = firstRow
                    .focusRequester(focus.primary)
                    .testTag(TV_FILES_DETAIL_PRIMARY_TAG),
            )
            secondary.forEachIndexed { index, action ->
                val delete = action is TvFilesAction.Delete
                val edit = action == TvFilesAction.Rename || action == TvFilesAction.Move
                val pressed = focus.pressed == TvFilesDetailPress(item.id.value, index)
                if (delete) TvDetailDivider()
                TvSheetOption(
                    label = action.label(),
                    detail = null,
                    selected = false,
                    onClick = {
                        focus.pressed = TvFilesDetailPress(current.id.value, index)
                        actions.onSecondary(current, action)
                    },
                    leadingIcon = action.icon(),
                    role = Role.Button,
                    enabled = when {
                        delete -> actions.enabled.delete
                        edit -> actions.enabled.edits
                        else -> true
                    },
                    danger = delete,
                    modifier = Modifier.focusRequesterIf(pressed, focus.secondary),
                )
            }
        },
    )
}

@Composable
private fun detailMeta(item: FilesItem, count: Int?): AnnotatedString {
    val date = rememberMetaDate(item)
    val watched = stringResource(R.string.tv_files_watched)
    val itemCount = count?.let { pluralStringResource(R.plurals.tv_files_item_count, it, it.toString()) }
    val parts = buildList {
        item.playback?.durationSeconds?.let { add(tvClock(it).asMonoRun()) }
        itemCount?.let { add(AnnotatedString(it)) }
        item.metaSize?.let { add(it.asMonoRun()) }
        date?.let { add(AnnotatedString(it)) }
        if (item.showsWatched) add(AnnotatedString(watched))
    }
    return buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            if (index > 0) append(META_SEPARATOR)
            append(part)
        }
    }
}

@Composable
private fun detailFacts(subtitles: FilesSubtitles?, place: TvFilesRowPlace): List<TvDetailFact> {
    val folder = stringResource(R.string.tv_files_detail_folder)
    val subtitlesLabel = stringResource(R.string.tv_files_detail_subtitles)
    val unknownLanguage = stringResource(PlaybackR.string.playback_subtitle_unknown)
    val subtitlesValue = subtitles?.takeIf { it.languages.isNotEmpty() }?.let { found ->
        val languages = found.languages.joinToString(", ") { it ?: unknownLanguage }
        if (found.sidecarFiles > 0) {
            val files = pluralStringResource(
                R.plurals.tv_files_detail_subtitle_files,
                found.sidecarFiles,
                found.sidecarFiles.toString(),
            )
            "$languages$META_SEPARATOR$files"
        } else {
            languages
        }
    }
    return listOfNotNull(
        (place as? TvFilesRowPlace.SearchResult)?.folderName?.let { TvDetailFact(folder, it) },
        subtitlesValue?.let { TvDetailFact(subtitlesLabel, it) },
    )
}

@Composable
private fun TvFilesPrimaryRow(
    item: FilesItem,
    resumeAt: Double?,
    actions: TvFilesDetailActions,
    current: () -> FilesItem,
    modifier: Modifier = Modifier,
) {
    val action = tvPrimaryAction(
        item = item,
        resumeAt = resumeAt,
        onOpenFolder = { actions.onOpenFolder(current()) },
        onPlay = { actions.onPlay(current(), TvPlayStart.Resume) },
        onOpenFile = { actions.onUnsupported(current()) },
    )
    TvPrimaryRow(action, modifier)
}

@Composable
internal fun TvPrimaryRow(action: TvPrimaryAction, modifier: Modifier = Modifier) {
    TvSheetOption(
        label = action.label.text,
        styledLabel = action.label,
        detail = null,
        selected = false,
        onClick = action.onClick,
        leadingIcon = action.icon,
        role = Role.Button,
        modifier = modifier,
    )
}

@Composable
internal fun tvPrimaryAction(
    item: FilesItem,
    resumeAt: Double?,
    onOpenFolder: () -> Unit,
    onPlay: () -> Unit,
    onOpenFile: () -> Unit,
): TvPrimaryAction =
    when {
        item.isFolder -> TvPrimaryAction(
            AnnotatedString(stringResource(R.string.tv_files_detail_open)),
            R.drawable.ic_ph_folder,
            onOpenFolder,
        )
        resumeAt != null -> {
            val time = tvClock(resumeAt)
            TvPrimaryAction(
                resumeLabel(stringResource(R.string.tv_files_detail_resume, time), time),
                DesignR.drawable.ic_ph_play_fill,
                onPlay,
            )
        }
        item.isPlayable -> TvPrimaryAction(
            AnnotatedString(stringResource(R.string.tv_files_detail_play)),
            DesignR.drawable.ic_ph_play_fill,
            onPlay,
        )
        else -> TvPrimaryAction(
            AnnotatedString(stringResource(R.string.tv_files_detail_open)),
            R.drawable.ic_ph_arrow_square_out,
            onOpenFile,
        )
    }

internal class TvPrimaryAction(
    val label: AnnotatedString,
    @param:DrawableRes val icon: Int,
    val onClick: () -> Unit,
)

internal val FilesItem.showsWatched: Boolean
    get() = !isFolder && playback?.isWatched == true && resumePosition(resumePlayback = true) == null

internal fun FilesItem.resumePosition(resumePlayback: Boolean): Double? {
    val playback = playback?.takeIf { type == PutioFileType.VIDEO } ?: return null
    val position = playback.startFromSeconds
    val duration = playback.durationSeconds
    return position.takeIf { resumePlayback && it > 0.0 && !playbackFinishedAt(it, duration) }
}

internal fun tvClock(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val hours = total / SECONDS_PER_HOUR
    val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val secs = total % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

private fun resumeLabel(label: String, time: String): AnnotatedString =
    buildAnnotatedString {
        val start = label.lastIndexOf(time)
        append(label)
        if (start >= 0) {
            addStyle(RESUME_TIME_SPAN, start, start + time.length)
            if (start > 0 && label[start - 1] == ' ') addStyle(RESUME_GAP_SPAN, start - 1, start)
        }
    }

private val RESUME_TIME_SPAN = TV_MONO_RUN.merge(SpanStyle(fontWeight = PutioTypeScale.tvMono.fontWeight))
private val RESUME_GAP_SPAN = SpanStyle(letterSpacing = 2.sp)
private const val META_SEPARATOR = " · "
private const val LOOKUP_DELAY_MILLIS = 300L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
