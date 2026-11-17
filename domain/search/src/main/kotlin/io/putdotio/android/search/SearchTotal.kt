package io.putdotio.android.search

import io.putdotio.android.files.FilesItem
import java.text.NumberFormat
import java.util.Locale

public sealed interface SearchTotal {
    public val count: Int

    public fun shown(locale: Locale): String {
        val number = NumberFormat.getIntegerInstance(locale).format(count)
        return if (this is AtLeast) "$number+" else number
    }

    public data class Exact(
        override val count: Int,
    ) : SearchTotal

    public data class AtLeast(
        override val count: Int,
    ) : SearchTotal
}

internal const val SEARCH_TOTAL_CAP = 1000

internal fun SearchState.totalOf(
    items: List<FilesItem>,
    paging: SearchPaging,
    matched: Int,
    capped: Boolean,
): SearchTotal? {
    val complete = paging == SearchPaging.Complete
    return when {
        filter == SearchFilter.Subtitles && !complete -> null
        filter == SearchFilter.Subtitles && capped -> SearchTotal.AtLeast(items.size)
        filter == SearchFilter.Subtitles -> SearchTotal.Exact(items.size)
        capped -> SearchTotal.AtLeast(maxOf(SEARCH_TOTAL_CAP, items.size))
        complete -> SearchTotal.Exact(items.size)
        else -> SearchTotal.Exact(maxOf(matched, items.size))
    }
}

internal fun SearchContent.total(): SearchTotal? =
    when (this) {
        is SearchContent.Ready -> total
        is SearchContent.Empty -> total
        else -> null
    }

internal fun SearchTotal.plus(change: Int): SearchTotal =
    when (this) {
        is SearchTotal.Exact -> SearchTotal.Exact((count + change).coerceAtLeast(0))
        is SearchTotal.AtLeast -> this
    }
