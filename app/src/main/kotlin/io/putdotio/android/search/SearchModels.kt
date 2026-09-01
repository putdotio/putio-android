package io.putdotio.android.search

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import kotlinx.coroutines.flow.StateFlow

@JvmInline
value class SearchTerm(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value == value.trim()) {
            "A search term must be trimmed and nonblank"
        }
    }
}

@JvmInline
value class SearchRequestId(
    val value: Long,
)

data class SearchPage(
    val items: List<FilesItem>,
    val nextCursor: FilesCursor?,
    val total: Int,
)

sealed interface SearchPaging {
    data object Complete : SearchPaging

    data class Available(
        val cursor: FilesCursor,
    ) : SearchPaging

    data class Loading(
        val cursor: FilesCursor,
        val requestId: SearchRequestId,
    ) : SearchPaging

    data class Failed(
        val cursor: FilesCursor,
        val failure: FilesFailure,
    ) : SearchPaging
}

sealed interface SearchContent {
    data object Idle : SearchContent

    data class Debouncing(
        val term: SearchTerm,
        val requestId: SearchRequestId,
    ) : SearchContent

    data class Loading(
        val term: SearchTerm,
        val requestId: SearchRequestId,
    ) : SearchContent

    data class Empty(
        val term: SearchTerm,
        val paging: SearchPaging,
    ) : SearchContent

    data class Ready(
        val term: SearchTerm,
        val items: List<FilesItem>,
        val paging: SearchPaging,
    ) : SearchContent {
        init {
            require(items.isNotEmpty()) { "Ready search content must contain at least one item" }
        }
    }

    data class Failed(
        val term: SearchTerm,
        val failure: FilesFailure,
    ) : SearchContent
}

@ConsistentCopyVisibility
data class SearchState internal constructor(
    val query: String,
    val content: SearchContent,
    val recentTerms: List<SearchTerm>,
    internal val consumedCursors: Set<FilesCursor>,
    internal val nextRequestValue: Long,
)

sealed interface SearchOutput {
    data class OpenResult(
        val item: FilesItem,
    ) : SearchOutput
}

sealed interface RecentSearchEdit {
    data class Remove(
        val term: SearchTerm,
    ) : RecentSearchEdit

    data object Clear : RecentSearchEdit
}

interface RecentSearchStore {
    val terms: StateFlow<List<SearchTerm>>

    fun record(term: SearchTerm)

    fun remove(term: SearchTerm)

    fun clear()
}
