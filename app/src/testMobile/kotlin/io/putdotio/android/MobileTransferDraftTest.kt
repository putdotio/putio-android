package io.putdotio.android

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.transfers.MOBILE_TRANSFER_INPUT_LIMIT
import io.putdotio.android.transfers.MobileShareValidation
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.MobileTransferDraftState
import io.putdotio.android.transfers.parseMobileSharedTransfer
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.transfers.MAX_TRANSFER_LINKS
import io.putdotio.android.transfers.MobileSharedTransfer
import io.putdotio.android.transfers.TorrentUpload
import io.putdotio.android.transfers.TransfersEvent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileTransferDraftTest {
    @Test
    fun authPendingShareSurvivesUntilFirstSignInThenClearsOnSessionExit() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer(FIRST))
        draft.reconcileSession(null)
        draft.reconcileSession(null)
        assertEquals(FIRST, draft.state.value.input)
        draft.reconcileSession(MobileAuthSessionId(1))
        assertEquals(FIRST, draft.state.value.input)
        draft.edit(EDITED)
        draft.reconcileSession(MobileAuthSessionId(1))
        assertEquals(EDITED, draft.state.value.input)
        draft.reconcileSession(null)
        assertEquals(MobileTransferDraftState(), draft.state.value)
        draft.reconcileSession(MobileAuthSessionId(2))
        assertEquals("", draft.state.value.input)
    }

    @Test
    fun changingAccountsClearsBothTheDraftAndPendingReplacement() {
        val draft = MobileTransferDraft()
        draft.reconcileSession(MobileAuthSessionId(1))
        draft.receive(parseMobileSharedTransfer(FIRST))
        draft.receive(parseMobileSharedTransfer(SECOND))
        assertTrue(draft.state.value.pendingReplacement)
        draft.reconcileSession(MobileAuthSessionId(2))
        draft.useSharedLink()
        assertEquals(MobileTransferDraftState(), draft.state.value)
    }

    @Test
    fun aNewSharePreservesAnEditedDraftUntilTheUserChoosesReplacement() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer(FIRST))
        draft.edit(EDITED)
        draft.receive(parseMobileSharedTransfer(SECOND))
        assertEquals(EDITED, draft.state.value.input)
        assertTrue(draft.state.value.pendingReplacement)
        draft.keepDraft()
        assertEquals(EDITED, draft.state.value.input)
        assertFalse(draft.state.value.pendingReplacement)
        assertNull(draft.state.value.incomingRequestId)
        draft.receive(parseMobileSharedTransfer(FIRST))
        draft.receive(parseMobileSharedTransfer(SECOND))
        val requestId = requireNotNull(draft.state.value.incomingRequestId)
        draft.useSharedLink()
        assertEquals(SECOND, draft.state.value.input)
        assertFalse(draft.state.value.pendingReplacement)
        assertTrue(draft.state.value.open)
        assertEquals(requestId, draft.state.value.incomingRequestId)
        draft.acknowledgeNavigation(requestId)
        assertNull(draft.state.value.incomingRequestId)
    }

    @Test
    fun openEmptyDraftIsNotReplacedAndNavigationAcknowledgementCannotConsumeANewerShare() {
        val draft = MobileTransferDraft()
        draft.open()
        draft.receive(parseMobileSharedTransfer(FIRST))
        val firstRequest = requireNotNull(draft.state.value.incomingRequestId)
        assertEquals("", draft.state.value.input)
        assertTrue(draft.state.value.pendingReplacement)
        draft.receive(parseMobileSharedTransfer(SECOND))
        val secondRequest = requireNotNull(draft.state.value.incomingRequestId)
        draft.acknowledgeNavigation(firstRequest)
        assertEquals(secondRequest, draft.state.value.incomingRequestId)
        draft.acknowledgeNavigation(secondRequest)
        assertNull(draft.state.value.incomingRequestId)
        draft.useSharedLink()
        assertEquals(SECOND, draft.state.value.input)
    }

    @Test
    fun validationRequiresEveryLinkToBeCompleteAndEditsClearTheShareError() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer("$FIRST and (magnet:?xt=urn:btih:1)"))
        assertNull(draft.validate())
        assertEquals(MobileShareValidation.InvalidLink, draft.state.value.validation)
        draft.edit("$FIRST  $SECOND\n$FIRST")
        assertEquals(TransfersEvent.Add("$FIRST\n$SECOND"), draft.validate())
        assertEquals("$FIRST\n$SECOND", draft.state.value.input)
        draft.edit((0..MAX_TRANSFER_LINKS).joinToString(" ") { "magnet:?xt=urn:btih:$it" })
        assertNull(draft.validate())
        assertEquals(MobileShareValidation.TooManyLinks, draft.state.value.validation)
        draft.edit("not a link")
        assertNull(draft.state.value.validation)
        assertNull(draft.validate())
        assertEquals(MobileShareValidation.InvalidLink, draft.state.value.validation)
        draft.edit("  $EDITED  ")
        assertEquals(TransfersEvent.Add(EDITED), draft.validate())
        assertEquals(EDITED, draft.state.value.input)
        assertNull(draft.state.value.validation)
        assertTrue(draft.state.value.open)
    }

    @Test
    fun aSharedTorrentSubmitsAsAnUploadToTheChosenFolderAndStaysAfterARejection() {
        val draft = MobileTransferDraft()
        draft.receive(MobileSharedTransfer(torrent = TORRENT))
        draft.chooseDestination(SAMPLE_FOLDER)
        val add = draft.validate()
        assertEquals(TransfersEvent.AddTorrent(TORRENT, SAMPLE_FOLDER.id.value), add)
        draft.setSubmitting(true)
        draft.removeTorrent()
        draft.chooseDestination(null)
        assertSame(TORRENT, draft.state.value.torrent)
        assertEquals(SAMPLE_FOLDER, draft.state.value.destination)
        draft.restoreRejectedTorrent(TORRENT)
        assertSame(TORRENT, draft.state.value.torrent)
        assertTrue(draft.state.value.open)
        draft.removeTorrent()
        assertNull(draft.state.value.torrent)
        assertNull(draft.validate())
        assertEquals(MobileShareValidation.InvalidLink, draft.state.value.validation)
    }

    @Test
    fun theChosenFolderLastsForTheSessionButNotAcrossAccounts() {
        val draft = MobileTransferDraft()
        draft.reconcileSession(MobileAuthSessionId(1))
        draft.chooseDestination(SAMPLE_FOLDER)
        draft.receive(parseMobileSharedTransfer(FIRST))
        assertEquals(TransfersEvent.Add(FIRST, SAMPLE_FOLDER.id.value), draft.validate())
        draft.submissionSucceeded()
        assertEquals(MobileTransferDraftState(destination = SAMPLE_FOLDER), draft.state.value)
        draft.receive(parseMobileSharedTransfer(SECOND))
        assertEquals(SAMPLE_FOLDER, draft.state.value.destination)
        draft.reconcileSession(MobileAuthSessionId(2))
        assertEquals(MobileTransferDraftState(), draft.state.value)
    }

    @Test
    fun linksPutioRefusedReopenTheDraftWhileAddedOnesClear() {
        val draft = MobileTransferDraft()
        draft.chooseDestination(SAMPLE_FOLDER)
        draft.receive(parseMobileSharedTransfer("$FIRST\n$SECOND"))
        draft.setSubmitting(true)
        draft.submissionSucceeded(rejectedLinks = listOf(SECOND))
        val state = draft.state.value
        assertEquals(SECOND, state.input)
        assertEquals(MobileShareValidation.NotAdded, state.validation)
        assertTrue(state.open)
        assertFalse(state.submitting)
        assertEquals(SAMPLE_FOLDER, state.destination)
    }

    @Test
    fun aTorrentReadOffTheMainThreadArrivesLikeAShare() = runTest {
        val draft = MobileTransferDraft(StandardTestDispatcher(testScheduler))
        draft.receiveLater { MobileSharedTransfer(torrent = TORRENT) }
        assertNull(draft.state.value.torrent)
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(TORRENT, draft.state.value.torrent)
        assertTrue(draft.state.value.open)
    }

    @Test
    fun aNewerIntakeOrAnotherAccountSupersedesAPendingTorrentRead() = runTest {
        val draft = MobileTransferDraft(StandardTestDispatcher(testScheduler))
        draft.reconcileSession(MobileAuthSessionId(1))
        val other = TorrentUpload("Other.torrent", TORRENT.content)
        draft.receiveLater { MobileSharedTransfer(torrent = TORRENT) }
        draft.receiveLater { MobileSharedTransfer(torrent = other) }
        settle()
        assertSame(other, draft.state.value.torrent)

        draft.dismiss()
        draft.removeTorrent()
        draft.receiveLater { MobileSharedTransfer(torrent = TORRENT) }
        draft.receive(parseMobileSharedTransfer(FIRST))
        settle()
        assertEquals(FIRST, draft.state.value.input)
        assertNull(draft.state.value.torrent)
        assertFalse(draft.state.value.pendingReplacement)

        draft.dismiss()
        draft.edit("")
        draft.receiveLater { MobileSharedTransfer(torrent = TORRENT) }
        draft.reconcileSession(MobileAuthSessionId(2))
        settle()
        assertEquals(MobileTransferDraftState(), draft.state.value)
    }

    @Test
    fun aTorrentReadBeforeTheFirstSignInSurvivesIt() = runTest {
        val draft = MobileTransferDraft(StandardTestDispatcher(testScheduler))
        draft.reconcileSession(null)
        draft.receiveLater { MobileSharedTransfer(torrent = TORRENT) }
        draft.reconcileSession(MobileAuthSessionId(1))
        settle()
        assertSame(TORRENT, draft.state.value.torrent)
    }

    private fun kotlinx.coroutines.test.TestScope.settle() {
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun runningAddCannotBeEditedDismissedReplacedOrSubmittedTwice() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer(FIRST))
        assertEquals(TransfersEvent.Add(FIRST), draft.validate())
        draft.setSubmitting(true)
        draft.edit(EDITED)
        draft.dismiss()
        draft.receive(parseMobileSharedTransfer(SECOND))
        draft.useSharedLink()
        assertNull(draft.validate())
        assertEquals(FIRST, draft.state.value.input)
        assertTrue(draft.state.value.open)
        assertTrue(draft.state.value.pendingReplacement)
        draft.submissionSucceeded()
        assertEquals(SECOND, draft.state.value.input)
        assertTrue(draft.state.value.open)
        assertFalse(draft.state.value.submitting)
        assertFalse(draft.state.value.pendingReplacement)
    }

    @Test
    fun rejectionPreservesTheEditedValueAndSuccessClearsTheFinishedDraft() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer(FIRST))
        draft.edit(EDITED)
        draft.setSubmitting(true)
        draft.restoreRejectedInput(FIRST)
        assertEquals(EDITED, draft.state.value.input)
        assertFalse(draft.state.value.submitting)
        draft.dismiss()
        assertFalse(draft.state.value.open)
        draft.open()
        assertEquals(EDITED, draft.state.value.input)
        draft.submissionSucceeded()
        assertEquals(MobileTransferDraftState(), draft.state.value)
    }

    @Test
    fun oversizedEditsKeepTheOriginalAndNeverCreateATruncatedSubmission() {
        val draft = MobileTransferDraft()
        draft.edit(FIRST)
        draft.edit("https://example.invalid/" + "a".repeat(MOBILE_TRANSFER_INPUT_LIMIT))
        assertEquals(FIRST, draft.state.value.input)
        assertEquals(MobileShareValidation.TooLong, draft.state.value.validation)
        assertNull(draft.validate())
        draft.edit(EDITED)
        assertEquals(TransfersEvent.Add(EDITED), draft.validate())
        draft.edit("https://example.invalid/" + "東".repeat(MOBILE_TRANSFER_INPUT_LIMIT / 2))
        assertEquals(EDITED, draft.state.value.input)
        assertNull(draft.validate())
    }

    @Test
    fun viewModelRetentionUsesMemoryAndANewOwnerStartsEmpty() {
        val store = ViewModelStore()
        try {
            val provider = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
            val first = provider[MobileTransferDraft::class.java]
            first.receive(parseMobileSharedTransfer(FIRST))
            first.edit(EDITED)
            val recreatedProvider = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
            val retained = recreatedProvider[MobileTransferDraft::class.java]
            assertSame(first, retained)
            assertEquals(EDITED, retained.state.value.input)
            assertFalse(retained.state.value.toString().contains("private-marker"))
            val fresh = MobileTransferDraft()
            assertNotSame(retained, fresh)
            assertEquals(MobileTransferDraftState(), fresh.state.value)
        } finally {
            store.clear()
        }
    }
}

private val TORRENT = TorrentUpload("Harbor film.torrent", "d4:infod4:name6:Harboree".toByteArray())
private val SAMPLE_FOLDER = FilesFolder(FilesItemId(42), "Sample folder")
private const val FIRST = "https://example.invalid/first?token=private-marker"
private const val SECOND = "magnet:?xt=urn:btih:12345"
private const val EDITED = "https://example.invalid/edited?token=private-marker"
