package io.putdotio.android.search

import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.toFilesItem
import io.putdotio.android.putioRequest
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.FileSearchResponse
import io.putdotio.sdk.files.FilesContinueQuery
import io.putdotio.sdk.files.FilesSearchQuery

public interface SearchRepository {
    public suspend fun search(term: SearchTerm): PutioResult<SearchPage>

    /** A search limited to [filter]'s put.io types; a repository that can't filter searches everything. */
    public suspend fun search(
        term: SearchTerm,
        filter: SearchFilter,
    ): PutioResult<SearchPage> = search(term)

    public suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage>
}

public class SdkSearchRepository internal constructor(
    private val searchFiles: suspend (FilesSearchQuery) -> FileSearchResponse,
    private val continueSearch: suspend (String, FilesContinueQuery) -> FileSearchResponse,
) : SearchRepository {
    public constructor(client: PutioClient) : this(
        searchFiles = { query -> client.files.search(query) },
        continueSearch = { cursor, query -> client.files.continueSearch(cursor, query) },
    )

    override suspend fun search(term: SearchTerm): PutioResult<SearchPage> = search(term, SearchFilter.All)

    override suspend fun search(
        term: SearchTerm,
        filter: SearchFilter,
    ): PutioResult<SearchPage> =
        putioRequest {
            searchFiles(
                FilesSearchQuery(keyword = term.value, perPage = SEARCH_PAGE_SIZE, type = filter.apiTypes),
            ).toSearchPage()
        }

    override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage> =
        putioRequest {
            continueSearch(cursor.value, FilesContinueQuery(perPage = SEARCH_PAGE_SIZE)).toSearchPage()
        }
}

private fun FileSearchResponse.toSearchPage(): SearchPage =
    SearchPage(
        items = files.map { it.toFilesItem() },
        nextCursor = cursor?.takeIf(String::isNotBlank)?.let(::FilesCursor),
        total = total,
    )

private const val SEARCH_PAGE_SIZE = 50
