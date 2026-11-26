package io.putdotio.android.tv

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.SearchTerm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface TvLaunchRequest {
    data class OpenFile(
        val id: FilesItemId,
        val continueWatching: Boolean = false,
    ) : TvLaunchRequest

    data class Search(val term: SearchTerm) : TvLaunchRequest
}

internal fun Intent.consumeTvLaunchRequest(key: () -> TvLinkKey): TvLaunchRequest? =
    data?.takeIf { action == Intent.ACTION_VIEW }?.let { link ->
        setDataAndType(null, null)
        link.toTvLaunchRequest()?.takeIf { link.linkKey()?.let { key().matches(it) } == true }
    }

internal fun Uri.toTvLaunchRequest(): TvLaunchRequest? {
    if (scheme != SCHEME) return null
    val id = pathSegments.singleOrNull()?.substringBefore(TvLinkKey.SEPARATOR)?.toLongOrNull()
        ?.takeIf { it != 0L }?.let(::FilesItemId)
    return when (host) {
        HOST_FILES -> id?.let { TvLaunchRequest.OpenFile(it) }
        HOST_CONTINUE -> id?.takeIf { it.value > 0L }?.let { TvLaunchRequest.OpenFile(it, continueWatching = true) }
        HOST_SEARCH -> getQueryParameter(QUERY)?.trim()?.takeIf { pathSegments.size <= 1 && it.isNotEmpty() }
            ?.let { TvLaunchRequest.Search(SearchTerm(it)) }
        else -> null
    }
}

private fun Uri.linkKey(): String? =
    pathSegments.singleOrNull()
        ?.let { if (host == HOST_SEARCH) it else it.substringAfter(TvLinkKey.SEPARATOR, missingDelimiterValue = "") }
        ?.takeIf(String::isNotEmpty)

internal fun TvLaunchRequest.toUri(key: TvLinkKey? = null): Uri =
    when (this) {
        is TvLaunchRequest.OpenFile ->
            "$SCHEME://${if (continueWatching) HOST_CONTINUE else HOST_FILES}/${key?.fileLinkSegment(id) ?: id.value}"
                .toUri()
        is TvLaunchRequest.Search ->
            Uri.Builder().scheme(SCHEME).authority(HOST_SEARCH).apply { key?.let { appendPath(it.value) } }
                .appendQueryParameter(QUERY, term.value).build()
    }

internal class TvLaunchRequests : ViewModel() {
    private val mutablePending = MutableStateFlow<TvLaunchRequest?>(null)
    val pending: StateFlow<TvLaunchRequest?> = mutablePending.asStateFlow()

    var launchIntentConsumed: Boolean = false

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
