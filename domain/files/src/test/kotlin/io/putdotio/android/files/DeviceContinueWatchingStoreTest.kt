package io.putdotio.android.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeviceContinueWatchingStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val entry = ContinueWatchingEntry(FilesItemId(7L), "Sintel.2010.1080p.mkv", 356.0, 891.0, 5L)

    @Test
    fun keepsEachUsersRowAndASignOutRetiresTheStoresMadeBeforeIt() {
        val first = DeviceContinueWatchingStore(context, userId = 1L)
        first.write(listOf(entry))
        assertEquals(listOf(entry), DeviceContinueWatchingStore(context, 1L).read())
        assertTrue(DeviceContinueWatchingStore(context, 2L).read().isEmpty())

        DeviceContinueWatchingStore.clearAll(context)
        // A read the ended session still had out lands after the wipe and writes nothing.
        first.write(listOf(entry))

        assertTrue(DeviceContinueWatchingStore(context, 1L).read().isEmpty())
        val next = DeviceContinueWatchingStore(context, 1L)
        next.write(listOf(entry))
        assertEquals(listOf(entry), next.read())
    }
}
