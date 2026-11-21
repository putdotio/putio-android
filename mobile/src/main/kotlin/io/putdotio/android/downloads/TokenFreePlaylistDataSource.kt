package io.putdotio.android.downloads

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayInputStream

/**
 * Serves HLS playlists with every `oauth_token` query removed, so the copies a download stores hold no
 * credential; the resolver authenticates the URLs they list when they are fetched. A playlist is read whole
 * and served from the requested position. Every other resource passes through.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class TokenFreePlaylistDataSource(private val upstream: DataSource) : DataSource by upstream {
    private var playlist: ByteArrayInputStream? = null

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.lastPathSegment?.endsWith(PLAYLIST_EXTENSION) != true) return upstream.open(dataSpec)
        upstream.open(dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
        val body = withoutOauthTokens(DataSourceUtil.readToEnd(upstream))
        val start = dataSpec.position.coerceAtMost(body.size.toLong()).toInt()
        val available = (body.size - start).toLong()
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length, available)
        playlist = ByteArrayInputStream(body, start, length.toInt())
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val served = playlist ?: return upstream.read(buffer, offset, length)
        return when (length) {
            0 -> 0
            else -> served.read(buffer, offset, length).takeUnless { it == -1 } ?: C.RESULT_END_OF_INPUT
        }
    }

    override fun close() {
        playlist = null
        upstream.close()
    }

    private companion object {
        const val PLAYLIST_EXTENSION = ".m3u8"
    }
}

private val OAUTH_TOKEN_PARAMETER = Regex("""([?&])$OAUTH_TOKEN_QUERY=[^&"\s]*(&?)""")

/** The playlist text with each URL's `oauth_token` parameter removed and the rest of its query kept. */
internal fun withoutOauthTokens(playlist: ByteArray): ByteArray =
    String(playlist, Charsets.UTF_8)
        .replace(OAUTH_TOKEN_PARAMETER) { match -> if (match.groupValues[2].isEmpty()) "" else match.groupValues[1] }
        .toByteArray(Charsets.UTF_8)
