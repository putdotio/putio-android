package io.putdotio.android.downloads

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata

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
        val mutations = ContentMetadataMutations().apply { names.forEach(::remove) }
        try {
            applyContentMetadataMutations(key, mutations)
        } catch (_: Cache.CacheException) {
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun ContentMetadata.signedNames(): List<String> {
    val entries = (this as? DefaultContentMetadata)?.entrySet() ?: return emptyList()
    return entries
        .filter { (name, value) ->
            name == ContentMetadata.KEY_REDIRECTED_URI || OAUTH_TOKEN_QUERY in String(value, Charsets.ISO_8859_1)
        }
        .map { it.key }
}
