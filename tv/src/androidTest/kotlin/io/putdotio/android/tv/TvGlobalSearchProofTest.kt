package io.putdotio.android.tv

import android.app.SearchManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live proof of the system search provider: in an install signed in with `devs-auto`, queries
 * `searchable.xml`'s suggestions authority the way the system search app does, from this app's
 * process so the GLOBAL_SEARCH read permission does not apply, and expects the caller's fixture
 * as a row whose intent data id is the fixture's file id. The query runs in a fresh process, so
 * it also covers the provider's quiet session restore. Opt-in; makes one search request.
 */
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
            buildList {
                while (it.moveToNext()) {
                    val id = it.getString(it.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID))
                    add(id to it.getString(it.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_1)))
                }
            }
        }

        val match = rows.firstOrNull { it.first == fileId }
        assertNotNull("Rows: ${rows.map { it.first }}", match)
        assertTrue(checkNotNull(match).second.contains(checkNotNull(query)))
    }
}
