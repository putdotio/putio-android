package io.putdotio.android

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.os.Looper
import android.view.DragEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.share.MobileFileDrag
import io.putdotio.android.transfers.MOBILE_TRANSFER_DROP_TARGET_TAG
import io.putdotio.android.transfers.MobileIncomingTransfer
import io.putdotio.android.transfers.MobileShareValidation
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.MobileTransferDropZone
import io.putdotio.android.transfers.isMobileTransferDrop
import io.putdotio.android.transfers.toMobileIncomingTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.DragEventBuilder

/** Drops from other apps through the production drop zone, delivered as the window's drag events. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileTransferDropTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val draft = MobileTransferDraft()
    private val ownDragEnds = mutableListOf<Pair<MobileFileDrag, Boolean>>()

    @Test
    fun aMagnetDraggedInShowsTheTargetThenOpensAnUnsubmittedDraft() {
        setZone()

        drag(DragEvent.ACTION_DRAG_STARTED, description = ClipDescription("link", arrayOf("text/plain")))
        compose.onNodeWithText("Drop to add a transfer").assertExists()
        drag(DragEvent.ACTION_DRAG_LOCATION)
        drag(DragEvent.ACTION_DROP, clip = ClipData.newPlainText("link", MAGNET))
        drag(DragEvent.ACTION_DRAG_ENDED, result = true)

        compose.onNodeWithTag(MOBILE_TRANSFER_DROP_TARGET_TAG).assertDoesNotExist()
        compose.runOnIdle {
            val state = draft.state.value
            assertEquals(MAGNET, state.input)
            assertTrue(state.open)
            assertFalse(state.submitting)
            assertNull(state.validation)
        }
    }

    @Test
    fun aDroppedTorrentIsReadThroughTheShareIntake() {
        val uri = Uri.parse("content://downloads.example/torrents/Harbor%20film.torrent")
        val metainfo = "d8:announce3:url4:infod4:name6:Harboree".toByteArray()
        shadowOf(compose.activity.contentResolver).registerInputStream(uri, metainfo.inputStream())
        setZone()

        val description = ClipDescription("Harbor film.torrent", arrayOf("application/x-bittorrent"))
        drag(DragEvent.ACTION_DRAG_STARTED, description = description)
        drag(DragEvent.ACTION_DRAG_LOCATION)
        drag(DragEvent.ACTION_DROP, clip = ClipData(description, ClipData.Item(uri)))
        drag(DragEvent.ACTION_DRAG_ENDED, result = true)

        val torrent = awaitTorrent()
        assertEquals("Harbor film.torrent", torrent.fileName)
        assertTrue(metainfo.contentEquals(torrent.content))
    }

    @Test
    fun otherFilesAndThisAppsOwnDragsDoNotShowTheTarget() {
        setZone()

        drag(DragEvent.ACTION_DRAG_STARTED, description = ClipDescription("photo", arrayOf("image/png")))
        compose.onNodeWithTag(MOBILE_TRANSFER_DROP_TARGET_TAG).assertDoesNotExist()
        drag(DragEvent.ACTION_DRAG_ENDED)

        val own = MobileFileDrag("key", FilesItemId(9L))
        val description = ClipDescription("poster.jpg", arrayOf("image/jpeg"))
        drag(DragEvent.ACTION_DRAG_STARTED, description = description, localState = own)
        compose.onNodeWithTag(MOBILE_TRANSFER_DROP_TARGET_TAG).assertDoesNotExist()
        drag(DragEvent.ACTION_DRAG_LOCATION, localState = own)
        drag(DragEvent.ACTION_DROP, clip = ClipData.newPlainText("poster", MAGNET), localState = own)
        drag(DragEvent.ACTION_DRAG_ENDED, localState = own, result = false)

        compose.runOnIdle {
            assertEquals("", draft.state.value.input)
            assertEquals(listOf(own to false), ownDragEnds)
        }
    }

    @Test
    fun dropsAreReadAsUntrustedShareInput() {
        assertTrue(ClipDescription("links", arrayOf("text/uri-list")).isMobileTransferDrop())
        assertTrue(ClipDescription("file", arrayOf("application/octet-stream")).isMobileTransferDrop())
        assertFalse(ClipDescription("photo", arrayOf("image/png")).isMobileTransferDrop())

        val links = ClipData.newPlainText("links", "https://example.invalid/a.iso")
            .apply { addItem(ClipData.Item(Uri.parse("https://example.invalid/b.iso"))) }
        val received = links.toMobileIncomingTransfer() as MobileIncomingTransfer.Ready
        assertEquals("https://example.invalid/a.iso\nhttps://example.invalid/b.iso", received.transfer.input)

        // A text file is not a torrent, and its bytes are never read for links.
        val textFile = ClipData(ClipDescription("notes", arrayOf("text/plain")), ClipData.Item(Uri.parse(TEXT_FILE)))
        assertNull(textFile.toMobileIncomingTransfer())

        val tooLong = ClipData.newPlainText("prose", "x".repeat(17 * 1024)).toMobileIncomingTransfer()
        assertEquals(MobileShareValidation.TooLong, (tooLong as MobileIncomingTransfer.Ready).transfer.validation)
    }

    private fun setZone() {
        compose.setContent {
            PutioTheme {
                MobileTransferDropZone(
                    enabled = true,
                    draft = draft,
                    onOwnDragEnded = { drag, dropped -> ownDragEnds += drag to dropped },
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
    }

    private fun drag(
        action: Int,
        description: ClipDescription? = null,
        clip: ClipData? = null,
        localState: Any? = null,
        result: Boolean = false,
    ) {
        val window = compose.activity.window.decorView
        val event = DragEventBuilder.newBuilder()
            .setAction(action)
            .setX(window.width / 2f)
            .setY(window.height / 2f)
            .setClipDescription(description ?: clip?.description)
            .setClipData(clip)
            .setLocalState(localState)
            .setResult(result)
            .build()
        compose.runOnUiThread { window.dispatchDragEvent(event) }
        compose.waitForIdle()
    }

    private fun awaitTorrent(): io.putdotio.android.transfers.TorrentUpload {
        val deadline = System.currentTimeMillis() + 5_000
        while (draft.state.value.torrent == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        return requireNotNull(draft.state.value.torrent)
    }

    private companion object {
        const val MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Harbor%20film"
        const val TEXT_FILE = "content://files.example/notes.txt"
    }
}
