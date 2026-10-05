package io.putdotio.android.files

import androidx.compose.runtime.mutableStateOf
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesKeyboardFocusTest {
    private val rows = (1L..4L).map { id ->
        FilesItem(FilesItemId(id), FilesFolder.Root.id, "row $id", PutioFileType.TEXT, 1L, "2026-10-04")
    }
    private val lastFocused = mutableStateOf<Long?>(null)
    private val focus = FilesKeyboardFocus(entryIndex = 0, entryPending = false, lastFocusedId = lastFocused)

    @Test
    fun aFocusedRowThatLeavesHandsFocusToTheRowNowInItsPlace() {
        focus.onRowFocus(rows[1].id, index = 1, focused = true)

        focus.afterListingChange(keyboardInput = true, items = rows - rows[1])

        assertEquals(listOf(1), entries(rows.size - 1))
    }

    @Test
    fun theLastRowLeavingHandsFocusToTheNewLastRow() {
        focus.onRowFocus(rows[3].id, index = 3, focused = true)

        focus.afterListingChange(keyboardInput = true, items = rows - rows[3])

        assertEquals(listOf(2), entries(rows.size - 1))
    }

    @Test
    fun aFocusedRowThatStaysKeepsItsFocus() {
        focus.onRowFocus(rows[1].id, index = 1, focused = true)

        focus.afterListingChange(keyboardInput = true, items = rows - rows[3])

        assertTrue(entries(rows.size - 1).isEmpty())
    }

    @Test
    fun touchInputNeverMovesFocus() {
        focus.onRowFocus(rows[1].id, index = 1, focused = true)

        focus.afterListingChange(keyboardInput = false, items = rows - rows[1])

        assertTrue(entries(rows.size - 1).isEmpty())
    }

    @Test
    fun focusMovedOffTheListingStaysWhereTheViewerPutIt() {
        focus.onRowFocus(rows[1].id, index = 1, focused = true)
        // Tab to the navigation rail: the row blurs and is still listed.
        focus.onRowFocus(rows[1].id, index = 1, focused = false)

        focus.afterListingChange(keyboardInput = true, items = rows - rows[3])

        assertTrue(entries(rows.size - 1).isEmpty())
    }

    @Test
    fun theLastFocusedRowIsRememberedForTheNextEntry() {
        focus.onRowFocus(rows[2].id, index = 2, focused = true)
        focus.entered()

        assertEquals(rows[2].id.value, lastFocused.value)
        assertFalse(focus.takesEntry(0))
    }

    private fun entries(count: Int): List<Int> = (0 until count).filter(focus::takesEntry)
}
