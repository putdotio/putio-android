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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
    fun validationRequiresOneCompleteLinkAndEditsClearTheShareError() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer("$FIRST and $SECOND"))
        assertNull(draft.validate())
        assertEquals(MobileShareValidation.MultipleLinks, draft.state.value.validation)
        draft.edit("not a link")
        assertNull(draft.state.value.validation)
        assertNull(draft.validate())
        assertEquals(MobileShareValidation.InvalidLink, draft.state.value.validation)
        draft.edit("  $EDITED  ")
        assertEquals(EDITED, draft.validate())
        assertEquals(EDITED, draft.state.value.input)
        assertNull(draft.state.value.validation)
        assertTrue(draft.state.value.open)
    }

    @Test
    fun runningAddCannotBeEditedDismissedReplacedOrSubmittedTwice() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer(FIRST))
        assertEquals(FIRST, draft.validate())
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
        assertEquals(EDITED, draft.validate())
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

private const val FIRST = "https://example.invalid/first?token=private-marker"
private const val SECOND = "magnet:?xt=urn:btih:12345"
private const val EDITED = "https://example.invalid/edited?token=private-marker"
