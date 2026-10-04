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

/** What the system asked the TV app to show: a file from search or Watch Next, or a search. */
internal sealed interface TvLaunchRequest {
    /** A file to open; [continueWatching] plays a video from its saved position without asking. */
    data class OpenFile(
        val id: FilesItemId,
        val continueWatching: Boolean = false,
    ) : TvLaunchRequest

    /** A query the system search handed to the app. */
    data class Search(val term: SearchTerm) : TvLaunchRequest
}

/**
 * Reads `putio://files/<id>` (a system search result), `putio://continue/<id>` (a Watch Next
 * card) and `ACTION_SEARCH`, then removes them from the intent so a replayed intent cannot open
 * them twice. Anything else returns null and the app opens normally.
 */
internal fun Intent.consumeTvLaunchRequest(): TvLaunchRequest? =
    when (action) {
        Intent.ACTION_VIEW -> data?.toTvLaunchRequest()?.also { setDataAndType(null, null) }
        Intent.ACTION_SEARCH ->
            getStringExtra(SearchManager.QUERY)?.trim()?.takeIf(String::isNotEmpty)
                ?.let { TvLaunchRequest.Search(SearchTerm(it)) }
                .also { removeExtra(SearchManager.QUERY) }
        else -> null
    }

internal fun Uri.toTvLaunchRequest(): TvLaunchRequest? {
    if (scheme != SCHEME) return null
    val id = pathSegments.singleOrNull()?.toLongOrNull()?.takeIf { it > 0L }?.let(::FilesItemId)
    return when (host) {
        HOST_FILES -> id?.let { TvLaunchRequest.OpenFile(it) }
        HOST_CONTINUE -> id?.let { TvLaunchRequest.OpenFile(it, continueWatching = true) }
        HOST_SEARCH -> getQueryParameter(QUERY)?.trim()?.takeIf(String::isNotEmpty)
            ?.let { TvLaunchRequest.Search(SearchTerm(it)) }
        else -> null
    }
}

/** The request as a `putio://` URI: what Watch Next cards carry and saved state keeps. */
internal fun TvLaunchRequest.toUri(): Uri =
    when (this) {
        is TvLaunchRequest.OpenFile ->
            "$SCHEME://${if (continueWatching) HOST_CONTINUE else HOST_FILES}/${id.value}".toUri()
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
