package io.putdotio.android.search

/** A Search state as the controller would hold it, for tests outside this module. */
fun searchState(
    query: String,
    content: SearchContent,
    recentTerms: List<SearchTerm>,
    nextRequestValue: Long,
    recentSearchesEnabled: Boolean? = null,
): SearchState =
    SearchState(
        query = query,
        content = content,
        recentTerms = recentTerms,
        consumedCursors = emptySet(),
        nextRequestValue = nextRequestValue,
        recentSearchesEnabled = recentSearchesEnabled,
    )
