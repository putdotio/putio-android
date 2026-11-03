package io.putdotio.android.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileContinueWatchingActionsTest {
    private val store = object : ContinueWatchingStore {
        var saved: List<ContinueWatchingEntry> = emptyList()

        override fun read(): List<ContinueWatchingEntry> = saved

        override fun write(entries: List<ContinueWatchingEntry>) {
            saved = entries
        }
    }
    private val row = ContinueWatching(store)

    @Test
    fun undoPutsAHiddenVideoBackAtItsPosition() {
        row.opened(FilesItemId(7L), "Sintel.mkv", 888.0)
        row.positionSaved(FilesItemId(7L), 120.0)
        val entry = row.entries.value.single()
        val actions = MobileFilesItemActions().withContinueWatching(row)

        checkNotNull(actions.onRemoveFromContinueWatching)(entry)
        assertTrue(store.saved.isEmpty())
        checkNotNull(actions.onRestoreToContinueWatching)(entry)

        assertEquals(listOf(FilesItemId(7L) to 120.0), store.saved.map { it.fileId to it.positionSeconds })
    }
}
