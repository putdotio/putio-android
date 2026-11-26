package io.putdotio.android.search

public fun searchState(
    query: String,
    content: SearchContent,
    recentTerms: List<SearchTerm>,
    nextRequestValue: Long,
    recentSearchesEnabled: Boolean? = null,
    scope: SearchScope? = null,
    filter: SearchFilter = SearchFilter.All,
): SearchState =
    SearchState(
        query = query,
        content = content,
        recentTerms = recentTerms,
        consumedCursors = emptySet(),
        nextRequestValue = nextRequestValue,
        recentSearchesEnabled = recentSearchesEnabled,
        scope = scope,
        filter = filter,
    )
