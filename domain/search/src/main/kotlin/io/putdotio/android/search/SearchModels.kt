package io.putdotio.android.search

import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.sdk.files.PutioFileType
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

@JvmInline
public value class SearchTerm(
    public val value: String,
) {
    init {
        require(value.isNotBlank() && value == value.trim()) {
            "A search term must be trimmed and nonblank"
        }
    }

    public val shown: String
        get() {
            if (value.codePointCount(0, value.length) <= SHOWN_TERM_LENGTH) return value
            val end = value.offsetByCodePoints(0, SHOWN_TERM_LENGTH)
            return value.substring(0, end).trimEnd() + "…"
        }
}

private const val SHOWN_TERM_LENGTH = 50

public data class SearchScope(
    val folderId: FilesItemId,
    val folderName: String?,
) {
    init {
        require(folderId.value > 0L) { "A search scope must be a real folder, not root or a shared one" }
    }
}

internal fun SearchTerm.within(scope: SearchScope?): SearchTerm =
    if (scope == null) this else SearchTerm("$value in:${scope.folderId.value}")

public enum class SearchFilter {
    All,
    Folders,
    Videos,
    Audio,
    Images,
    Documents,
    Subtitles,
    ;

    internal val apiTypes: List<PutioFileType>
        get() =
            when (this) {
                Folders -> listOf(PutioFileType.FOLDER)
                Videos -> listOf(PutioFileType.VIDEO)
                Audio -> listOf(PutioFileType.AUDIO)
                Images -> listOf(PutioFileType.IMAGE)
                Documents -> DOCUMENT_TYPES
                All, Subtitles -> emptyList()
            }

    public fun accepts(item: FilesItem): Boolean =
        when (this) {
            All -> true
            Folders -> item.isFolder
            Videos -> item.type == PutioFileType.VIDEO
            Audio -> item.type == PutioFileType.AUDIO
            Images -> item.type == PutioFileType.IMAGE
            Documents -> item.type in DOCUMENT_TYPES
            Subtitles -> !item.isFolder && item.extension() in SUBTITLE_EXTENSIONS
        }
}

private val DOCUMENT_TYPES = listOf(PutioFileType.PDF, PutioFileType.TEXT)

private fun FilesItem.extension(): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "smi")

@JvmInline
public value class SearchRequestId(
    internal val value: Long,
)

public data class SearchPage(
    val items: List<FilesItem>,
    val nextCursor: FilesCursor?,
    val total: Int,
)

public sealed interface SearchPaging {
    public data object Complete : SearchPaging

    public data class Available(
        val cursor: FilesCursor,
    ) : SearchPaging

    public data class Loading(
        val cursor: FilesCursor,
        val requestId: SearchRequestId,
    ) : SearchPaging

    public data class Failed(
        val cursor: FilesCursor,
        val failure: PutioFailure,
    ) : SearchPaging
}

public sealed interface SearchContent {
    public data object Idle : SearchContent

    public data class Debouncing(
        val term: SearchTerm,
        val requestId: SearchRequestId,
    ) : SearchContent

    public data class Loading(
        val term: SearchTerm,
        val requestId: SearchRequestId,
    ) : SearchContent

    public data class Empty(
        val term: SearchTerm,
        val paging: SearchPaging,
        val total: SearchTotal? = null,
    ) : SearchContent

    public data class Ready(
        val term: SearchTerm,
        val items: List<FilesItem>,
        val paging: SearchPaging,
        val total: SearchTotal? = null,
    ) : SearchContent {
        init {
            require(items.isNotEmpty()) { "Ready search content must contain at least one item" }
        }
    }

    public data class Failed(
        val term: SearchTerm,
        val failure: PutioFailure,
    ) : SearchContent
}

@ConsistentCopyVisibility
public data class SearchState internal constructor(
    val query: String,
    val content: SearchContent,
    val recentTerms: List<SearchTerm>,
    internal val consumedCursors: Set<FilesCursor>,
    internal val nextRequestValue: Long,
    val recentSearchesEnabled: Boolean? = null,
    val scope: SearchScope? = null,
    val filter: SearchFilter = SearchFilter.All,
    internal val capped: Boolean = false,
)

public object SearchReducer {
    public fun start(
        recentTerms: List<SearchTerm> = emptyList(),
        recentSearchesEnabled: Boolean? = null,
    ): SearchState =
        SearchState(
            query = "",
            content = SearchContent.Idle,
            recentTerms = recentTerms,
            consumedCursors = emptySet(),
            nextRequestValue = INITIAL_REQUEST_VALUE,
            recentSearchesEnabled = recentSearchesEnabled,
        )
}

public sealed interface SearchOutput {
    public data class OpenResult(
        val item: FilesItem,
    ) : SearchOutput
}

public sealed interface RecentSearchEdit {
    public data class Remove(
        val term: SearchTerm,
    ) : RecentSearchEdit

    public data object Clear : RecentSearchEdit

    public data class SetEnabled(
        val enabled: Boolean,
    ) : RecentSearchEdit
}

public interface RecentSearchStore {
    public val terms: StateFlow<List<SearchTerm>>

    public val enabled: StateFlow<Boolean?>

    public fun record(term: SearchTerm)

    public fun remove(term: SearchTerm)

    public fun clear()

    public fun setEnabled(enabled: Boolean)
}

private const val INITIAL_REQUEST_VALUE = 1L

internal fun SearchContent.pagingOrNull(): SearchPaging? =
    when (this) {
        is SearchContent.Empty -> paging
        is SearchContent.Ready -> paging
        SearchContent.Idle, is SearchContent.Debouncing, is SearchContent.Loading, is SearchContent.Failed -> null
    }

internal fun SearchContent.Ready.withPosition(itemId: FilesItemId, seconds: Double): SearchContent.Ready? =
    if (items.none { it.id == itemId && it.isPlayable }) {
        null
    } else {
        copy(
            items = items.map { item ->
                if (item.id == itemId && item.isPlayable) {
                    item.copy(playback = FilesPlaybackProgress(seconds, item.playback?.durationSeconds))
                } else {
                    item
                }
            },
        )
    }

internal fun SearchContent.Ready.withItem(updated: FilesItem): SearchContent.Ready? =
    if (items.none { it.id == updated.id }) {
        null
    } else {
        copy(items = items.map { if (it.id == updated.id) updated else it })
    }

internal fun SearchContent.Ready.withoutItem(itemId: FilesItemId): SearchContent? =
    when {
        items.none { it.id == itemId } -> null
        items.size == 1 -> SearchContent.Empty(term, paging, total?.plus(-1))
        else -> copy(items = items.filterNot { it.id == itemId }, total = total?.plus(-1))
    }

internal fun SearchContent.withRestoredItem(item: FilesItem, index: Int, term: SearchTerm): SearchContent.Ready? =
    when (this) {
        is SearchContent.Ready -> if (this.term != term || items.any { it.id == item.id }) {
            null
        } else {
            val before = items.take(index.coerceIn(0, items.size))
            copy(items = before + item + items.drop(before.size), total = total?.plus(1))
        }
        is SearchContent.Empty ->
            if (this.term == term) SearchContent.Ready(term, listOf(item), paging, total?.plus(1)) else null
        else -> null
    }
