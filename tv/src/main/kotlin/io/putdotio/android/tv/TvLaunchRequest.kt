package io.putdotio.android.tv

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.SearchTerm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the system asked the TV app to show: a file from search or Watch Next, or a search term. */
internal sealed interface TvLaunchRequest {
    /** A file to open; [continueWatching] marks a Watch Next card, which leaves the row once its file is gone. */
    data class OpenFile(
        val id: FilesItemId,
        val continueWatching: Boolean = false,
    ) : TvLaunchRequest

    data class Search(val term: SearchTerm) : TvLaunchRequest
}

/**
 * Reads `putio://files/<id>~<key>` (a system search result), `putio://continue/<id>~<key>` (a
 * Watch Next card) and an `ACTION_SEARCH` term, then removes them from the intent so a replayed
 * intent cannot open them twice. A file link carries this install's key in its path; a search term
 * carries it as [SearchManager.EXTRA_DATA_KEY], which the system search app fills from a put.io
 * row's extra data. Anything without the key returns null, and the app opens normally.
 */
internal fun Intent.consumeTvLaunchRequest(key: () -> TvLinkKey): TvLaunchRequest? =
    when (action) {
        Intent.ACTION_VIEW -> data?.let { link ->
            setDataAndType(null, null)
            (link.toTvLaunchRequest() as? TvLaunchRequest.OpenFile)
                ?.takeIf { link.fileLinkKey()?.let { key().matches(it) } == true }
        }
        Intent.ACTION_SEARCH -> {
            val term = getStringExtra(SearchManager.QUERY)?.trim()?.takeIf(String::isNotEmpty)
            val keyed = getStringExtra(SearchManager.EXTRA_DATA_KEY)?.let { key().matches(it) } == true
            removeExtra(SearchManager.QUERY)
            removeExtra(SearchManager.EXTRA_DATA_KEY)
            term?.takeIf { keyed }?.let { TvLaunchRequest.Search(SearchTerm(it)) }
        }
        else -> null
    }

internal fun Uri.toTvLaunchRequest(): TvLaunchRequest? {
    if (scheme != SCHEME) return null
    // Shared folders such as Items shared with you (-2) have negative ids; only files are watched.
    val id = pathSegments.singleOrNull()?.substringBefore(TvLinkKey.SEPARATOR)?.toLongOrNull()
        ?.takeIf { it != 0L }?.let(::FilesItemId)
    return when (host) {
        HOST_FILES -> id?.let { TvLaunchRequest.OpenFile(it) }
        HOST_CONTINUE -> id?.takeIf { it.value > 0L }?.let { TvLaunchRequest.OpenFile(it, continueWatching = true) }
        HOST_SEARCH -> getQueryParameter(QUERY)?.trim()?.takeIf(String::isNotEmpty)
            ?.let { TvLaunchRequest.Search(SearchTerm(it)) }
        else -> null
    }
}

private fun Uri.fileLinkKey(): String? =
    pathSegments.singleOrNull()?.substringAfter(TvLinkKey.SEPARATOR, missingDelimiterValue = "")
        ?.takeIf(String::isNotEmpty)

/** The request as a `putio://` URI: what saved state keeps, and with [key], what Watch Next cards carry. */
internal fun TvLaunchRequest.toUri(key: TvLinkKey? = null): Uri =
    when (this) {
        is TvLaunchRequest.OpenFile ->
            "$SCHEME://${if (continueWatching) HOST_CONTINUE else HOST_FILES}/${key?.fileLinkSegment(id) ?: id.value}"
                .toUri()
        is TvLaunchRequest.Search ->
            Uri.Builder().scheme(SCHEME).authority(HOST_SEARCH).appendQueryParameter(QUERY, term.value).build()
    }

/** A pending launch request; it stays pending until the signed-in shell has acted on it. */
internal class TvLaunchRequests : ViewModel() {
    private val mutablePending = MutableStateFlow<TvLaunchRequest?>(null)
    val pending: StateFlow<TvLaunchRequest?> = mutablePending.asStateFlow()

    /** Set once the activity's launch intent has been read, so a recreation does not read it again. */
    var launchIntentConsumed: Boolean = false

    /** Only the newest request matters. */
    fun receive(request: TvLaunchRequest) {
        mutablePending.value = request
    }

    fun acknowledge(request: TvLaunchRequest) {
        if (mutablePending.value == request) mutablePending.value = null
    }
}

private const val SCHEME = "putio"
private const val HOST_FILES = "files"
private const val HOST_CONTINUE = "continue"
private const val HOST_SEARCH = "search"
private const val QUERY = "q"
