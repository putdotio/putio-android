package io.putdotio.android.tv

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.search.SearchTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TvLaunchRequestTest {
    private val key = TvLinkKey("k3y")

    @Test
    fun `a keyed system search result opens its file and a keyed Watch Next card continues it`() {
        assertEquals(TvLaunchRequest.OpenFile(FilesItemId(55L)), view("putio://files/55~k3y").consume())
        assertEquals(
            TvLaunchRequest.OpenFile(FilesItemId(55L), continueWatching = true),
            view("putio://continue/55~k3y").consume(),
        )
    }

    @Test
    fun `a keyed link to a shared folder opens it`() {
        assertEquals(TvLaunchRequest.OpenFile(FilesItemId(-2L)), view("putio://files/-2~k3y").consume())
        assertEquals(TvLaunchRequest.OpenFile(FilesItemId(-7000L)), view("putio://files/-7000~k3y").consume())
    }

    @Test
    fun `a file link from another app, without this install's key, opens nothing and is dropped`() {
        listOf(
            "putio://files/55",
            "putio://continue/55",
            "putio://files/55~",
            "putio://files/55~k3",
            "putio://files/55~k3y0",
            "putio://continue/55~K3Y",
            "putio://files/55%7Ek3y~other",
        ).forEach { link ->
            val intent = view(link)
            assertNull(link, intent.consume())
            assertNull(link, intent.data)
        }
    }

    @Test
    fun `a search link needs no key, since it opens no file`() {
        assertEquals(TvLaunchRequest.Search(SearchTerm("harbor")), view("putio://search?q=harbor").consume())
    }

    @Test
    fun `a system search query opens Search with it`() {
        val intent = Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, "  harbor film ")

        assertEquals(TvLaunchRequest.Search(SearchTerm("harbor film")), intent.consume())
        assertNull("Consumed once", intent.consume())
        assertNull(Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, " ").consume())
    }

    @Test
    fun `a consumed link leaves the intent, so a replay opens nothing`() {
        val intent = view("putio://files/55~k3y")

        intent.consume()

        assertNull(intent.data)
        assertNull(intent.consume())
    }

    @Test
    fun `anything else opens the app as usual`() {
        listOf(
            "putio://files",
            "putio://files/0~k3y",
            "putio://continue/-2~k3y",
            "putio://files/abc~k3y",
            "putio://files/55~k3y/extra",
            "putio://transfers/55~k3y",
            "https://put.io/files/55~k3y",
            "content://io.put.putio.search/55~k3y",
        ).forEach { link -> assertNull(link, view(link).consume()) }
        assertNull(Intent(Intent.ACTION_MAIN).consume())
    }

    @Test
    fun `a pending request survives saved state as its putio URI`() {
        listOf(
            TvLaunchRequest.OpenFile(FilesItemId(55L)),
            TvLaunchRequest.OpenFile(FilesItemId(55L), continueWatching = true),
            TvLaunchRequest.Search(SearchTerm("harbor & film?")),
        ).forEach { request -> assertEquals(request, request.toUri().toString().toUri().toTvLaunchRequest()) }
    }

    @Test
    fun `the install keeps one key, unguessable from another install's`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val installed = TvLinkKey.get(context)

        assertEquals(installed, TvLinkKey.get(context))
        assertEquals(32, installed.value.length)
        assertNotEquals(key, installed)
        val request = TvLaunchRequest.OpenFile(FilesItemId(55L), continueWatching = true)
        assertEquals(request, Intent(Intent.ACTION_VIEW, request.toUri(installed)).consumeTvLaunchRequest { installed })
    }

    private fun Intent.consume() = consumeTvLaunchRequest { key }

    private fun view(link: String) = Intent(Intent.ACTION_VIEW, link.toUri())
}
