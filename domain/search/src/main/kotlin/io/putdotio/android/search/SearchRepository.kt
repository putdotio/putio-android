package io.putdotio.android.search

import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.toFilesItem
import io.putdotio.android.putioRequest
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.FileSearchResponse
import io.putdotio.sdk.files.FilesSearchQuery

interface SearchRepository {
    suspend fun search(term: SearchTerm): PutioResult<SearchPage>

    suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage>
}

class SdkSearchRepository internal constructor(
    private val searchFiles: suspend (FilesSearchQuery) -> FileSearchResponse,
    private val continueSearch: suspend (String) -> FileSearchResponse,
) : SearchRepository {
    constructor(client: PutioClient) : this(
        searchFiles = { query -> client.files.search(query) },
        continueSearch = { cursor -> client.files.continueSearch(cursor) },
    )

    override suspend fun search(term: SearchTerm): PutioResult<SearchPage> =
        putioRequest { searchFiles(FilesSearchQuery(keyword = term.value)).toSearchPage() }

    override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<SearchPage> =
        putioRequest { continueSearch(cursor.value).toSearchPage() }
}

private fun FileSearchResponse.toSearchPage(): SearchPage =
    SearchPage(
        items = files.map { it.toFilesItem() },
        nextCursor = cursor?.takeIf(String::isNotBlank)?.let(::FilesCursor),
        total = total,
    )
