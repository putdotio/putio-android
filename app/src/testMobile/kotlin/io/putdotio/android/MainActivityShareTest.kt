package io.putdotio.android

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityShareTest {
    @Test
    fun shareAndEditsSurviveActivityRecreationWithoutReplayingTheScrubbedLaunch() {
        ActivityScenario.launch<MainActivity>(share()).use { scenario ->
            lateinit var retained: MobileTransferDraft
            scenario.onActivity {
                retained = it.transferDraft
                assertEquals(Link, retained.state.value.input)
                assertNull(it.intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
                assertNull(it.intent.clipData)
                retained.edit(Edited)
                retained.acknowledgeNavigation(requireNotNull(retained.state.value.incomingRequestId))
            }
            scenario.recreate()
            scenario.onActivity {
                assertSame(retained, it.transferDraft)
                assertEquals(Edited, it.transferDraft.state.value.input)
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
                assertEquals(Link, transferDraft.state.value.input)
                deliverIntentForTest(
                    Intent(this, MainActivity::class.java).setAction(MobilePlaybackService.ACTION_OPEN_NOW_PLAYING),
                )
                assertTrue(nowPlayingRequests.pending.value)
                assertEquals(Link, transferDraft.state.value.input)
            }
        }
    }

    @Test
    fun savedStateContainsOnlyConsumptionMetadataAndANewActivityDoesNotRestoreTheDraft() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, share()).setup()
        val saved = Bundle()
        try {
            controller.get().transferDraft.edit(Edited)
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

    private fun share(): Intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Link)
        .apply { clipData = ClipData.newPlainText("shared", Link) }
}

private const val Link = "https://example.invalid/file?token=private-share-marker"
private const val Edited = "https://example.invalid/edited?token=private-share-marker"
