package io.putdotio.android.tv

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import io.putdotio.android.files.FilesItemId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the system asked the TV app to show: a file from search or Watch Next. */
internal sealed interface TvLaunchRequest {
    /** A file to open; [continueWatching] marks a Watch Next card, which leaves the row once its file is gone. */
    data class OpenFile(
        val id: FilesItemId,
        val continueWatching: Boolean = false,
    ) : TvLaunchRequest
}

/**
 * Reads `putio://files/<id>~<key>` (a system search result) and `putio://continue/<id>~<key>` (a
 * Watch Next card), then removes the link from the intent so a replayed intent cannot open it
 * twice. A link without this install's key and anything else, a search term included, return
 * null, and the app opens normally.
 */
internal fun Intent.consumeTvLaunchRequest(key: () -> TvLinkKey): TvLaunchRequest? =
    data?.takeIf { action == Intent.ACTION_VIEW }?.let { link ->
        setDataAndType(null, null)
        link.toTvLaunchRequest()?.takeIf { link.fileLinkKey()?.let { key().matches(it) } == true }
    }

internal fun Uri.toTvLaunchRequest(): TvLaunchRequest? {
    if (scheme != SCHEME) return null
    // Shared folders such as Items shared with you (-2) have negative ids; only files are watched.
    val id = pathSegments.singleOrNull()?.substringBefore(TvLinkKey.SEPARATOR)?.toLongOrNull()
        ?.takeIf { it != 0L }?.let(::FilesItemId)
    return when (host) {
        HOST_FILES -> id?.let { TvLaunchRequest.OpenFile(it) }
        HOST_CONTINUE -> id?.takeIf { it.value > 0L }?.let { TvLaunchRequest.OpenFile(it, continueWatching = true) }
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
