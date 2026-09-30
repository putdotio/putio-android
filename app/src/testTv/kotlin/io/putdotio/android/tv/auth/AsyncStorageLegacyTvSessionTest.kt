package io.putdotio.android.tv.auth

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AsyncStorageLegacyTvSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = AsyncStorageLegacyTvSession(context, Dispatchers.Unconfined)
    private val database: File get() = context.getDatabasePath(RN_DATABASE)

    @Before
    @After
    fun removeFixture() {
        context.deleteDatabase(RN_DATABASE)
    }

    @Test
    fun `reads the tv-native token and deletes the whole database`() = runBlocking {
        writeFixture(RN_TOKEN_KEY to FAKE_TOKEN, UPDATE_NOTICE_KEY to "91")

        assertEquals(FAKE_TOKEN, session.read()?.reveal())

        val journal = File(database.path + "-journal").apply { writeText("left by a crash") }
        session.delete()
        assertFalse(database.exists())
        assertFalse(journal.exists())
        assertNull(session.read())
    }

    @Test
    fun `no database, no token row, or an unusable value reads as no session`() = runBlocking {
        assertNull(session.read())
        assertFalse("reading must not create the database", database.exists())

        writeFixture(UPDATE_NOTICE_KEY to "91")
        assertNull(session.read())

        listOf("", "fake token with spaces", "fake-token\n").forEach { value ->
            writeFixture(RN_TOKEN_KEY to value)
            assertNull(session.read())
        }
    }

    @Test
    fun `a hot journal tv-native left mid-write is rolled back and the token read`() = runBlocking {
        writeFixture(RN_TOKEN_KEY to FAKE_TOKEN)
        val crashed = File(database.parentFile, "crashed")
        // Spill an uncommitted overwrite to the file, then snapshot it with its journal, as a kill mid-write leaves it.
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA cache_size=1", null).use { it.moveToFirst() }
            db.beginTransaction()
            try {
                db.execSQL("UPDATE $RN_TABLE SET value = 'overwritten' WHERE key = ?", arrayOf(RN_TOKEN_KEY))
                repeat(SPILL_ROWS) { index ->
                    db.execSQL("INSERT INTO $RN_TABLE VALUES (?, ?)", arrayOf("filler-$index", "x".repeat(SPILL_BYTES)))
                }
                database.copyTo(crashed, overwrite = true)
                File(database.path + "-journal").copyTo(File(crashed.path + "-journal"), overwrite = true)
            } finally {
                db.endTransaction()
            }
        }
        context.deleteDatabase(RN_DATABASE)
        crashed.renameTo(database)
        File(crashed.path + "-journal").renameTo(File(database.path + "-journal"))

        assertEquals(FAKE_TOKEN, session.read()?.reveal())
    }

    @Test
    fun `a file that is not a database reads as no session and is still deleted`() = runBlocking {
        database.parentFile?.mkdirs()
        database.writeText("not sqlite")

        assertNull(session.read())

        session.delete()
        assertFalse(database.exists())
    }

    /** The schema React Native AsyncStorage 1.23.1 creates (`ReactDatabaseSupplier`). */
    private fun writeFixture(vararg rows: Pair<String, String>) {
        context.deleteDatabase(RN_DATABASE)
        database.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            db.execSQL("CREATE TABLE $RN_TABLE (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            rows.forEach { (key, value) ->
                db.insertOrThrow(
                    RN_TABLE,
                    null,
                    ContentValues().apply {
                        put("key", key)
                        put("value", value)
                    },
                )
            }
        }
    }

    // Literals from tv-native and AsyncStorage, not the production constants, so a wrong name fails here.
    private companion object {
        const val RN_DATABASE = "RKStorage"
        const val RN_TABLE = "catalystLocalStorage"
        const val RN_TOKEN_KEY = "@putio:auth_token"
        const val FAKE_TOKEN = "FAKETOKEN0000000000000000000000000"
        const val UPDATE_NOTICE_KEY = "@putio:update-notified-for"
        const val SPILL_ROWS = 64
        const val SPILL_BYTES = 4096
    }
}
