package io.putdotio.android.tv.search

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.BaseColumns
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.MainActivity
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.TvLaunchRequest
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.toTvLaunchRequest
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TvSearchSuggestionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a global search lists the account's matches and each row opens its file`() = runTest {
        val searched = mutableListOf<SearchTerm>()
        val search = globalSearch { term ->
            searched += term
            PutioResult.Success(SearchPage(listOf(video, folder), nextCursor = null, total = 2))
        }
        val cursor = provider(search).query(suggestUri, null, " ?", arrayOf(" harbor "), null)

        assertEquals(listOf(SearchTerm("harbor")), searched)
        val rows = buildList {
            while (cursor.moveToNext()) {
                add(
                    listOf(
                        cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_1)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE)),
                        cursor.getString(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_CONTENT_TYPE)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_DURATION)),
                    ),
                )
            }
        }
        assertEquals(
            listOf(
                listOf(55L, "Harbor film.mp4", "55", "https://api.put.io/screenshots/55.jpg", "video/*", 600_000L),
                listOf(56L, "Harbor stills", "56", null, null, 0L),
            ),
            rows,
        )
        // The search app appends the row's id to searchable.xml's intent data.
        assertEquals(
            TvLaunchRequest.OpenFile(FilesItemId(55L)),
            "${searchableAttribute("searchSuggestIntentData")}/${rows.first()[2]}".toUri().toTvLaunchRequest(),
        )
    }

    @Test
    fun `signed out, the provider answers an empty cursor without searching`() {
        val search = TvGlobalSearch(session = { null }, search = { error("not searched") }, reject = { error("no") })

        val cursor = provider(search).query(suggestUri, null, " ?", arrayOf("harbor"), null)

        assertEquals(0, cursor.count)
    }

    @Test
    fun `a 401 empties the answer and ends the session that searched`() {
        val rejected = mutableListOf<TvAuthSessionId>()
        val failure = PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))
        val search = TvGlobalSearch(
            session = { signedIn },
            search = { PutioResult.Failure(failure) },
            reject = { rejected += it },
        )

        val cursor = provider(search).query(suggestUri, null, " ?", arrayOf("harbor"), null)

        assertEquals(0, cursor.count)
        assertEquals(listOf(signedIn.sessionId), rejected)
    }

    @Test
    fun `no query or another failed search lists nothing and ends no session`() = runTest {
        var calls = 0
        val noQuery = globalSearch { calls++; error("not searched") }
        assertEquals(emptyList<FilesItem>(), noQuery.files("  "))
        assertEquals(emptyList<FilesItem>(), noQuery.files(null))
        assertEquals(0, calls)
        val offline = PutioFailure.NetworkUnavailable(IllegalStateException("offline"))
        val failing = TvGlobalSearch({ signedIn }, { PutioResult.Failure(offline) }, reject = { error("not ended") })
        assertEquals(emptyList<FilesItem>(), failing.files("harbor"))
    }

    @Test
    fun `the system's row limit caps the list`() {
        val many = (1L..30L).map { video.copy(id = FilesItemId(it)) }

        assertEquals(5, tvSearchSuggestions(many, limit = 5).count)
        assertEquals(20, tvSearchSuggestions(many, limit = null).count)
    }

    @Test
    fun `system search reads this variant's provider, which only the search app may use`() {
        assertEquals("true", searchableAttribute("includeInGlobalSearch"))
        val authority = "${context.packageName}.search"
        assertEquals(authority, context.getString(R.string.tv_search_authority))
        // Robolectric implements only the int-flag lookups.
        @Suppress("DEPRECATION")
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, TvSearchSuggestionsProvider::class.java),
            0,
        )
        assertEquals(authority, provider.authority)
        assertEquals("android.permission.GLOBAL_SEARCH", provider.readPermission)
        assertEquals("android.permission.GLOBAL_SEARCH", provider.writePermission)
        assertTrue(provider.exported)
        @Suppress("DEPRECATION")
        val searchable = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA,
        ).metaData.getInt("android.app.searchable")
        assertEquals(R.xml.searchable, searchable)
    }

    private val signedIn = TvAuthState.SignedIn(
        TvAccount(userId = 42L, username = "u", email = "u@example.com", historyEnabled = true),
        TvAuthSessionId(1L),
    )

    private val suggestUri = "content://${context.packageName}.search/${SearchManager.SUGGEST_URI_PATH_QUERY}".toUri()

    private fun globalSearch(search: suspend (SearchTerm) -> PutioResult<SearchPage>) =
        TvGlobalSearch(session = { signedIn }, search = search, reject = { error("not ended") })

    private fun provider(search: TvGlobalSearch) = TvSearchSuggestionsProvider { search }

    /** An attribute of searchable.xml as written, with string references resolved. */
    private fun searchableAttribute(name: String): String? {
        context.resources.getXml(R.xml.searchable).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "searchable") continue
                val index = (0 until parser.attributeCount).first { parser.getAttributeName(it) == name }
                val reference = parser.getAttributeResourceValue(index, 0)
                return if (reference != 0) context.getString(reference) else parser.getAttributeValue(index)
            }
        }
        return null
    }

    private val video = FilesItem(
        id = FilesItemId(55L),
        parentId = FilesItemId(0L),
        name = "Harbor film.mp4",
        type = PutioFileType.VIDEO,
        sizeBytes = 1L,
        createdAt = "2026-10-04T10:00:00",
        playback = FilesPlaybackProgress(0.0, 600.0),
        screenshotUrl = "https://api.put.io/screenshots/55.jpg",
    )

    private val folder = FilesItem(
        id = FilesItemId(56L),
        parentId = FilesItemId(0L),
        name = "Harbor stills",
        type = PutioFileType.FOLDER,
        sizeBytes = 0L,
        createdAt = "2026-10-04T10:00:00",
    )
}
