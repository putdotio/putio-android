package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FilesMoveTargetMemoryTest {
    private val folder = FilesFolder(FilesItemId(8L), "Sample folder")
    private val nested = FilesFolder(FilesItemId(9L), "Archive été 東京")

    @Test
    fun thePickerOpensAtTheLastTargetOnlyWhileRememberIsOn() {
        val path = listOf(folder, nested)
        val off = FilesMoveTargetMemory(remember = false, lastTarget = path)
        assertEquals(emptyList<FilesFolder>(), off.startPath(null))
        assertEquals(path, FilesMoveTargetMemory(remember = true, lastTarget = path).startPath(null))
        assertEquals(path, FilesMoveTargetMemory(remember = true, lastTarget = path).startPath(item(7L)))
    }

    @Test
    fun aMoveNeverOpensInsideTheItemItMoves() {
        val memory = FilesMoveTargetMemory(remember = true, lastTarget = listOf(folder, nested))
        assertEquals(emptyList<FilesFolder>(), memory.startPath(item(folder.id.value)))
        assertEquals(emptyList<FilesFolder>(), memory.startPath(item(nested.id.value)))
    }

    @Test
    fun aChoiceIsRecordedBelowRootOnlyWhileRememberIsOn() {
        val off = FilesMoveTargetMemory(remember = false, lastTarget = listOf(folder))
        assertSame(off, off.chosen(listOf(FilesFolder.Root, nested)))
        val on = FilesMoveTargetMemory(remember = true, lastTarget = listOf(folder))
        assertEquals(listOf(nested), on.chosen(listOf(FilesFolder.Root, nested)).lastTarget)
        assertEquals(emptyList<FilesFolder>(), on.chosen(listOf(FilesFolder.Root)).lastTarget)
    }

    private fun item(id: Long) = FilesItem(
        FilesItemId(id), FilesFolder.Root.id, "Moving folder", PutioFileType.FOLDER, 1L, "2026-10-01",
    )
}
