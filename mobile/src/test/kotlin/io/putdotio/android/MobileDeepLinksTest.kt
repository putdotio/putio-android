package io.putdotio.android

import android.content.Intent
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(
            MobileDeepLink.File(FilesItemId(42L)),
            parseMobileDeepLink("https://app.put.io/files/42?x=1#f".toUri()),
        )
        assertEquals(MobileDeepLink.File(FilesItemId(42L)), parseMobileDeepLink("putio://files/42".toUri()))
        assertEquals(MobileDeepLink.Transfers, parseMobileDeepLink("https://put.io/transfers".toUri()))
        assertEquals(MobileDeepLink.Search, parseMobileDeepLink("putio://search".toUri()))
        assertEquals(MobileDeepLink.History, parseMobileDeepLink("https://www.put.io/history".toUri()))
        assertEquals(MobileDeepLink.Trash, parseMobileDeepLink("putio://trash".toUri()))
        assertEquals(MobileDeepLink.Downloads(), parseMobileDeepLink("putio://downloads".toUri()))
        // A download notification opens its own row.
        assertEquals(MobileDeepLink.Downloads(FilesItemId(42L)), parseMobileDeepLink("putio://downloads/42".toUri()))
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
        assertNull(parseMobileDeepLink("putio://downloads/0".toUri()))
        assertNull(parseMobileDeepLink("putio://downloads/42/1".toUri()))
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
    fun routeUrisRoundTripWithOnlyTheDownloadsAccount() {
        val links = listOf(
            MobileDeepLink.Files, MobileDeepLink.File(FilesItemId(42L)), MobileDeepLink.Transfers,
            MobileDeepLink.AddTransfer, MobileDeepLink.Search, MobileDeepLink.History, MobileDeepLink.Trash,
            MobileDeepLink.Downloads(), MobileDeepLink.Downloads(FilesItemId(42L)),
            MobileDeepLink.Downloads(FilesItemId(42L), userId = 7L),
            MobileDeepLink.Downloads(FilesItemId(42L), play = true, userId = 7L),
        )
        for (link in links) assertEquals(link, parseMobileDeepLink(link.toRouteUri()))
        assertEquals("putio://downloads/42/play?user=7", links.last().toRouteUri().toString())
    }

    @Test
    fun theAddTransferShortcutAndNotificationActionsHaveTheirOwnLinks() {
        assertEquals(MobileDeepLink.AddTransfer, parseMobileDeepLink("putio://transfers/add".toUri()))
        assertNull(parseMobileDeepLink("putio://transfers/remove".toUri()))
        assertEquals(
            MobileDeepLink.Downloads(FilesItemId(42L), play = true, userId = 7L),
            parseMobileDeepLink("putio://downloads/42/play?user=7".toUri()),
        )
        // Only Downloads links read an account; anywhere else the query is ignored as before.
        assertEquals(MobileDeepLink.Search, parseMobileDeepLink("putio://search?user=7".toUri()))
        // A malformed account voids the link rather than widening it to any account.
        assertNull(parseMobileDeepLink("putio://downloads/42?user=abc".toUri()))
        assertNull(parseMobileDeepLink("putio://downloads/42?user=0".toUri()))
        assertNull(parseMobileDeepLink("putio://downloads/42/stop".toUri()))
        assertNull(parseMobileDeepLink("putio://downloads/42/play/1".toUri()))
        // An opaque URI has no query to read; it opens Files as before instead of throwing.
        val opaque = Intent(Intent.ACTION_VIEW, "putio:downloads?user=7".toUri())
        assertEquals(MobileDeepLink.Files, opaque.consumeMobileDeepLink())
    }

    @Test
    fun aLinkScopedToAnAccountBelongsOnlyToThatAccount() {
        assertTrue(MobileDeepLink.Downloads(FilesItemId(42L), userId = 7L).belongsTo(7L))
        assertFalse(MobileDeepLink.Downloads(FilesItemId(42L), play = true, userId = 7L).belongsTo(8L))
        assertTrue(MobileDeepLink.Downloads(FilesItemId(42L)).belongsTo(8L))
        assertTrue(MobileDeepLink.AddTransfer.belongsTo(8L))
    }
}
