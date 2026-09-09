package io.putdotio.android.share

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileFileShareServiceTest {
    @Test
    fun chooserCarriesOnlyAContentStreamWithAReadGrant() {
        val service = Robolectric.buildService(MobileFileShareService::class.java).create().get()
        val file = File(MobileFileShareService.shareRoot(service), "9/Sintel.mp4").apply {
            parentFile?.mkdirs()
            writeText("bytes")
        }
        val chooser = service.chooserForTest(file)
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        val stream = requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertEquals("content", stream.scheme)
        assertEquals("${service.packageName}.share", stream.authority)
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
        val everything = listOf(chooser, send).joinToString { it.toUri(0) + it.extras?.keySet()?.joinToString().orEmpty() }
        assertFalse(everything.contains("oauth_token"))
        assertFalse(everything.contains("http"))
        assertEquals(stream, send.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun exportedNamesAreSinglePathSegments() {
        assertEquals("a_b_c.mkv", "a/b c.mkv".sanitizedFileName())
        assertEquals("file", "..".sanitizedFileName())
        assertEquals("file", "   ".sanitizedFileName())
        assertEquals(200, "x".repeat(300).sanitizedFileName().length)
    }

    @Test
    fun pruneRemovesEveryExportedCopy() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(MobileFileShareService.shareRoot(context), "1/a.bin").apply { parentFile?.mkdirs(); writeText("a") }
        MobileFileShareService.prune(context)
        assertFalse(MobileFileShareService.shareRoot(context).exists())
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
