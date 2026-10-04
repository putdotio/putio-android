package io.putdotio.android.tv

import android.app.SearchManager
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.SearchTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TvLaunchRequestTest {
    @Test
    fun `a system search result opens its file and a Watch Next card continues it`() {
        assertEquals(TvLaunchRequest.OpenFile(FilesItemId(55L)), view("putio://files/55").consumeTvLaunchRequest())
        assertEquals(
            TvLaunchRequest.OpenFile(FilesItemId(55L), continueWatching = true),
            view("putio://continue/55").consumeTvLaunchRequest(),
        )
    }

    @Test
    fun `a system search query opens Search with it`() {
        val intent = Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, "  harbor film ")

        assertEquals(TvLaunchRequest.Search(SearchTerm("harbor film")), intent.consumeTvLaunchRequest())
        assertNull("Consumed once", intent.consumeTvLaunchRequest())
        assertNull(Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, " ").consumeTvLaunchRequest())
    }

    @Test
    fun `a consumed link leaves the intent, so a replay opens nothing`() {
        val intent = view("putio://files/55")

        intent.consumeTvLaunchRequest()

        assertNull(intent.data)
        assertNull(intent.consumeTvLaunchRequest())
    }

    @Test
    fun `anything else opens the app as usual`() {
        listOf(
            "putio://files",
            "putio://files/0",
            "putio://files/abc",
            "putio://files/55/extra",
            "putio://transfers/55",
            "https://put.io/files/55",
            "content://io.put.putio.search/55",
        ).forEach { link -> assertNull(link, view(link).consumeTvLaunchRequest()) }
        assertNull(Intent(Intent.ACTION_MAIN).consumeTvLaunchRequest())
    }

    @Test
    fun `a pending request survives saved state as its putio URI`() {
        listOf(
            TvLaunchRequest.OpenFile(FilesItemId(55L)),
            TvLaunchRequest.OpenFile(FilesItemId(55L), continueWatching = true),
            TvLaunchRequest.Search(SearchTerm("harbor & film?")),
        ).forEach { request -> assertEquals(request, request.toUri().toString().toUri().toTvLaunchRequest()) }
    }

    private fun view(link: String) = Intent(Intent.ACTION_VIEW, link.toUri())
}
