package io.putdotio.android.tv.files

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesSubtitleLanguages
import io.putdotio.android.files.formatFileSize
import io.putdotio.android.files.toDisplayDate
import io.putdotio.android.files.toFullDate
import io.putdotio.android.tv.TvDetailBody
import io.putdotio.android.tv.TvDetailButton
import io.putdotio.android.tv.TvDetailFact
import io.putdotio.android.tv.TvPlayStart
import io.putdotio.android.design.R as DesignR
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.delay

internal const val TV_FILES_DETAIL_PRIMARY_TAG = "tv-files-detail-primary"

internal class TvFilesDetailSources(
    private val folderCounts: FilesFolderCounts? = null,
    private val subtitleLanguages: FilesSubtitleLanguages? = null,
    private val onAuthenticationRequired: suspend () -> Unit = {},
) {
    private val counts = mutableMapOf<Long, Int?>()
    private val subtitles = mutableMapOf<Long, List<String>>()
    private val countListings = mutableMapOf<Long, Any>()
    private val subtitleListings = mutableMapOf<Long, Any>()

    fun knownCount(id: FilesItemId): Int? = counts[id.value]

    fun knownSubtitles(id: FilesItemId): List<String>? = subtitles[id.value]

    fun countSettled(id: FilesItemId, listing: Any): Boolean = countListings[id.value] === listing

    fun subtitlesSettled(id: FilesItemId, listing: Any): Boolean = subtitleListings[id.value] === listing

    suspend fun count(id: FilesItemId, listing: Any): Int? =
        if (countSettled(id, listing)) {
            counts[id.value]
        } else {
            folderCounts?.itemCount(id)?.settle {
                counts[id.value] = it
                countListings[id.value] = listing
            }
        }

    suspend fun subtitles(id: FilesItemId, listing: Any): List<String>? =
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

@Stable
internal class TvFilesDetailFocus {
    val primary = FocusRequester()
    val more = FocusRequester()
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

    fun dialogReturn(moreShown: Boolean, entry: FocusRequester): FocusRequester =
        if (owns.value && moreShown) more else entry
}

internal fun FilesItem?.offersActions(actionsOf: (FilesItem) -> List<TvFilesAction>): Boolean =
    this != null && actionsOf(this).isNotEmpty()

internal class TvFilesDetailActions(
    val onOpenFolder: (FilesItem) -> Unit,
    val onPlay: (FilesItem, TvPlayStart) -> Unit,
    val onUnsupported: (FilesItem) -> Unit,
    val onMore: ((FilesItem) -> Unit)?,
)

@Composable
internal fun ColumnScope.TvFilesDetail(
    item: FilesItem,
    detailHasFocus: Boolean,
    primaryFocus: FocusRequester,
    resumePlayback: Boolean,
    sources: TvFilesDetailSources,
    listing: Any,
    actions: TvFilesDetailActions,
    moreFocus: FocusRequester,
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
    TvDetailBody(title = item.name, meta = detailMeta(item, count), facts = detailFacts(item, subtitles)) {
        TvFilesPrimaryActions(
            item = item,
            resumeAt = item.resumePosition(resumePlayback),
            detailHasFocus = detailHasFocus,
            actions = actions,
            current = { current },
            modifier = Modifier
                .focusRequester(primaryFocus)
                .testTag(TV_FILES_DETAIL_PRIMARY_TAG),
        )
        actions.onMore?.let { more ->
            val description = stringResource(R.string.tv_files_detail_more)
            TvDetailButton(
                label = null,
                icon = R.drawable.ic_ph_dots_three,
                onClick = { more(current) },
                modifier = Modifier
                    .focusRequester(moreFocus)
                    .semantics { contentDescription = description },
            )
        }
    }
}

@Composable
private fun detailMeta(item: FilesItem, count: Int?): AnnotatedString {
    val context = LocalContext.current
    val date = remember(item.createdAt, context) { item.createdAt.toDisplayDate(context) }
    val watched = stringResource(R.string.tv_files_watched)
    val itemCount = count?.let { pluralStringResource(R.plurals.tv_files_item_count, it, it.toString()) }
    val parts = buildList {
        itemCount?.let { add(AnnotatedString(it)) }
        add(mono(formatFileSize(item.sizeBytes)))
        if (!item.isFolder) {
            date?.let { add(AnnotatedString(it)) }
            if (item.playback?.isWatched == true) add(AnnotatedString(watched))
        }
    }
    return buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            if (index > 0) append(META_SEPARATOR)
            append(part)
        }
    }
}

@Composable
private fun detailFacts(item: FilesItem, subtitles: List<String>?): List<TvDetailFact> {
    val context = LocalContext.current
    val fullDate = remember(item.createdAt, context) { item.createdAt.toFullDate(context) }
    val length = stringResource(R.string.tv_files_detail_length)
    val added = stringResource(R.string.tv_files_detail_added)
    val subtitlesLabel = stringResource(R.string.tv_files_detail_subtitles)
    return listOfNotNull(
        item.playback?.durationSeconds?.let { TvDetailFact(length, tvClock(it), mono = true) },
        fullDate?.let { TvDetailFact(added, it) },
        subtitles?.takeIf { it.isNotEmpty() }?.let { TvDetailFact(subtitlesLabel, it.joinToString(", ")) },
    )
}

@Composable
private fun TvFilesPrimaryActions(
    item: FilesItem,
    resumeAt: Double?,
    detailHasFocus: Boolean,
    actions: TvFilesDetailActions,
    current: () -> FilesItem,
    modifier: Modifier = Modifier,
) {
    val action = when {
        item.isFolder -> PrimaryAction(
            AnnotatedString(stringResource(R.string.tv_files_detail_open)),
            R.drawable.ic_ph_folder,
            { actions.onOpenFolder(current()) },
        )
        item.isPlayable && resumeAt != null -> {
            val time = tvClock(resumeAt)
            PrimaryAction(
                resumeLabel(stringResource(R.string.tv_files_detail_resume, time), time),
                DesignR.drawable.ic_ph_play_fill,
                { actions.onPlay(current(), TvPlayStart.Resume) },
            )
        }
        item.isPlayable -> PrimaryAction(
            AnnotatedString(stringResource(R.string.tv_files_detail_play)),
            DesignR.drawable.ic_ph_play_fill,
            { actions.onPlay(current(), TvPlayStart.Ask) },
        )
        else -> PrimaryAction(
            AnnotatedString(stringResource(R.string.tv_files_detail_open)),
            R.drawable.ic_ph_arrow_square_out,
            { actions.onUnsupported(current()) },
        )
    }
    TvDetailButton(
        label = action.label,
        icon = action.icon,
        onClick = action.onClick,
        modifier = modifier,
        primary = true,
        detailHasFocus = detailHasFocus,
    )
    if (item.isPlayable && resumeAt != null) {
        TvDetailButton(
            label = AnnotatedString(stringResource(R.string.tv_files_detail_start_over)),
            icon = R.drawable.ic_ph_arrow_counter_clockwise,
            onClick = { actions.onPlay(current(), TvPlayStart.StartOver) },
        )
    }
}

private class PrimaryAction(val label: AnnotatedString, @param:DrawableRes val icon: Int, val onClick: () -> Unit)

internal fun FilesItem.resumePosition(resumePlayback: Boolean): Double? {
    val playback = playback ?: return null
    val position = playback.startFromSeconds
    val duration = playback.durationSeconds
    val unfinished = duration == null || position < duration - FINISHED_WITHIN_SECONDS
    return position.takeIf { resumePlayback && it > 0.0 && unfinished }
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

private fun mono(text: String): AnnotatedString =
    buildAnnotatedString { withStyle(MONO_SPAN) { append(text) } }

private fun resumeLabel(label: String, time: String): AnnotatedString =
    buildAnnotatedString {
        val start = label.lastIndexOf(time)
        append(label)
        if (start >= 0) addStyle(MONO_SPAN, start, start + time.length)
    }

private val MONO_SPAN = SpanStyle(
    fontFamily = PutioTypeScale.tvMono.fontFamily,
    fontFeatureSettings = PutioTypeScale.tvMono.fontFeatureSettings,
)
private const val META_SEPARATOR = " · "
private const val LOOKUP_DELAY_MILLIS = 300L
private const val FINISHED_WITHIN_SECONDS = 10.0
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
