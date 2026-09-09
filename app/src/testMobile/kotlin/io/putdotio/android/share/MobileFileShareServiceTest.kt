package io.putdotio.android.share

import android.content.Intent
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

        val notification = service.readyNotificationForTest("Sintel.mp4", chooser)
        val launched = requireNotNull(shadowOf(notification.contentIntent).savedIntent)
        assertEquals(Intent.ACTION_CHOOSER, launched.action)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val shown = shadowOf(notification).contentText.toString() + launched.toUri(0)
        assertFalse(shown.contains("oauth_token"))
        assertFalse(shown.contains("http"))
    }

    @Test
    fun exportedNamesAreSinglePathSegments() {
        assertEquals("a_b_c.mkv", "a/b c.mkv".sanitizedFileName())
        assertEquals("file", "..".sanitizedFileName())
        assertEquals("file", "   ".sanitizedFileName())
        assertEquals(200, "x".repeat(300).sanitizedFileName().length)
    }

    @Test
    fun invalidStartStopsOnlyItsOwnStartId() {
        val controller = Robolectric.buildService(MobileFileShareService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(service, MobileFileShareService::class.java), 0, 7)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(shadowOf(service).isForegroundStopped)
        assertEquals(7, shadowOf(service).stopSelfId)
    }

    @Test
    fun secondStartDoesNotStopTheServiceWhenTheFirstJobEnds() {
        val controller = Robolectric.buildService(MobileFileShareService::class.java).create()
        val service = controller.get()
        val first = Intent(service, MobileFileShareService::class.java).putExtra("fileId", 1L).putExtra("name", "a")
        val second = Intent(service, MobileFileShareService::class.java).putExtra("fileId", 2L).putExtra("name", "b")
        service.onStartCommand(first, 0, 1)
        service.onStartCommand(second, 0, 2)
        // Without a session both exports fail off the main thread. Each job stops only its own startId, so
        // the framework keeps the service alive until the newest start reports; the last stop must carry 2.
        val deadline = System.currentTimeMillis() + 10_000
        while (shadowOf(service).stopSelfId != 2 && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(2, shadowOf(service).stopSelfId)
        service.onDestroy()
    }
}
