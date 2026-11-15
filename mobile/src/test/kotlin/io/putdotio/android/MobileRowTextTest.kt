package io.putdotio.android

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileRowTextTest {
    private data class Row(val id: Int, val name: String)

    private val built = mutableListOf<Row>()
    private val text = MobileRowText<Row, String>(key = { it.id }, placeholder = "") { row ->
        built += row
        "${row.name} text"
    }

    @Test
    fun aRowReadBeforeItsTextIsBuiltGetsThePlaceholderAndRecomposesOnceBuilt() = runTest {
        val reads = mutableSetOf<Any>()
        val read = Snapshot.takeSnapshot { reads += it }
        val shown = read.enter { text[Row(1, "a")] }
        read.dispose()
        assertEquals("", shown)
        assertEquals(emptyList<Row>(), built)

        var invalidated = false
        val observer = Snapshot.registerApplyObserver { changed, _ -> invalidated = changed.any { it in reads } }
        text.prepare(listOf(Row(1, "a"), Row(2, "b")), refresh = null)
        text.recomposeStaleRows()
        Snapshot.sendApplyNotifications()
        observer.dispose()

        assertTrue(invalidated)
        assertEquals("a text", text[Row(1, "a")])
    }

    @Test
    fun aChangedRowKeepsItsOldTextUntilTheNewTextIsBuilt() = runTest {
        text.prepare(listOf(Row(1, "a")), refresh = null)
        built.clear()

        assertEquals("a text", text[Row(1, "renamed")])
        assertEquals(emptyList<Row>(), built)

        text.prepare(listOf(Row(1, "renamed")), refresh = null)
        assertEquals("renamed text", text[Row(1, "renamed")])
    }
}
