package io.putdotio.android.tv.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItem
import io.putdotio.android.search.SdkSearchRepository
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.auth.TvAuthRuntime
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * put.io as an Android TV global search source. The system's search app queries this provider
 * as the viewer types (`searchable.xml`); each query runs the account's file search, the same
 * one the Search pane uses, and a chosen row opens `putio://files/<id>` in [io.putdotio.android.MainActivity].
 * Only callers holding `GLOBAL_SEARCH` (the system search app) may read it, so other apps
 * never see file names. Signed out, it answers nothing and starts no sign-in.
 */
internal class TvSearchSuggestionsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val query = selectionArgs?.firstOrNull()
            ?: uri.lastPathSegment?.takeIf { it != SearchManager.SUGGEST_URI_PATH_QUERY }
        val limit = uri.getQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT)?.toIntOrNull()
        val runtime = context?.let(TvAuthRuntime::get) ?: return tvSearchSuggestions(emptyList(), limit)
        // A binder thread: the system search waits on this answer, so it is bounded.
        val items = runBlocking {
            withTimeoutOrNull(QUERY_TIMEOUT_MILLIS) {
                tvGlobalSearch(query) { term ->
                    val session = runtime.signedInSession() ?: return@tvGlobalSearch null
                    SdkSearchRepository(runtime.putioClient).search(term).also { result ->
                        val failure = (result as? PutioResult.Failure)?.failure
                        if (failure is PutioFailure.AuthenticationRequired) {
                            runtime.authController.rejectAuthoritativeSession(session.sessionId)
                        }
                    }
                }
            }
        }
        return tvSearchSuggestions(items.orEmpty(), limit)
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}

/**
 * The files a global search for [query] lists: the first page of the account's search, or
 * nothing for a blank query, no session ([search] answers null) or a failed search.
 */
internal suspend fun tvGlobalSearch(
    query: String?,
    search: suspend (SearchTerm) -> PutioResult<SearchPage>?,
): List<FilesItem> {
    val term = query?.trim()?.takeIf(String::isNotEmpty)?.let(::SearchTerm) ?: return emptyList()
    return (search(term) as? PutioResult.Success)?.value?.items.orEmpty()
}

/**
 * The rows the system search shows: the file's name, its kind, put.io's still when it has one,
 * and a duration for media. The intent data id completes `putio://files/<id>`.
 */
internal fun tvSearchSuggestions(items: List<FilesItem>, limit: Int?): Cursor {
    val cursor = MatrixCursor(SUGGESTION_COLUMNS)
    items.take(limit?.takeIf { it > 0 } ?: DEFAULT_LIMIT).forEach { item ->
        cursor.addRow(
            arrayOf<Any?>(
                item.id.value,
                item.name,
                item.type.suggestionKind(),
                item.id.value.toString(),
                item.screenshotUrl,
                item.playback?.durationSeconds?.let { (it * MILLIS_PER_SECOND).toLong() },
                SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT,
            ),
        )
    }
    return cursor
}

private fun PutioFileType.suggestionKind(): String? =
    when (this) {
        PutioFileType.VIDEO -> "video/*"
        PutioFileType.AUDIO -> "audio/*"
        else -> null
    }

private val SUGGESTION_COLUMNS = arrayOf(
    BaseColumns._ID,
    SearchManager.SUGGEST_COLUMN_TEXT_1,
    SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
    SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
    SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
    SearchManager.SUGGEST_COLUMN_DURATION,
    SearchManager.SUGGEST_COLUMN_SHORTCUT_ID,
)

private const val DEFAULT_LIMIT = 20
private const val QUERY_TIMEOUT_MILLIS = 10_000L
private const val MILLIS_PER_SECOND = 1_000.0
