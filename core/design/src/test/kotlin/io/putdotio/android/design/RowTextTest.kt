package io.putdotio.android.design

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowTextTest {
    private data class Row(val id: Int, val name: String)

    private val built = mutableListOf<Row>()
    private var suffix = "text"
    private val text = RowText<Row, String>(key = { it.id }, placeholder = "") { row ->
        built += row
        "${row.name} $suffix"
    }

    private suspend fun recomposesRowsAfter(prepare: suspend () -> Unit): Boolean {
        val reads = mutableSetOf<Any>()
        val read = Snapshot.takeSnapshot { reads += it }
        read.enter { text[Row(1, "a")] }
        read.dispose()
        var invalidated = false
        val observer = Snapshot.registerApplyObserver { changed, _ ->
            invalidated = invalidated || changed.any { it in reads }
        }
        prepare()
        Snapshot.sendApplyNotifications()
        observer.dispose()
        return invalidated
    }

    @Test
    fun aRowReadBeforeItsTextIsBuiltGetsThePlaceholderAndRecomposesOnceBuilt() = runTest {
        assertEquals("", text[Row(1, "a")])
        assertEquals(emptyList<Row>(), built)

        assertTrue(recomposesRowsAfter { text.show(text.prepare(listOf(Row(1, "a"), Row(2, "b")), refresh = null)) })
        assertEquals("a text", text[Row(1, "a")])
    }

    @Test
    fun aChangedRowKeepsItsOldTextUntilTheNewTextIsBuilt() = runTest {
        text.show(text.prepare(listOf(Row(1, "a")), refresh = null))
        built.clear()

        assertEquals("a text", text[Row(1, "renamed")])
        assertEquals(emptyList<Row>(), built)

        text.show(text.prepare(listOf(Row(1, "renamed")), refresh = null))
        assertEquals("renamed text", text[Row(1, "renamed")])
    }

    @Test
    fun aRefreshRecomposesRowsOnlyWhenTheirTextChanges() = runTest {
        text.show(text.prepare(listOf(Row(1, "a")), refresh = 1))

        assertFalse(recomposesRowsAfter { text.show(text.prepare(listOf(Row(1, "a")), refresh = 2)) })

        suffix = "later"
        assertTrue(recomposesRowsAfter { text.show(text.prepare(listOf(Row(1, "a")), refresh = 3)) })
        assertEquals("a later", text[Row(1, "a")])
    }
}
