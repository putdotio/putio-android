package io.putdotio.android.search

import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.settings.StreamingMethod

internal class MobileSearchPlayWith {
    private var pending: Pair<FilesItemId, StreamingMethod>? = null

    fun open(item: FilesItem, method: StreamingMethod, openResult: (FilesItem) -> Unit) {
        pending = item.id to method
        openResult(item)
    }

    fun take(item: FilesItem): StreamingMethod? {
        val method = pending?.takeIf { it.first == item.id }?.second
        pending = null
        return method
    }
}
