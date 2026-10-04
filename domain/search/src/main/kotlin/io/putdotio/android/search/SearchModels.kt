package io.putdotio.android.search

import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
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
}

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
    ) : SearchContent

    public data class Ready(
        val term: SearchTerm,
        val items: List<FilesItem>,
        val paging: SearchPaging,
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
    /** Whether the account keeps recent searches; null until the shared config has loaded. */
    val recentSearchesEnabled: Boolean? = null,
)

public object SearchReducer {
    /** No query yet; recent searches come from the account once its shared config has loaded. */
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

    /** Turning history off also clears it, as tv-native's search settings do. */
    public data class SetEnabled(
        val enabled: Boolean,
    ) : RecentSearchEdit
}

public interface RecentSearchStore {
    public val terms: StateFlow<List<SearchTerm>>

    /** The account's `searchHistoryEnabled`; null until it has loaded. */
    public val enabled: StateFlow<Boolean?>

    public fun record(term: SearchTerm)

    public fun remove(term: SearchTerm)

    public fun clear()

    public fun setEnabled(enabled: Boolean)
}

private const val INITIAL_REQUEST_VALUE = 1L
