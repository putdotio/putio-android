package io.putdotio.android

import android.content.Intent
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileDeepLinksTest {
    @Test
    fun webAndSchemeLinksResolveToTheSameDestinations() {
        assertEquals(MobileDeepLink.Files, parseMobileDeepLink("https://app.put.io/files".toUri()))
        assertEquals(MobileDeepLink.Files, parseMobileDeepLink("https://app.put.io/".toUri()))
        assertEquals(MobileDeepLink.File(FilesItemId(42L)), parseMobileDeepLink("https://app.put.io/files/42?x=1#f".toUri()))
        assertEquals(MobileDeepLink.File(FilesItemId(42L)), parseMobileDeepLink("putio://files/42".toUri()))
        assertEquals(MobileDeepLink.Transfers, parseMobileDeepLink("https://put.io/transfers".toUri()))
        assertEquals(MobileDeepLink.Search, parseMobileDeepLink("putio://search".toUri()))
        assertEquals(MobileDeepLink.History, parseMobileDeepLink("https://www.put.io/history".toUri()))
        assertEquals(MobileDeepLink.Trash, parseMobileDeepLink("putio://trash".toUri()))
        assertEquals(MobileDeepLink.Downloads, parseMobileDeepLink("putio://downloads".toUri()))
    }

    @Test
    fun unknownShapesAndTheAuthCallbackAreNotRoutes() {
        assertNull(parseMobileDeepLink(null))
        assertNull(parseMobileDeepLink("putio://auth?code=secret".toUri()))
        assertNull(parseMobileDeepLink("https://example.com/files/1".toUri()))
        assertNull(parseMobileDeepLink("https://app.put.io/files/0".toUri()))
        assertNull(parseMobileDeepLink("https://app.put.io/files/abc".toUri()))
        assertNull(parseMobileDeepLink("https://app.put.io/files/1/2".toUri()))
        assertNull(parseMobileDeepLink("https://app.put.io/sharing".toUri()))
        assertNull(parseMobileDeepLink("http://app.put.io/files".toUri()))
    }

    @Test
    fun consumingAViewIntentRoutesOnceAndScrubsTheUri() {
        val intent = Intent(Intent.ACTION_VIEW, "https://app.put.io/files/7?oauth_token=secret".toUri())
        assertEquals(MobileDeepLink.File(FilesItemId(7L)), intent.consumeMobileDeepLink())
        assertNull(intent.data)
        assertNull(intent.consumeMobileDeepLink())
        assertNull(Intent(Intent.ACTION_SEND, "https://app.put.io/files/7".toUri()).consumeMobileDeepLink())
        val foreign = Intent(Intent.ACTION_VIEW, "https://example.com/x".toUri())
        assertNull(foreign.consumeMobileDeepLink())
        assertEquals("https://example.com/x", foreign.data.toString())
        val unroutable = Intent(Intent.ACTION_VIEW, "https://app.put.io/files/download?oauth_token=secret".toUri())
        assertNull(unroutable.consumeMobileDeepLink())
        assertNull(unroutable.data)
        val mixedCase = Intent(Intent.ACTION_VIEW, "https://App.Put.io/transfers?oauth_token=secret".toUri())
        assertEquals(MobileDeepLink.Transfers, mixedCase.consumeMobileDeepLink())
        assertNull(mixedCase.data)
        val auth = Intent(Intent.ACTION_VIEW, "putio://auth?code=abc&state=xyz".toUri())
        assertNull(auth.consumeMobileDeepLink())
        assertEquals("putio://auth?code=abc&state=xyz", auth.data.toString())
    }

    @Test
    fun routeUrisRoundTripWithoutQueries() {
        val links = listOf(
            MobileDeepLink.Files, MobileDeepLink.File(FilesItemId(42L)), MobileDeepLink.Transfers,
            MobileDeepLink.Search, MobileDeepLink.History, MobileDeepLink.Trash, MobileDeepLink.Downloads,
        )
        for (link in links) assertEquals(link, parseMobileDeepLink(link.toRouteUri()))
    }
}
