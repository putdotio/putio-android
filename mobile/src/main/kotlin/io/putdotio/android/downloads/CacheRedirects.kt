package io.putdotio.android.downloads

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata

private val OAUTH_TOKEN_BYTES = "oauth_token".encodeToByteArray()

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class RequestUriDataSource(private val upstream: DataSource) : DataSource by upstream {
    private var requestUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec).also { requestUri = dataSpec.uri }

    override fun getUri(): Uri? = requestUri

    override fun close() {
        requestUri = null
        upstream.close()
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun Cache.dropSignedMetadata() {
    for (key in keys) {
        val names = getContentMetadata(key).signedNames()
        if (names.isEmpty()) continue
        val mutations = ContentMetadataMutations()
        names.forEach(mutations::remove)
        try {
            applyContentMetadataMutations(key, mutations)
        } catch (_: Cache.CacheException) {
            return
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun ContentMetadata.signedNames(): List<String> {
    val entries = (this as? DefaultContentMetadata)?.entrySet()
        ?: return listOfNotNull(ContentMetadata.KEY_REDIRECTED_URI.takeIf { ContentMetadata.getRedirectedUri(this) != null })
    return entries
        .filter { (name, value) -> name == ContentMetadata.KEY_REDIRECTED_URI || value.contains(OAUTH_TOKEN_BYTES) }
        .map { it.key }
}

private fun ByteArray.contains(needle: ByteArray): Boolean =
    (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
