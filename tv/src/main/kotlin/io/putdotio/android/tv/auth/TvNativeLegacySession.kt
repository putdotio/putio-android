package io.putdotio.android.tv.auth

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.putdotio.android.auth.AccessToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface LegacyTvSession {
    suspend fun read(): AccessToken?

    suspend fun delete()
}

internal class AsyncStorageLegacyTvSession(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LegacyTvSession {
    override suspend fun read(): AccessToken? = withContext(ioDispatcher) {
        val path = context.getDatabasePath(ASYNC_STORAGE_DATABASE)
        if (!path.isFile) return@withContext null
        try {
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
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
