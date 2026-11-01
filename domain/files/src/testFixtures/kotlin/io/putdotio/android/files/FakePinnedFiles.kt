package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import kotlinx.coroutines.flow.MutableStateFlow

public class FakePinnedFiles(initial: List<FilesItemId>? = emptyList()) : PinnedFiles {
    override val pins: MutableStateFlow<List<FilesItemId>?> = MutableStateFlow(initial)
    override val failure: MutableStateFlow<PutioFailure?> = MutableStateFlow(null)
    override val editFailure: MutableStateFlow<PutioFailure?> = MutableStateFlow(null)
    public var reloads: Int = 0
        private set
    public var closed: Boolean = false
        private set

    override fun pin(id: FilesItemId) {
        pins.value = listOf(id) + pins.value.orEmpty().minus(id)
    }

    override fun unpin(id: FilesItemId) {
        pins.value = pins.value.orEmpty().minus(id)
    }

    override fun unpinGone(id: FilesItemId) {
        unpin(id)
    }

    override fun reload() {
        reloads++
    }

    override fun dismissEditFailure() {
        editFailure.value = null
    }

    override fun close() {
        closed = true
    }
}
