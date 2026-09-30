package io.putdotio.android.tv.auth

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.putdotio.android.auth.AccessToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The session the React Native TV app (putio-web `apps/tv-native`) left behind
 * under the same application id. Read once when there is no Keystore session,
 * then deleted whatever the outcome.
 */
internal interface LegacyTvSession {
    /** The legacy token, or null when there is none or it cannot be read. */
    suspend fun read(): AccessToken?

    /** Deletes every legacy copy; nothing reads it again. */
    suspend fun delete()
}

/**
 * tv-native stores the token as a raw string in React Native AsyncStorage
 * 1.23.1 (`token-storage.android.ts`), whose Android backend is the SQLite
 * database `RKStorage`, table `catalystLocalStorage` (`key TEXT PRIMARY KEY,
 * value TEXT NOT NULL`); tv-native does not opt into the Room-based
 * `AsyncStorage_useNextStorage`. Its only other key is an update notice, so
 * the whole database goes, journals included, rather than leaving the token in
 * free pages after a row delete.
 */
internal class AsyncStorageLegacyTvSession(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LegacyTvSession {
    override suspend fun read(): AccessToken? = withContext(ioDispatcher) {
        val path = context.getDatabasePath(ASYNC_STORAGE_DATABASE)
        if (!path.isFile) return@withContext null
        try {
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery(TOKEN_QUERY, arrayOf(TV_NATIVE_AUTH_TOKEN_KEY)).use { cursor ->
                    if (cursor.moveToFirst()) AccessToken.parse(cursor.getString(0)) else null
                }
            }
        } catch (_: SQLiteException) {
            null
        }
    }

    override suspend fun delete() {
        withContext(ioDispatcher) { context.deleteDatabase(ASYNC_STORAGE_DATABASE) }
    }
}

internal const val ASYNC_STORAGE_DATABASE = "RKStorage"
internal const val ASYNC_STORAGE_TABLE = "catalystLocalStorage"
internal const val TV_NATIVE_AUTH_TOKEN_KEY = "@putio:auth_token"
private const val TOKEN_QUERY = "SELECT value FROM $ASYNC_STORAGE_TABLE WHERE key = ?"
