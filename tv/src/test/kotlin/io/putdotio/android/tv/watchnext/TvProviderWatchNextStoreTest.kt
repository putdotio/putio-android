package io.putdotio.android.tv.watchnext

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.media.tv.TvContract
import io.putdotio.android.MainActivity
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.tv.TvLaunchRequest
import io.putdotio.android.tv.consumeTvLaunchRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** The store against a stand-in for the system TV provider, which owns the real rows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TvProviderWatchNextStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeTvProvider

    @Before
    fun registerProvider() {
        provider = Robolectric.setupContentProvider(FakeTvProvider::class.java, TvContract.AUTHORITY)
    }

    @Test
    fun `a published card is a Continue movie whose intent continues the file in this app`() = runTest {
        val store = TvProviderWatchNextStore(context)

        store.insert(TvWatchNextOwner(42L, 7L), program, engagedAtMillis = 1_234L)

        val row = provider.rows.single()
        assertEquals(
            TvContract.WatchNextPrograms.TYPE_MOVIE,
            row.getAsInteger(TvContract.WatchNextPrograms.COLUMN_TYPE),
        )
        assertEquals(
            TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
            row.getAsInteger(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE),
        )
        assertEquals(
            TvContract.WatchNextPrograms.ASPECT_RATIO_16_9,
            row.getAsInteger(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO),
        )
        val intent = Intent.parseUri(
            row.getAsString(TvContract.WatchNextPrograms.COLUMN_INTENT_URI),
            Intent.URI_INTENT_SCHEME,
        )
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(
            TvLaunchRequest.OpenFile(FilesItemId(7L), continueWatching = true),
            intent.consumeTvLaunchRequest(),
        )

        assertEquals(
            listOf(TvStoredWatchNextProgram(rowId = 1L, TvWatchNextOwner(42L, 7L), program, engagedAtMillis = 1_234L)),
            store.programs(),
        )
    }

    @Test
    fun `an update moves the card and leaves a viewer's removal in place`() = runTest {
        val store = TvProviderWatchNextStore(context)
        store.insert(TvWatchNextOwner(42L, 7L), program, engagedAtMillis = 1_234L)
        provider.rows.single().put(TvContract.WatchNextPrograms.COLUMN_BROWSABLE, 0)

        store.update(1L, TvWatchNextOwner(42L, 7L), program.copy(positionMillis = 300_000L), engagedAtMillis = 5_000L)

        val row = provider.rows.single()
        assertEquals(0, row.getAsInteger(TvContract.WatchNextPrograms.COLUMN_BROWSABLE))
        assertEquals(300_000, row.getAsInteger(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS))
        assertEquals(5_000L, row.getAsLong(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS))
    }

    @Test
    fun `a row another build wrote is read without an owner and can be deleted`() = runTest {
        provider.rows += ContentValues().apply {
            put("_id", 9L)
            put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, "legacy")
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, "Old")
        }
        val store = TvProviderWatchNextStore(context)

        assertEquals(null, store.programs().single().owner)
        store.delete(9L)
        assertEquals(emptyList<ContentValues>(), provider.rows)
    }

    private val program = TvWatchNextProgram(
        fileId = 7L,
        title = "Harbor film.mp4",
        posterUrl = "https://api.put.io/screenshots/abc.jpg",
        positionMillis = 125_000L,
        durationMillis = 600_000L,
    )
}

/** Keeps watch-next rows by id and, like the real provider, refuses selections. */
class FakeTvProvider : ContentProvider() {
    val rows = mutableListOf<ContentValues>()
    private var nextId = 1L

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (selection != null) throw SecurityException("Selection not allowed")
        val columns = requireNotNull(projection)
        return MatrixCursor(columns).apply { rows.forEach { row -> addRow(columns.map { row.get(it) }) } }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = nextId++
        rows += ContentValues(values).apply { put("_id", id) }
        return TvContract.buildWatchNextProgramUri(id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (selection != null) throw SecurityException("Selection not allowed")
        val row = rows.firstOrNull { it.getAsLong("_id") == ContentUris.parseId(uri) } ?: return 0
        row.putAll(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (selection != null) throw SecurityException("Selection not allowed")
        return if (rows.removeAll { it.getAsLong("_id") == ContentUris.parseId(uri) }) 1 else 0
    }

    override fun getType(uri: Uri): String? = null
}
