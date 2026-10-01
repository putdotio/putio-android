package io.putdotio.android.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileMoveTargetStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val path = listOf(FilesFolder(FilesItemId(8L), "Sample folder"), FilesFolder(FilesItemId(9L), "Archive été 東京"))

    @Test
    fun eachAccountKeepsItsOwnChoiceUntilSignOutClearsThemAll() {
        val first = MobileMoveTargetStore(context, userId = 1L)
        val second = MobileMoveTargetStore(context, userId = 2L)
        assertEquals(FilesMoveTargetMemory(), first.read())

        first.write(FilesMoveTargetMemory(remember = true, lastTarget = path))
        assertEquals(FilesMoveTargetMemory(remember = true, lastTarget = path), MobileMoveTargetStore(context, 1L).read())
        assertEquals(FilesMoveTargetMemory(), second.read())
        second.write(FilesMoveTargetMemory(remember = true))

        MobileMoveTargetStore.clearAll(context)

        assertEquals(FilesMoveTargetMemory(), first.read())
        assertEquals(FilesMoveTargetMemory(), second.read())
    }

    @Test
    fun aDamagedRecordOpensThePickerAtRoot() {
        val preferences = context.getSharedPreferences("move-target-test", Context.MODE_PRIVATE)
        val store = MobileMoveTargetStore(preferences, "user-1")
        preferences.edit().putString("user-1", "{\"remember\":true,\"lastTarget\":[{\"id\":-3}]}").commit()
        assertEquals(FilesMoveTargetMemory(remember = true), store.read())
        preferences.edit().putString("user-1", "not json").commit()
        assertEquals(FilesMoveTargetMemory(), store.read())
    }
}
