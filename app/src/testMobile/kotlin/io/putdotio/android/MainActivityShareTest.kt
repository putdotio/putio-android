package io.putdotio.android

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.MobileTransferDraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.net.Uri
import android.os.Looper

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityShareTest {
    @Test
    fun shareAndEditsSurviveActivityRecreationWithoutReplayingTheScrubbedLaunch() {
        ActivityScenario.launch<MainActivity>(share()).use { scenario ->
            lateinit var retained: MobileTransferDraft
            scenario.onActivity {
                retained = it.transferDraft
                assertEquals(LINK, retained.state.value.input)
                assertNull(it.intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
                assertNull(it.intent.clipData)
                retained.edit(EDITED)
                retained.acknowledgeNavigation(requireNotNull(retained.state.value.incomingRequestId))
            }
            scenario.recreate()
            scenario.onActivity {
                assertSame(retained, it.transferDraft)
                assertEquals(EDITED, it.transferDraft.state.value.input)
                assertFalse(it.transferDraft.state.value.pendingReplacement)
                assertNull(it.transferDraft.state.value.incomingRequestId)
                assertFalse(it.nowPlayingRequests.pending.value)
            }
        }
    }

    @Test
    fun freshSharesAreReceivedWhileHistoryReplaysAreOnlyScrubbed() {
        // ActivityScenario matches lifecycle callbacks against the launch intent's action.
        // This test deliberately replaces that action, so own the Activity lifecycle directly.
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            with(controller.get()) {
                val history = share().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
                deliverIntentForTest(history)
                assertEquals("", transferDraft.state.value.input)
                assertNull(history.getCharSequenceExtra(Intent.EXTRA_TEXT))
                deliverIntentForTest(share())
                assertEquals(LINK, transferDraft.state.value.input)
                deliverIntentForTest(
                    Intent(this, MainActivity::class.java).setAction(MobilePlaybackService.ACTION_OPEN_NOW_PLAYING),
                )
                assertTrue(nowPlayingRequests.pending.value)
                assertEquals(LINK, transferDraft.state.value.input)
            }
        }
    }

    @Test
    fun savedStateContainsOnlyConsumptionMetadataAndANewActivityDoesNotRestoreTheDraft() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, share()).setup()
        val saved = Bundle()
        try {
            controller.get().transferDraft.edit(EDITED)
            controller.saveInstanceState(saved)
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(saved)
                val bytes = parcel.marshall()
                for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
                    assertFalse(String(bytes, charset).contains("private-share-marker"))
                }
            } finally {
                parcel.recycle()
            }
        } finally {
            controller.close()
        }
        val restored = Robolectric.buildActivity(MainActivity::class.java, share())
            .create(saved).start().resume().visible()
        try {
            assertEquals(MobileTransferDraftState(), restored.get().transferDraft.state.value)
            assertNull(restored.get().intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
        } finally {
            restored.close()
        }
    }

    @Test
    fun aTappedMagnetLinkOpensTheDraftWithoutSubmittingAndIsScrubbed() {
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Harbor%20film"
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse(magnet))
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().use { controller ->
            val activity = controller.get()
            val draft = activity.transferDraft.state.value
            assertEquals(magnet, draft.input)
            assertTrue(draft.open)
            assertFalse(draft.submitting)
            assertNull(activity.intent.data)
            assertNull(activity.deepLinkRequests.pending.value)
        }
    }

    @Test
    fun aViewedTorrentIsReadFromItsContentUriIntoTheDraft() {
        val uri = Uri.parse("content://downloads.example/torrents/Harbor%20film.torrent")
        val metainfo = "d8:announce3:url4:infod4:name6:Harboree".toByteArray()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            shadowOf(activity.contentResolver).registerInputStream(uri, metainfo.inputStream())
            activity.deliverIntentForTest(
                Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/x-bittorrent"),
            )
            val deadline = System.currentTimeMillis() + 5_000
            while (activity.transferDraft.state.value.torrent == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val torrent = requireNotNull(activity.transferDraft.state.value.torrent)
            assertEquals("Harbor film.torrent", torrent.fileName)
            assertTrue(metainfo.contentEquals(torrent.content))
            assertTrue(activity.transferDraft.state.value.open)
            assertNull(activity.intent.data)
        }
    }

    private fun share(): Intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, LINK)
        .apply { clipData = ClipData.newPlainText("shared", LINK) }
}

private const val LINK = "https://example.invalid/file?token=private-share-marker"
private const val EDITED = "https://example.invalid/edited?token=private-share-marker"
