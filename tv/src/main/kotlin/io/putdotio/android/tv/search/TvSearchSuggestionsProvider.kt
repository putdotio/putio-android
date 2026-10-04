package io.putdotio.android.tv.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItem
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.auth.TvAuthController
import io.putdotio.android.tv.auth.TvAuthRuntime
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * put.io as an Android TV global search source. The system's search app queries this provider
 * as the viewer types (`searchable.xml`); each query runs [TvGlobalSearch], and a chosen row
 * opens `putio://files/<id>` in [io.putdotio.android.MainActivity]. Only callers holding
 * `GLOBAL_SEARCH` (the system search app) may use it, so other apps never see file names.
 */
internal class TvSearchSuggestionsProvider internal constructor(
    private val globalSearch: (Context?) -> TvGlobalSearch?,
) : ContentProvider() {
    constructor() : this({ context -> context?.let { TvAuthRuntime.get(it).globalSearch } })

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
        val search = globalSearch(context) ?: return tvSearchSuggestions(emptyList(), limit)
        // A binder thread: the system search waits on this answer, so it is bounded.
        val items = runBlocking { withTimeoutOrNull(QUERY_TIMEOUT_MILLIS) { search.files(query) } }
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
 * A system search query: the first page of the account's file search, run in the signed-in
 * session. Nothing for a blank query, no session or a failed search. Nobody watches this
 * process, so a 401 goes to [reject], which ends the session without requesting a code.
 */
internal class TvGlobalSearch(
    /** The signed-in session, restored without a screen when the app has not; null for none. */
    private val session: suspend () -> TvAuthState.SignedIn?,
    private val search: suspend (SearchTerm) -> PutioResult<SearchPage>,
    private val reject: suspend (TvAuthSessionId) -> Unit,
) {
    suspend fun files(query: String?): List<FilesItem> {
        val term = query?.trim()?.takeIf(String::isNotEmpty)?.let(::SearchTerm) ?: return emptyList()
        val signedIn = session()
        val result = signedIn?.let { search(term) }
        if (signedIn != null && (result as? PutioResult.Failure)?.failure is PutioFailure.AuthenticationRequired) {
            reject(signedIn.sessionId)
        }
        return (result as? PutioResult.Success)?.value?.items.orEmpty()
    }
}

/** System search in [controller]'s session; a 401 ends it quietly, with no code polled unseen. */
internal fun tvGlobalSearch(
    controller: TvAuthController,
    session: suspend () -> TvAuthState.SignedIn?,
    search: suspend (SearchTerm) -> PutioResult<SearchPage>,
): TvGlobalSearch =
    TvGlobalSearch(session, search) { controller.rejectAuthoritativeSession(it, quiet = true) }

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
