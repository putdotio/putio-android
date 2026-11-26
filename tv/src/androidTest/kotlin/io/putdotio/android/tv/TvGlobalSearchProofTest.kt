package io.putdotio.android.tv

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.search.SearchTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvGlobalSearchProofTest {
    @Test
    fun theProviderAnswersASearchWithTheFixture() {
        val arguments = InstrumentationRegistry.getArguments()
        val query = arguments.getString("putio.tv.globalSearch.query")
        val fileId = arguments.getString("putio.tv.globalSearch.fileId")
        assumeTrue("TV global search proof requires a query and a file id", query != null && fileId != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://${context.packageName}.search/${SearchManager.SUGGEST_URI_PATH_QUERY}")

        val cursor = checkNotNull(context.contentResolver.query(uri, null, " ?", arrayOf(query), null)) {
            "The provider answers"
        }
        val rows = cursor.use {
            check(it.moveToFirst()) { "No rows" }
            val searchRow = it.getString(it.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_INTENT_DATA))
            assertEquals(
                TvLaunchRequest.Search(SearchTerm(checkNotNull(query).trim())),
                Intent(Intent.ACTION_VIEW, Uri.parse(searchRow)).consumeTvLaunchRequest { TvLinkKey.get(context) },
            )
            buildList {
                while (it.moveToNext()) {
                    val link = it.getString(it.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID))
                    val id = link.substringBefore(TvLinkKey.SEPARATOR)
                    check(link.substringAfter(TvLinkKey.SEPARATOR, "").isNotEmpty()) { "Row $id carries no link key" }
                    add(id to it.getString(it.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_1)))
                }
            }
        }

        val match = rows.firstOrNull { it.first == fileId }
        assertNotNull("Rows: ${rows.map { it.first }}", match)
        assertTrue(checkNotNull(match).second.contains(checkNotNull(query)))
    }
}
