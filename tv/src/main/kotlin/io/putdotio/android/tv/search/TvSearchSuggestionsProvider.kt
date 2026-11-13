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
import io.putdotio.android.R
import io.putdotio.android.files.FilesItem
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.TvLaunchRequest
import io.putdotio.android.tv.TvLinkKey
import io.putdotio.android.tv.auth.TvAuthController
import io.putdotio.android.tv.auth.TvAuthRuntime
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.toUri
import io.putdotio.android.tv.tvLauncherFallbackArt
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * put.io as an Android TV global search source. The system's search app queries this provider
 * as the viewer types (`searchable.xml`); each query runs [TvGlobalSearch]. A chosen file row
 * opens `putio://files/<id>~<key>` in [io.putdotio.android.MainActivity], and the first row,
 * "Search put.io for …", opens `putio://search/<key>?q=<term>`. Only callers holding
 * `GLOBAL_SEARCH` (the system search app) may use it, so other apps never see file names or the
 * [TvLinkKey] that lets a link open a file.
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
        val context = context
        val search = globalSearch(context)
        if (context == null || search == null) return MatrixCursor(SUGGESTION_COLUMNS)
        // A binder thread: the system search waits on this answer, so it is bounded.
        val found = runBlocking { withTimeoutOrNull(QUERY_TIMEOUT_MILLIS) { search.files(query) } }
            ?: return MatrixCursor(SUGGESTION_COLUMNS)
        return tvSearchSuggestions(
            searchRow = TvSearchRow(found.term, context.getString(R.string.tv_global_search_in_app, found.term.value)),
            items = found.items,
            limit = limit,
            fallbackArt = tvLauncherFallbackArt(context),
            key = TvLinkKey.get(context),
        )
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

/** What a system search query found: its [term], and [items] from the account, empty when the search failed. */
internal data class TvGlobalSearchResult(val term: SearchTerm, val items: List<FilesItem>)

/**
 * A system search query: the first page of the account's file search, run in the signed-in
 * session. Null for a blank query, no session, or a session that ended while it ran; no items
 * for a failed search. Nobody watches this process, so a 401 goes to [reject], which ends the
 * session without requesting a code.
 */
internal class TvGlobalSearch(
    /** The signed-in session, restored without a screen when the app has not; null for none. */
    private val session: suspend () -> TvAuthState.SignedIn?,
    private val search: suspend (SearchTerm) -> PutioResult<SearchPage>,
    private val reject: suspend (TvAuthSessionId) -> Unit,
    /** Whether the session is still the signed-in one. */
    private val isCurrent: (TvAuthSessionId) -> Boolean,
) {
    suspend fun files(query: String?): TvGlobalSearchResult? {
        val term = query?.trim()?.takeIf(String::isNotEmpty)?.let(::SearchTerm) ?: return null
        val signedIn = session() ?: return null
        val result = search(term)
        if ((result as? PutioResult.Failure)?.failure is PutioFailure.AuthenticationRequired) {
            reject(signedIn.sessionId)
        }
        if (!isCurrent(signedIn.sessionId)) return null
        return TvGlobalSearchResult(term, (result as? PutioResult.Success)?.value?.items.orEmpty())
    }
}

/** System search in [controller]'s session; a 401 ends it quietly, with no code polled unseen. */
internal fun tvGlobalSearch(
    controller: TvAuthController,
    session: suspend () -> TvAuthState.SignedIn?,
    search: suspend (SearchTerm) -> PutioResult<SearchPage>,
): TvGlobalSearch =
    TvGlobalSearch(
        session = session,
        search = search,
        reject = { controller.rejectAuthoritativeSession(it, quiet = true) },
        isCurrent = { (controller.state.value as? TvAuthState.SignedIn)?.sessionId == it },
    )

/** The row that opens Search with [term], shown as [label]. */
internal data class TvSearchRow(val term: SearchTerm, val label: String)

/**
 * The rows the system search shows. First [searchRow], whose intent data is the keyed
 * `putio://search/<key>?q=<term>`. Then each file: its name, its kind, put.io's still or else
 * [fallbackArt], and a duration for media; the intent data id completes `putio://files/<id>~<key>`.
 */
internal fun tvSearchSuggestions(
    searchRow: TvSearchRow?,
    items: List<FilesItem>,
    limit: Int?,
    fallbackArt: String?,
    key: TvLinkKey,
): Cursor {
    val cursor = MatrixCursor(SUGGESTION_COLUMNS)
    val rows = limit?.takeIf { it > 0 } ?: DEFAULT_LIMIT
    searchRow?.let {
        cursor.addRow(
            arrayOf<Any?>(
                SEARCH_ROW_ID,
                it.label,
                null,
                null,
                TvLaunchRequest.Search(it.term).toUri(key).toString(),
                fallbackArt,
                null,
                SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT,
            ),
        )
    }
    items.take(rows - cursor.count).forEach { item ->
        cursor.addRow(
            arrayOf<Any?>(
                item.id.value,
                item.name,
                item.type.suggestionKind(),
                key.fileLinkSegment(item.id),
                null,
                item.screenshotUrl ?: fallbackArt,
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
    SearchManager.SUGGEST_COLUMN_INTENT_DATA,
    SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
    SearchManager.SUGGEST_COLUMN_DURATION,
    SearchManager.SUGGEST_COLUMN_SHORTCUT_ID,
)

private const val DEFAULT_LIMIT = 20
private const val SEARCH_ROW_ID = 0L
private const val QUERY_TIMEOUT_MILLIS = 10_000L
private const val MILLIS_PER_SECOND = 1_000.0
