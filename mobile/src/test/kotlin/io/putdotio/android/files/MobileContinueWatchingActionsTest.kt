package io.putdotio.android.files

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileContinueWatchingActionsTest {
    private val store = object : ContinueWatchingStore {
        var saved: List<ContinueWatchingEntry> = emptyList()

        override fun read(): List<ContinueWatchingEntry> = saved

        override fun write(entries: List<ContinueWatchingEntry>) {
            saved = entries
        }
    }
    private var now = 1_000L
    private val row = ContinueWatching(store, clock = { now++ })

    @Test
    fun undoPutsAHiddenVideoBackWhereItWas() {
        row.opened(Tears, "Tears.of.Steel.mkv", 734.0)
        row.positionSaved(Tears, 120.0)
        row.opened(Sintel, "Sintel.mkv", 888.0)
        row.positionSaved(Sintel, 300.0)
        val before = store.saved
        val actions = MobileFilesItemActions().withContinueWatching(row)

        val undo = checkNotNull(actions.onRemoveFromContinueWatching)(before.single { it.fileId == Tears })
        assertEquals(listOf(Sintel), store.saved.map { it.fileId })
        undo()

        assertEquals(before, store.saved)
    }

    private companion object {
        val Tears = FilesItemId(7L)
        val Sintel = FilesItemId(8L)
    }
}
