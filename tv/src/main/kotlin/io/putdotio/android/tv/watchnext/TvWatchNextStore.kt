package io.putdotio.android.tv.watchnext

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.SQLException
import android.provider.BaseColumns
import android.media.tv.TvContract
import android.media.tv.TvContract.WatchNextPrograms
import io.putdotio.android.MainActivity
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.tv.TvLaunchRequest
import io.putdotio.android.tv.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whose card a stored program is: the signed-in user it was published for, and its file. */
internal data class TvWatchNextOwner(
    val userId: Long,
    val fileId: Long,
)

/** A program this app published, as the system provider holds it. */
internal data class TvStoredWatchNextProgram(
    val rowId: Long,
    /** Null for a row this app cannot attribute; it is removed rather than trusted. */
    val owner: TvWatchNextOwner?,
    val program: TvWatchNextProgram,
    val engagedAtMillis: Long,
)

/** The system's Watch Next programs this app owns; the provider refuses anyone else's. */
internal interface TvWatchNextStore {
    suspend fun programs(): List<TvStoredWatchNextProgram>

    suspend fun insert(owner: TvWatchNextOwner, program: TvWatchNextProgram, engagedAtMillis: Long)

    suspend fun update(rowId: Long, owner: TvWatchNextOwner, program: TvWatchNextProgram, engagedAtMillis: Long)

    suspend fun delete(rowId: Long)
}

/**
 * [TvWatchNextStore] on the Android TV provider. Watch Next is best effort: a device without
 * the provider (Fire TV) or one that revokes access reads as empty and drops writes, and the
 * provider rejects selections, so every lookup reads this app's rows and filters them here.
 */
internal class TvProviderWatchNextStore(context: Context) : TvWatchNextStore {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    override suspend fun programs(): List<TvStoredWatchNextProgram> = provider(emptyList()) {
        resolver.query(WatchNextPrograms.CONTENT_URI, PROJECTION, null, null, null)
            ?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toStored()) } }
            .orEmpty()
    }

    override suspend fun insert(owner: TvWatchNextOwner, program: TvWatchNextProgram, engagedAtMillis: Long) {
        provider(Unit) {
            resolver.insert(
                WatchNextPrograms.CONTENT_URI,
                watchNextProgramValues(context, owner, program, engagedAtMillis),
            )
        }
    }

    override suspend fun update(
        rowId: Long,
        owner: TvWatchNextOwner,
        program: TvWatchNextProgram,
        engagedAtMillis: Long,
    ) {
        provider(Unit) {
            resolver.update(
                TvContract.buildWatchNextProgramUri(rowId),
                watchNextProgramValues(context, owner, program, engagedAtMillis),
                null,
                null,
            )
        }
    }

    override suspend fun delete(rowId: Long) {
        provider(Unit) { resolver.delete(TvContract.buildWatchNextProgramUri(rowId), null, null) }
    }

    private suspend fun <T> provider(fallback: T, block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (_: SecurityException) {
                fallback
            } catch (_: IllegalArgumentException) {
                // No TV provider on this device: the URI is unknown.
                fallback
            } catch (_: IllegalStateException) {
                fallback
            } catch (_: SQLException) {
                fallback
            }
        }
}

/**
 * The provider row for [program]: a movie-type Continue card whose intent reopens the file in
 * this app and continues it. Leaves `browsable` out, so a card the viewer removed from the row
 * stays removed when its position changes. The platform's `TvContract` (API 26, the app's
 * minimum) names the columns; androidx.tvprovider's builder sits on library-restricted classes.
 */
internal fun watchNextProgramValues(
    context: Context,
    owner: TvWatchNextOwner,
    program: TvWatchNextProgram,
    engagedAtMillis: Long,
): ContentValues =
    ContentValues().apply {
        put(WatchNextPrograms.COLUMN_TYPE, WatchNextPrograms.TYPE_MOVIE)
        put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
        put(WatchNextPrograms.COLUMN_TITLE, program.title)
        put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, engagedAtMillis)
        put(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, program.positionMillis.toIntMillis())
        put(WatchNextPrograms.COLUMN_DURATION_MILLIS, program.durationMillis.toIntMillis())
        put(WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, WatchNextPrograms.ASPECT_RATIO_16_9)
        put(WatchNextPrograms.COLUMN_POSTER_ART_URI, program.posterUrl)
        put(
            WatchNextPrograms.COLUMN_INTENT_URI,
            watchNextIntent(context, program.fileId).toUri(Intent.URI_INTENT_SCHEME),
        )
        put(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, owner.toInternalId())
    }

/** Opens the file in this app's activity and continues it from the saved position. */
internal fun watchNextIntent(context: Context, fileId: Long): Intent =
    Intent(Intent.ACTION_VIEW, TvLaunchRequest.OpenFile(FilesItemId(fileId), continueWatching = true).toUri())
        .setComponent(ComponentName(context, MainActivity::class.java))

private fun Cursor.toStored(): TvStoredWatchNextProgram {
    val owner = string(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)?.toOwner()
    return TvStoredWatchNextProgram(
        rowId = getLong(getColumnIndexOrThrow(BaseColumns._ID)),
        owner = owner,
        program = TvWatchNextProgram(
            fileId = owner?.fileId ?: 0L,
            title = string(WatchNextPrograms.COLUMN_TITLE).orEmpty(),
            posterUrl = string(WatchNextPrograms.COLUMN_POSTER_ART_URI),
            positionMillis = long(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS),
            durationMillis = long(WatchNextPrograms.COLUMN_DURATION_MILLIS),
        ),
        engagedAtMillis = long(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS),
    )
}

private fun Cursor.string(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun Cursor.long(column: String): Long =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L

private fun TvWatchNextOwner.toInternalId(): String = "$userId:$fileId"

private fun String.toOwner(): TvWatchNextOwner? {
    val parts = split(':').takeIf { it.size == 2 }
    val userId = parts?.get(0)?.toLongOrNull()
    val fileId = parts?.get(1)?.toLongOrNull()?.takeIf { it > 0L }
    return if (userId != null && fileId != null) TvWatchNextOwner(userId, fileId) else null
}

private fun Long.toIntMillis(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private val PROJECTION = arrayOf(
    BaseColumns._ID,
    WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
    WatchNextPrograms.COLUMN_TITLE,
    WatchNextPrograms.COLUMN_POSTER_ART_URI,
    WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
    WatchNextPrograms.COLUMN_DURATION_MILLIS,
    WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
)
