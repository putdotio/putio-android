package io.putdotio.android.search

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.toFilesFailure
import io.putdotio.android.files.toFilesItem
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.files.FileSearchResponse
import io.putdotio.sdk.files.FilesSearchQuery
import java.util.concurrent.CancellationException

interface SearchRepository {
    suspend fun search(term: SearchTerm): FilesRepositoryResult<SearchPage>

    suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<SearchPage>
}

class SdkSearchRepository internal constructor(
    private val searchFiles: suspend (FilesSearchQuery) -> FileSearchResponse,
    private val continueSearch: suspend (String) -> FileSearchResponse,
) : SearchRepository {
    constructor(client: PutioClient) : this(
        searchFiles = { query -> client.files.search(query) },
        continueSearch = { cursor -> client.files.continueSearch(cursor) },
    )

    override suspend fun search(term: SearchTerm): FilesRepositoryResult<SearchPage> =
        requestPage { searchFiles(FilesSearchQuery(keyword = term.value)) }

    override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<SearchPage> =
        requestPage { continueSearch(cursor.value) }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun requestPage(request: suspend () -> FileSearchResponse): FilesRepositoryResult<SearchPage> =
        try {
            FilesRepositoryResult.Success(request().toSearchPage())
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            FilesRepositoryResult.Failure(error.toFilesFailure())
        } catch (unexpected: Exception) {
            FilesRepositoryResult.Failure(FilesFailure.Unexpected(unexpected))
        }
}

private fun FileSearchResponse.toSearchPage(): SearchPage =
    SearchPage(
        items = files.map { it.toFilesItem() },
        nextCursor = cursor?.takeIf(String::isNotBlank)?.let(::FilesCursor),
        total = total,
    )
